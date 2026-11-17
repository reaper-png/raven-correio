package fun.rederaven.correio.entity;

import fun.rederaven.correio.RavenCorreio;
import fun.rederaven.correio.data.CorreioData;
import fun.rederaven.correio.data.Encomenda;
import fun.rederaven.correio.menu.CorreioMenu;
import fun.rederaven.correio.permission.CorreioPermissions;
import fun.rederaven.correio.server.ServerSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Ephemeral courier avatar. Physical NPC is NOT the source of truth for any items. */
public final class CarteiroEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> SKIN_ID = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> SKIN_URL = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> SKIN_SLIM = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SKIN_REVISION = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> WALKING = SynchedEntityData.defineId(CarteiroEntity.class, EntityDataSerializers.BOOLEAN);

    /** Physical animation states; parcels are always held by CorreioData, never by the entity. */
    public enum Phase { APPROACHING, SENDING, ARRIVING, DELIVERING, DEPARTING }
    private static final double ARRIVAL_HEIGHT = 300.0;
    private static final int ARRIVAL_DURATION_TICKS = 120;
    private static final double MAX_RISE = 300.0;
    private static final int DEPARTURE_TIMEOUT_TICKS = 200;
    private static final double GROUND_FOLLOW_DISTANCE_SQR = 49.0;
    // 19 seconds: enough to walk 50 blocks at the slower default speed.
    private static final int APPROACH_TIMEOUT_TICKS = 380;
    private static final double SENDER_STOP_DISTANCE = 2.6;
    private Phase phase = Phase.DELIVERING;
    private UUID ownerId;
    private UUID receiverId;
    private String receiverName;
    private int departTicks;
    private int arrivalTicks;
    private double arrivalStartHeight;
    private double departureTargetY;
    private int approachTicks;
    private boolean approachFlying;

    public CarteiroEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoGravity(true);
        setInvulnerable(true);
        setSilent(true);
        setNoAi(true);
        noPhysics = true; // Courier can rise through roofs and arrive over the void safely.
        setCustomName(Component.literal("Carteiro"));
        setCustomNameVisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN_NAME, "");
        builder.define(SKIN_ID, Optional.empty());
        builder.define(SKIN_URL, "");
        builder.define(SKIN_SLIM, false);
        builder.define(SKIN_REVISION, 0);
        builder.define(WALKING, false);
    }

    public String skinName() { return entityData.get(SKIN_NAME); }
    public Optional<UUID> skinId() { return entityData.get(SKIN_ID); }
    public String skinUrl() { return entityData.get(SKIN_URL); }
    public boolean skinSlim() { return entityData.get(SKIN_SLIM); }
    public int skinRevision() { return entityData.get(SKIN_REVISION); }
    public void setSkin(String name, UUID uuid, int revision) {
        entityData.set(SKIN_NAME, name);
        entityData.set(SKIN_ID, Optional.ofNullable(uuid));
        entityData.set(SKIN_URL, "");
        entityData.set(SKIN_SLIM, false);
        entityData.set(SKIN_REVISION, revision);
    }
    public void setSkinUrl(String url, boolean slim, int revision) {
        entityData.set(SKIN_NAME, "");
        entityData.set(SKIN_ID, Optional.empty());
        entityData.set(SKIN_URL, url);
        entityData.set(SKIN_SLIM, slim);
        entityData.set(SKIN_REVISION, revision);
    }

    public void setupSender(ServerPlayer sender, UUID recipient, String recipientName) {
        phase = Phase.APPROACHING;
        entityData.set(WALKING, false);
        ownerId = sender.getUUID();
        receiverId = recipient;
        receiverName = recipientName;
        approachTicks = 0;
        Vec3 start = pointAhead(sender, ServerSettings.get().senderSpawnDistance());
        approachFlying = isFlyingOrVoid(sender);
        if (!approachFlying && level() instanceof ServerLevel serverLevel) {
            double feet = findWalkableY(serverLevel, start.x, start.z, sender.getY());
            if (Double.isFinite(feet)) start = new Vec3(start.x, feet, start.z);
            else approachFlying = true; // e.g. void, cave or very steep terrain
        }
        if (approachFlying) start = new Vec3(start.x, sender.getY(), start.z);
        setPos(start.x, start.y, start.z);
        facePlayer(sender);
    }

    public void setupRecipient(ServerPlayer receiver) {
        phase = Phase.ARRIVING;
        entityData.set(WALKING, false);
        ownerId = receiver.getUUID();
        arrivalTicks = 0;
        Vec3 landing = front(receiver);
        // A requested 300-block drop must not create an NPC above this dimension's
        // world ceiling (e.g. Y=364 in an Overworld with max build height 320).
        // Use as much of ARRIVAL_HEIGHT as the world allows.
        double ceiling = receiver.level().getMaxBuildHeight() - 2.0;
        arrivalStartHeight = Math.max(0.0, Math.min(ARRIVAL_HEIGHT, ceiling - landing.y));
        setPos(landing.x, landing.y + arrivalStartHeight, landing.z);
        facePlayer(receiver);
        playCourierSound(SoundEvents.ELYTRA_FLYING, 1.10F, 1.35F);
    }

    public boolean isDeliveryFor(UUID id) {
        // ARRIVING also counts as present: server must not schedule duplicate couriers.
        return (phase == Phase.ARRIVING || phase == Phase.DELIVERING) && id.equals(ownerId);
    }
    public boolean isSenderFor(UUID id) {
        return (phase == Phase.APPROACHING || phase == Phase.SENDING) && id.equals(ownerId);
    }
    public boolean activeFor(UUID id) {
        // The NPC cannot be clicked while falling or ascending.
        return (phase == Phase.SENDING || phase == Phase.DELIVERING) && id.equals(ownerId);
    }

    private static boolean isFlyingOrVoid(ServerPlayer target) {
        return target.getAbilities().flying || target.isFallFlying()
            || !target.onGround() || target.getY() < target.level().getMinBuildHeight() + 8;
    }

    /**
     * The courier follows the PLAYER ENTITY, not the spectator / third-person camera.
     * Its initial position is derived from the player's yaw. Camera pitch is intentionally
     * ignored so the NPC won't jump when the player looks up/down.
     */
    private static Vec3 pointAhead(ServerPlayer target, double distance) {
        double angle = Math.toRadians(target.getYRot());
        return target.position().add(-Math.sin(angle) * distance, 0, Math.cos(angle) * distance);
    }

    private static Vec3 front(ServerPlayer target) {
        if (isFlyingOrVoid(target)) {
            Vec3 ahead = pointAhead(target, 2.4);
            return new Vec3(ahead.x, target.getEyeY() - 0.9, ahead.z);
        }
        return pointAhead(target, 2.1);
    }

    /** Find solid floor and two free blocks near current Y without loading remote chunks.
     *  This is a light terrain-follower, NOT a full navigation AI; obstacles switch to flight.
     */
    private static double findWalkableY(ServerLevel level, double x, double z, double nearY) {
        int base = (int) Math.floor(nearY);
        BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        for (int offset = 0; offset <= 6; offset++) {
            for (int sign : new int[] { -1, 1 }) {
                if (offset == 0 && sign > 0) continue;
                int feet = base + offset * sign;
                if (feet < level.getMinBuildHeight() + 1 || feet >= level.getMaxBuildHeight() - 2) continue;
                support.set(x, feet - 1, z);
                if (!level.hasChunkAt(support)) return Double.NaN;
                if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) continue;
                BlockPos atFeet = support.above();
                BlockPos atHead = atFeet.above();
                if (level.getBlockState(atFeet).getCollisionShape(level, atFeet).isEmpty()
                    && level.getBlockState(atHead).getCollisionShape(level, atHead).isEmpty()) return feet;
            }
        }
        return Double.NaN;
    }

    /** Sounds are server-broadcast; no custom sound assets required on the client. */
    private void playCourierSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, getX(), getY(), getZ(), sound,
                SoundSource.PLAYERS, (float) volume, (float) pitch); 
        }
    }

    /** Rotate body AND head, so the player never sees the courier permanently backwards. */
    private void facePlayer(ServerPlayer target) {
        Vec3 eye = target.getEyePosition();
        double dx = eye.x - getX();
        double dy = eye.y - (getY() + getEyeHeight());
        double dz = eye.z - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        // Set both current and previous rotations to avoid the body facing away during interpolation.
        yBodyRotO = yaw;
        yHeadRotO = yaw;
        setXRot(Math.max(-45F, Math.min(45F, pitch)));
    }

    private void placeInFront(ServerPlayer target) {
        Vec3 where = front(target);
        setPos(where.x, where.y, where.z);
        facePlayer(target);
    }

    /** Both sender and recipient couriers depart vertically, as far as the world permits. */
    public void beginDeparture() {
        if (phase == Phase.DEPARTING) return;
        phase = Phase.DEPARTING;
        entityData.set(WALKING, false);
        departTicks = 0;
        // Fly UP by 300 blocks regardless of terrain, roofs, or world height.
        // The NPC is ephemeral and has noPhysics, and is never the parcel inventory.
        departureTargetY = getY() + MAX_RISE;
        setDeltaMovement(Vec3.ZERO);
        playCourierSound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.25F, 0.83F);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Stable walking animation even with server-authoritative no-AI movement.
            if (entityData.get(WALKING)) walkAnimation.update(0.32F, 0.30F);
            return;
        }
        if (!(level() instanceof ServerLevel level)) return;
        if (ownerId == null) { discard(); return; }

        ServerPlayer target = level.getServer().getPlayerList().getPlayer(ownerId);

        // All phases, including takeoff, keep their head and BODY oriented toward the
        // player entity itself. We never inspect the spectator / third-person camera.
        if (target != null && !target.isRemoved() && target.level() == level) {
            facePlayer(target);
        }

        if (phase == Phase.DEPARTING) {
            departTicks++;
            // Accelerating rocket-style vertical departure, up to 300 blocks above takeoff.
            double speed = Math.min(4.2, 0.35 + departTicks * 0.065);
            setPos(getX(), Math.min(departureTargetY, getY() + speed), getZ());
            setDeltaMovement(Vec3.ZERO);
            // Whoosh while the player can still hear the ascending courier.
            if (departTicks == 8 || departTicks == 20 || departTicks == 32) {
                playCourierSound(SoundEvents.ELYTRA_FLYING, 1.0F, 1.1F + departTicks * 0.008F);
            }
            if (getY() >= departureTargetY - 0.001 || departTicks >= DEPARTURE_TIMEOUT_TICKS) {
                discard();
            }
            return;
        }

        if (target == null || target.isRemoved() || target.level() != level) {
            // Items are in persisted SavedData; the NPC can safely disappear on logout.
            discard();
            return;
        }

        if (phase == Phase.APPROACHING) {
            approachTicks++;
            Vec3 destination = front(target);
            Vec3 delta = destination.subtract(position());
            if (delta.lengthSqr() <= SENDER_STOP_DISTANCE * SENDER_STOP_DISTANCE
                || approachTicks >= APPROACH_TIMEOUT_TICKS) {
                phase = Phase.SENDING;
                entityData.set(WALKING, false);
                // On timeout or sudden player move, finish the journey in front of them.
                placeInFront(target);
                setDeltaMovement(Vec3.ZERO);
                playCourierSound(SoundEvents.VILLAGER_YES, 0.8F, 1.1F);
                target.sendSystemMessage(Component.literal("§aO §eCarteiro §achegou! Clique nele para montar a encomenda."));
                return;
            }
            boolean fly = isFlyingOrVoid(target) || approachFlying;
            if (!fly) {
                double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                if (horizontal > 0.0001) {
                    double step = Math.min(ServerSettings.get().senderWalkSpeed(), horizontal);
                    double x = getX() + delta.x / horizontal * step;
                    double z = getZ() + delta.z / horizontal * step;
                    double y = findWalkableY(level, x, z, getY());
                    if (Double.isFinite(y) && Math.abs(y - getY()) <= 1.1) {
                        setPos(x, y, z);
                    } else {
                        // Avoid walking through a wall/cliff; glide the remaining distance.
                        approachFlying = true;
                        fly = true;
                    }
                }
            }
            if (fly) {
                double travel = Math.min(ServerSettings.get().senderFlySpeed(), delta.length());
                if (delta.lengthSqr() > 0.0001) {
                    Vec3 motion = delta.normalize().scale(travel);
                    setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);
                }
            }
            entityData.set(WALKING, !fly);
            facePlayer(target);
            // Keeping no AI/gravity means flight and void are safe; moving position is
            // synchronized to the client so the player-model renderer can animate walking.
            setDeltaMovement(Vec3.ZERO);
            return;
        }

        if (phase == Phase.ARRIVING) {
            // Controlled drop in front of the PLAYER ENTITY; a flying/void recipient has
            // no ground, so stop precisely at the floating handover position instead.
            arrivalTicks++;
            float progress = (float) Math.min(1.0, arrivalTicks / (float) ARRIVAL_DURATION_TICKS);
            double height = arrivalStartHeight * (1.0 - progress * progress);
            Vec3 landing = front(target);
            // The recipient may fly upward during the descent, so clamp every tick.
            setPos(landing.x,
                Math.min(landing.y + height, level.getMaxBuildHeight() - 2.0), landing.z);
            facePlayer(target);
            setDeltaMovement(Vec3.ZERO);
            // Audible descent approach; sound follows the falling entity.
            if (arrivalTicks == 8 || arrivalTicks == 18 || arrivalTicks == 27) {
                playCourierSound(SoundEvents.ELYTRA_FLYING, 1.15F, 1.40F - progress * 0.45F);
            }
            if (progress >= 1.0) {
                phase = Phase.DELIVERING;
                playCourierSound(SoundEvents.PLAYER_BIG_FALL, 1.25F, 0.82F);
                if (CorreioData.get(level.getServer()).firstFor(ownerId) == null) {
                    beginDeparture();
                }
            }
            return;
        }

        if (phase == Phase.DELIVERING && CorreioData.get(level.getServer()).firstFor(ownerId) == null) {
            beginDeparture();
            return;
        }

        // While hovering over the void, follow the entity itself every tick. On ground,
        // DON'T teleport every time the user turns the camera: simply keep looking at
        // the player, and reposition only if they actually move too far away.
        if (isFlyingOrVoid(target) || distanceToSqr(target) > GROUND_FOLLOW_DISTANCE_SQR) {
            Vec3 where = front(target);
            setPos(where.x, where.y, where.z);
        }
        facePlayer(target);
        setDeltaMovement(Vec3.ZERO);

        if (tickCount > 20 * 120 && phase == Phase.SENDING
            && target.containerMenu == target.inventoryMenu) {
            beginDeparture();
        }
    }

    @Override
    protected void onBelowWorld() {
        // Temporary NPC is allowed to follow a recipient below the build floor.
        // It must NEVER be the authoritative container for a parcel.
    }

    /** All normal damage sources are rejected, including void, fire and player attacks. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) { return true; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        if (!activeFor(serverPlayer.getUUID())) {
            if (phase == Phase.APPROACHING && serverPlayer.getUUID().equals(ownerId)) {
                serverPlayer.sendSystemMessage(Component.literal("§eO Carteiro está se aproximando. Aguarde ele parar antes de clicar."));
            } else {
                serverPlayer.sendSystemMessage(Component.literal("§cEste Carteiro não está disponível para você."));
            }
            return InteractionResult.CONSUME;
        }
        if (phase == Phase.SENDING) {
            if (!CorreioPermissions.allowed(serverPlayer, CorreioPermissions.ENVIAR)) {
                serverPlayer.sendSystemMessage(Component.literal("§cVocê não tem permissão para enviar encomendas."));
                return InteractionResult.CONSUME;
            }
            if (receiverId == null || receiverName == null) return InteractionResult.FAIL;
            serverPlayer.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> CorreioMenu.forSender(id, inv, this, receiverId, receiverName),
                Component.literal("Enviar encomenda para " + receiverName)));
        } else if (phase == Phase.DELIVERING) {
            if (!CorreioPermissions.allowed(serverPlayer, CorreioPermissions.RECEBER)) {
                serverPlayer.sendSystemMessage(Component.literal("§cVocê não tem permissão para receber encomendas."));
                return InteractionResult.CONSUME;
            }
            Encomenda parcel = CorreioData.get(serverPlayer.server).firstFor(serverPlayer.getUUID());
            if (parcel == null) { beginDeparture(); return InteractionResult.CONSUME; }
            serverPlayer.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> CorreioMenu.forRecipient(id, inv, this, parcel.id),
                Component.literal("Encomenda de " + parcel.fromName)));
        }
        return InteractionResult.CONSUME;
    }
}
