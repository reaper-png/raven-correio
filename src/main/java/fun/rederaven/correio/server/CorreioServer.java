package fun.rederaven.correio.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.rederaven.correio.RavenCorreio;
import fun.rederaven.correio.data.CorreioData;
import fun.rederaven.correio.entity.CarteiroEntity;
import fun.rederaven.correio.permission.CorreioPermissions;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelResource;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authoritative server commands, NPC skin state and delivery retries. */
public final class CorreioServer {
    private static final Logger LOG = LoggerFactory.getLogger("RavenCorreio");
    /** 2 seconds from chat notice to falling courier appearing. Server-thread only. */
    private static final int ARRIVAL_NOTICE_TICKS = 40;
    private final Map<UUID, Integer> arrivalQueue = new HashMap<>();
    /** Active deliveries are tracked by recipient UUID, not by entity proximity.
     * A falling courier can legitimately be hundreds of blocks above the player. */
    private final Map<UUID, CarteiroEntity> activeDeliveries = new HashMap<>();

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        arrivalQueue.clear();
        activeDeliveries.clear();
        try {
            ServerSettings.reload(event.getServer());
        } catch (IOException exception) {
            LOG.error("Nao foi possivel carregar configuracao; usando configuracao atual/padrao", exception);
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("correio")
            .then(Commands.literal("enviar")
                .requires(CorreioPermissions.requires(CorreioPermissions.ENVIAR))
                .then(Commands.argument("jogador", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayer sender = context.getSource().getPlayerOrException();
                        String nickname = StringArgumentType.getString(context, "jogador");
                        requestSend(sender, nickname);
                        return 1;
                    })))
            .then(Commands.literal("pendentes")
                .requires(CorreioPermissions.requires(CorreioPermissions.PENDENTES)).executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                int count = CorreioData.get(player.server).pendingFor(player.getUUID());
                player.sendSystemMessage(Component.literal("§bEncomendas aguardando: §f" + count));
                return 1;
            }))
            .then(Commands.literal("receber")
                .requires(CorreioPermissions.requires(CorreioPermissions.RECEBER)).executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                if (CorreioData.get(player.server).pendingFor(player.getUUID()) == 0) {
                    player.sendSystemMessage(Component.literal("§7Você não tem encomendas pendentes."));
                } else {
                    spawnRecipient(player, true);
                }
                return 1;
            }))
            .then(Commands.literal("reload")
                .requires(CorreioPermissions.requires(CorreioPermissions.RELOAD))
                .executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    try {
                        ServerSettings.reload(server);
                        updateVisibleCouriers(server);
                        context.getSource().sendSuccess(() -> Component.literal(
                            "§aRaven Correio recarregado! §7Config: " + ServerSettings.path(server)), true);
                        return 1;
                    } catch (IOException exception) {
                        LOG.warn("Falha no /correio reload", exception);
                        context.getSource().sendFailure(Component.literal("§cReload cancelado: " + exception.getMessage()));
                        return 0;
                    }
                }))
            .then(Commands.literal("skin")
                .then(Commands.literal("ver")
                    .requires(CorreioPermissions.requires(CorreioPermissions.SKIN_VER)).executes(context -> {
                    ServerSettings.Settings s = ServerSettings.get();
                    context.getSource().sendSuccess(() -> Component.literal(
                        "§bCarteiro: §f" + s.skinType() + " = " + s.skinValue()
                            + (s.skinSlim() ? " §7(slim)" : "")), false);
                    return 1;
                }))
                .then(Commands.literal("nick")
                    .requires(CorreioPermissions.requires(CorreioPermissions.SKIN_NICK))
                    .then(Commands.argument("nickname", StringArgumentType.word()).executes(context ->
                        setSkin(context.getSource().getServer(), context.getSource(), "nickname",
                            StringArgumentType.getString(context, "nickname"), false))))
                .then(Commands.literal("url")
                    .requires(CorreioPermissions.requires(CorreioPermissions.SKIN_URL))
                    .then(Commands.argument("link", StringArgumentType.greedyString()).executes(context ->
                        setSkin(context.getSource().getServer(), context.getSource(), "url",
                            StringArgumentType.getString(context, "link"), false))))
                .then(Commands.literal("urlslim")
                    .requires(CorreioPermissions.requires(CorreioPermissions.SKIN_URLSLIM))
                    .then(Commands.argument("link", StringArgumentType.greedyString()).executes(context ->
                        setSkin(context.getSource().getServer(), context.getSource(), "url",
                            StringArgumentType.getString(context, "link"), true))))
            )
        );
    }

    private static int setSkin(MinecraftServer server, net.minecraft.commands.CommandSourceStack source,
                               String type, String value, boolean slim) {
        try {
            ServerSettings.setSkin(server, type, value, slim);
            updateVisibleCouriers(server);
            source.sendSuccess(() -> Component.literal("§aSkin do Carteiro atualizada! §7" + type
                + " = " + value + " §8(sem reiniciar)"), true);
            return 1;
        } catch (IllegalArgumentException | IOException exception) {
            source.sendFailure(Component.literal("§cNão foi possível alterar a skin: " + exception.getMessage()));
            return 0;
        }
    }

    private static void requestSend(ServerPlayer sender, String name) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            sender.sendSystemMessage(Component.literal("§cNickname inválido."));
            return;
        }
        MinecraftServer server = sender.server;
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            spawnSender(sender, online.getUUID(), online.getGameProfile().getName());
            return;
        }
        // A profile cache lookup alone is not proof that the player belongs to this
        // server (offline mode may even synthesize profiles from arbitrary nicknames).
        sender.sendSystemMessage(Component.literal("§7Verificando se " + name + " já entrou no servidor..."));
        server.getProfileCache().getAsync(name).whenComplete((result, error) -> server.execute(() -> {
            ServerPlayer currentSender = server.getPlayerList().getPlayer(sender.getUUID());
            if (currentSender == null) return;
            if (error != null || result == null || result.isEmpty()) {
                currentSender.sendSystemMessage(Component.literal("§cJogador não encontrado: " + name));
                if (error != null) LOG.warn("Nao foi possivel consultar perfil de {}", name, error);
                return;
            }
            GameProfile profile = result.get();
            if (profile.getId() == null || profile.getName() == null
                || !profile.getName().equalsIgnoreCase(name)) {
                currentSender.sendSystemMessage(Component.literal("§cNickname não encontrado: " + name));
                return;
            }
            // Only allow recipients who are online OR have persisted player data.
            // A valid premium nickname that never joined this server is not an account
            // here and must not receive a phantom mailbox.
            if (!hasPlayedOnServer(server, profile.getId())) {
                currentSender.sendSystemMessage(Component.literal(
                    "§cO jogador §f" + name + " §cnunca entrou neste servidor. Envio cancelado."));
                return;
            }
            spawnSender(currentSender, profile.getId(), profile.getName());
        }));
    }

    /** Server-local evidence of a previous login, independent of external APIs. */
    private static boolean hasPlayedOnServer(MinecraftServer server, UUID playerId) {
        if (playerId == null) return false;
        if (server.getPlayerList().getPlayer(playerId) != null) return true;
        Path dataFile = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
            .resolve(playerId + ".dat");
        return Files.isRegularFile(dataFile);
    }

    private static void spawnSender(ServerPlayer player, UUID receiver, String receiverName) {
        // Nickname resolution is asynchronous: permissions may change in the meantime.
        if (!CorreioPermissions.allowed(player, CorreioPermissions.ENVIAR)) {
            player.sendSystemMessage(Component.literal("§cVocê não tem permissão para enviar encomendas."));
            return;
        }
        if (!ServerSettings.get().allowSendToSelf() && receiver.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cEnvio para si mesmo desativado no config."));
            return;
        }
        if (CorreioData.get(player.server).pendingFor(receiver) >= ServerSettings.get().maxPendingParcelsPerPlayer()) {
            player.sendSystemMessage(Component.literal("§cA caixa postal de " + receiverName + " está cheia."));
            return;
        }
        if (find(player, false) != null) {
            player.sendSystemMessage(Component.literal("§eSeu Carteiro já foi chamado. Aguarde ele chegar ou clique nele quando parar."));
            return;
        }
        CarteiroEntity npc = RavenCorreio.CARTEIRO.get().create(player.serverLevel());
        if (npc == null) return;
        npc.setupSender(player, receiver, receiverName);
        applySkin(player.server, npc);
        if (player.serverLevel().addFreshEntity(npc)) {
            player.sendSystemMessage(Component.literal("§6📦 O Carteiro apareceu a §e"
                + (int) ServerSettings.get().senderSpawnDistance()
                + " blocos§6 e está vindo até você. Aguarde ele chegar."));
        } else {
            player.sendSystemMessage(Component.literal("§cNão foi possível chamar o Carteiro nesta área."));
        }
    }

    private static CarteiroEntity find(ServerPlayer player, boolean deliveryOnly) {
        // Use a tall search volume: the delivery NPC can start 300 blocks above the player.
        // Horizontal search stays limited so this query remains inexpensive.
        return player.serverLevel().getEntitiesOfClass(CarteiroEntity.class,
            player.getBoundingBox().inflate(64.0, 384.0, 64.0),
            npc -> deliveryOnly ? npc.isDeliveryFor(player.getUUID())
                : npc.isSenderFor(player.getUUID())).stream().findFirst().orElse(null);
    }

    private CarteiroEntity activeDeliveryFor(ServerPlayer recipient) {
        UUID id = recipient.getUUID();
        CarteiroEntity existing = activeDeliveries.get(id);
        if (existing != null) {
            // This reference remains valid even when the NPC is 300 blocks above the player.
            if (!existing.isRemoved() && existing.isAlive()
                    && existing.level() == recipient.serverLevel() && existing.isDeliveryFor(id)) {
                return existing;
            }
            activeDeliveries.remove(id);
        }
        // Reconcile in case this mod was reloaded or the courier wasn't in the index yet.
        existing = find(recipient, true);
        if (existing != null) activeDeliveries.put(id, existing);
        return existing;
    }

    /** Notify before spawning, including /correio receber and automatic online deliveries. */
    private void spawnRecipient(ServerPlayer recipient, boolean force) {
        if (!CorreioPermissions.allowed(recipient, CorreioPermissions.RECEBER)) return;
        if (CorreioData.get(recipient.server).firstFor(recipient.getUUID()) == null) return;
        if (activeDeliveryFor(recipient) != null) {
            if (force) recipient.sendSystemMessage(Component.literal("§eSeu Carteiro já está chegando ou aguardando você."));
            return;
        }
        if (arrivalQueue.containsKey(recipient.getUUID())) {
            if (force) recipient.sendSystemMessage(Component.literal("§eO Carteiro já está a caminho. Aguarde um instante."));
            return;
        }
        arrivalQueue.put(recipient.getUUID(), recipient.server.getTickCount() + ARRIVAL_NOTICE_TICKS);
        recipient.sendSystemMessage(Component.literal(
            "§6📦 Você recebeu uma encomenda! §eO Carteiro está chegando. §6Aguarde a queda e clique nele para coletar."));
    }

    private void spawnRecipientNow(ServerPlayer recipient) {
        if (!CorreioPermissions.allowed(recipient, CorreioPermissions.RECEBER)) return;
        if (CorreioData.get(recipient.server).firstFor(recipient.getUUID()) == null) return;
        if (activeDeliveryFor(recipient) != null) return;
        CarteiroEntity npc = RavenCorreio.CARTEIRO.get().create(recipient.serverLevel());
        if (npc == null) return;
        npc.setupRecipient(recipient); // ARRIVING: height is controlled by CarteiroEntity.
        applySkin(recipient.server, npc);
        // Make the descent audible immediately, while the NPC is still high above.
        recipient.serverLevel().playSound(null, recipient.getX(), recipient.getY() + 4.0,
            recipient.getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.85F, 1.3F);
        // Register only if the entity really entered the world.
        if (recipient.serverLevel().addFreshEntity(npc)) {
            activeDeliveries.put(recipient.getUUID(), npc);
        } else {
            LOG.warn("Nao foi possivel criar Carteiro de entrega para {}", recipient.getGameProfile().getName());
        }
    }

    /** Apply server settings to an NPC. URL and slim flag are synced through SynchedEntityData. */
    private static void applySkin(MinecraftServer server, CarteiroEntity npc) {
        ServerSettings.Settings skin = ServerSettings.get();
        int version = ServerSettings.revision();
        if ("url".equals(skin.skinType())) {
            npc.setSkinUrl(skin.skinValue(), skin.skinSlim(), version);
            return;
        }
        String name = skin.skinValue();
        npc.setSkin(name, null, version);
        server.getProfileCache().getAsync(name).thenAccept(result -> server.execute(() -> {
            if (!npc.isRemoved() && result.isPresent() && npc.skinName().equals(name)
                && npc.skinRevision() == version)
                npc.setSkin(name, result.get().getId(), version);
        }));
    }

    /** Refresh existing mailmen instantly after a skin command or reload. */
    private static void updateVisibleCouriers(MinecraftServer server) {
        Set<UUID> updated = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (CarteiroEntity npc : player.serverLevel().getEntitiesOfClass(CarteiroEntity.class,
                player.getBoundingBox().inflate(128))) {
                if (updated.add(npc.getUUID())) applySkin(server, npc);
            }
        }
    }

    @SubscribeEvent
    public void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // Only create the entity after the prior chat notice has been displayed.
        Iterator<Map.Entry<UUID, Integer>> iterator = arrivalQueue.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> pending = iterator.next();
            if (server.getTickCount() < pending.getValue()) continue;
            iterator.remove();
            ServerPlayer recipient = server.getPlayerList().getPlayer(pending.getKey());
            if (recipient != null && !recipient.isRemoved() && recipient.isAlive()) {
                spawnRecipientNow(recipient);
            }
        }
        if (server.getTickCount() % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isRemoved() || !player.isAlive()) continue;
            if (CorreioData.get(server).pendingFor(player.getUUID()) == 0) continue;
            spawnRecipient(player, false);
        }
    }
}
