package fun.rederaven.correio.menu;

import fun.rederaven.correio.RavenCorreio;
import fun.rederaven.correio.server.ServerSettings;
import fun.rederaven.correio.permission.CorreioPermissions;
import fun.rederaven.correio.data.CorreioData;
import fun.rederaven.correio.data.Encomenda;
import fun.rederaven.correio.entity.CarteiroEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 18 parcel slots + 9 control slots; vanilla inventory slots below. Server alone moves parcels. */
public final class CorreioMenu extends AbstractContainerMenu {
    private static final int SEND = 0;
    private static final int RECEIVE = 1;
    private static final int CANCEL_BUTTON = 20;
    // Bottom row: red button on the left (column 2), green button on the right (column 6).
    // Preserve the centered receive button independently of the sender GUI.
    private static final int SEND_BUTTON = 24;
    private static final int RECEIVE_BUTTON = 22;
    private final SimpleContainer mail = new SimpleContainer(27);
    private final SimpleContainerData status = new SimpleContainerData(1);
    private final CarteiroEntity courier; // server-only, never trusted from packets
    private final UUID recipientId;
    private final String recipientName;
    private final UUID parcelId;
    private boolean sealed;

    /** Client factory. Menu type is registered with a 2-arg constructor. */
    public CorreioMenu(int id, Inventory inventory) {
        this(id, inventory, null, null, null, null, SEND);
    }

    public static CorreioMenu forSender(int id, Inventory inventory, CarteiroEntity entity, UUID recipientId, String name) {
        return new CorreioMenu(id, inventory, entity, recipientId, name, null, SEND);
    }

    public static CorreioMenu forRecipient(int id, Inventory inventory, CarteiroEntity entity, UUID parcelId) {
        return new CorreioMenu(id, inventory, entity, null, null, parcelId, RECEIVE);
    }

    private CorreioMenu(int id, Inventory inventory, CarteiroEntity courier, UUID recipientId,
                        String recipientName, UUID parcelId, int mode) {
        super(RavenCorreio.CORREIO_MENU.get(), id);
        this.courier = courier;
        this.recipientId = recipientId;
        this.recipientName = recipientName;
        this.parcelId = parcelId;
        status.set(0, mode);
        addDataSlots(status);

        if (mode == RECEIVE && inventory.player instanceof ServerPlayer player) {
            Encomenda parcel = CorreioData.get(player.server).get(parcelId);
            if (parcel != null && parcel.to.equals(player.getUUID())) {
                for (int i = 0; i < Encomenda.CAPACITY; i++) mail.setItem(i, parcel.items.get(i).copy());
            }
        }
        for (int i = 18; i < 27; i++) {
            boolean cancel = i == CANCEL_BUTTON && mode == SEND;
            boolean action = i == (mode == SEND ? SEND_BUTTON : RECEIVE_BUTTON);
            ItemStack decor = new ItemStack(cancel ? Items.RED_DYE
                : action ? Items.LIME_DYE : Items.GRAY_STAINED_GLASS_PANE);
            decor.set(DataComponents.CUSTOM_NAME, Component.literal(cancel ? "§c§lCANCELAR ENVIO"
                : action ? (mode == SEND ? "§a§lENVIAR" : "§a§lRETIRAR TUDO") : " "));
            mail.setItem(i, decor);
        }
        for (int i = 0; i < 27; i++) {
            final int slotIndex = i;
            addSlot(new Slot(mail, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override
                public boolean mayPlace(ItemStack item) { return slotIndex < 18 && CorreioMenu.this.mode() == SEND; }
                @Override
                public boolean mayPickup(Player player) { return slotIndex < 18 && CorreioMenu.this.mode() == SEND; }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 85 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 143));
    }

    public boolean isReceive() { return mode() == RECEIVE; }
    private int mode() { return status.get(0); }

    @Override
    public boolean stillValid(Player player) {
        return courier == null || (courier.isAlive() && player.distanceToSqr(courier) < 64);
    }

    @Override
    public void clicked(int index, int button, ClickType clickType, Player player) {
        if (index == CANCEL_BUTTON && mode() == SEND) {
            if (player instanceof ServerPlayer serverPlayer && !sealed) cancelSend(serverPlayer);
            return;
        }
        if (index == (mode() == SEND ? SEND_BUTTON : RECEIVE_BUTTON)) {
            if (player instanceof ServerPlayer serverPlayer && !sealed) {
                if (mode() == SEND) confirmSend(serverPlayer);
                else claimAll(serverPlayer);
            }
            return;
        }
        if (index >= 18 && index < 27) return; // decoration
        if (mode() == RECEIVE && index >= 0 && index < Encomenda.CAPACITY) {
            if (player instanceof ServerPlayer serverPlayer) claimSlot(serverPlayer, index);
            return;
        }
        super.clicked(index, button, clickType, player);
    }

    private void confirmSend(ServerPlayer player) {
        if (!CorreioPermissions.allowed(player, CorreioPermissions.ENVIAR)) {
            player.sendSystemMessage(Component.literal("§cVocê não tem permissão para enviar encomendas."));
            player.closeContainer(); // closing the menu returns deposited items
            return;
        }
        if (courier == null || !courier.isSenderFor(player.getUUID()) || recipientId == null) return;
        List<ItemStack> stacks = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < Encomenda.CAPACITY; i++) {
            ItemStack stack = mail.getItem(i).copy();
            stacks.add(stack);
            any |= !stack.isEmpty();
        }
        if (!any) {
            player.sendSystemMessage(Component.literal("§cColoque ao menos um item na encomenda."));
            return;
        }
        CorreioData saved = CorreioData.get(player.server);
        if (saved.pendingFor(recipientId) >= ServerSettings.get().maxPendingParcelsPerPlayer()) {
            player.sendSystemMessage(Component.literal("§cEste jogador já possui encomendas pendentes demais."));
            return;
        }
        if (!ServerSettings.get().allowSendToSelf() && recipientId.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cEnvio para si mesmo está desativado."));
            return;
        }
        // Fail closed for modded items with unserializable components: never remove
        // player items unless their complete ItemStack survives an NBT round trip.
        try {
            Encomenda.validatePersistentStacks(stacks, player.serverLevel().registryAccess());
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal("§cEste envio contém um item ou componente "
                + "que não pode ser salvo sem perda. Itens mantidos na GUI. Veja o log do servidor."));
            org.slf4j.LoggerFactory.getLogger("RavenCorreio")
                .error("Encomenda recusada por dados de item nao persistiveis", exception);
            return;
        }
        Encomenda parcel = new Encomenda(UUID.randomUUID(), player.getUUID(),
            player.getGameProfile().getName(), recipientId, recipientName, System.currentTimeMillis(), stacks);
        // Save the parcel before removing items from the GUI. Never reuse its mutable stacks.
        saved.add(parcel);
        sealed = true;
        for (int i = 0; i < Encomenda.CAPACITY; i++) mail.setItem(i, ItemStack.EMPTY);
        player.sendSystemMessage(Component.literal("§aEncomenda enviada para §f" + recipientName + "§a!"));
        player.closeContainer();
        courier.beginDeparture();
    }

    private void claimAll(ServerPlayer player) {
        for (int i = 0; i < Encomenda.CAPACITY; i++) claimSlot(player, i);
    }

    private void claimSlot(ServerPlayer player, int slot) {
        if (!CorreioPermissions.allowed(player, CorreioPermissions.RECEBER)) {
            player.sendSystemMessage(Component.literal("§cVocê não tem permissão para receber encomendas."));
            player.closeContainer();
            return;
        }
        if (courier == null || !courier.isDeliveryFor(player.getUUID()) || parcelId == null) return;
        CorreioData data = CorreioData.get(player.server);
        Encomenda parcel = data.get(parcelId);
        if (parcel == null || !parcel.to.equals(player.getUUID())) {
            player.closeContainer();
            return;
        }
        ItemStack stack = parcel.items.get(slot);
        if (stack.isEmpty()) return;
        if (!canInsertFully(player.getInventory(), stack)) {
            player.sendSystemMessage(Component.literal("§cInventário cheio. Abra espaço e clique novamente."));
            return;
        }
        ItemStack toInsert = stack.copy();
        // Claimed slot is removed from saved parcel before handing over to player inventory.
        parcel.items.set(slot, ItemStack.EMPTY);
        data.modified(parcel);
        mail.setItem(slot, ItemStack.EMPTY);
        player.getInventory().add(toInsert);
        if (!toInsert.isEmpty()) player.drop(toInsert, false); // failsafe against inventory modifications
        broadcastChanges();
        if (parcel.empty()) {
            sealed = true;
            player.sendSystemMessage(Component.literal("§aVocê retirou toda a encomenda!"));
            player.closeContainer();
            courier.beginDeparture();
        }
    }

    /** Simulate vanilla main inventory capacity; never consume even one item if it cannot fit fully. */
    private boolean canInsertFully(Inventory inv, ItemStack input) {
        int remaining = input.getCount();
        for (int i = 0; i < 36; i++) {
            ItemStack existing = inv.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, input)) {
                remaining -= Math.max(0, Math.min(existing.getMaxStackSize(), inv.getMaxStackSize()) - existing.getCount());
            }
        }
        if (remaining <= 0) return true;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) remaining -= Math.min(input.getMaxStackSize(), inv.getMaxStackSize());
            if (remaining <= 0) return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (mode() == RECEIVE || index < 0 || index >= slots.size() || index >= 18 && index < 27) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack original = source.getItem();
        ItemStack copy = original.copy();
        boolean moved = index < 18 ? moveItemStackTo(original, 27, 63, true)
            : moveItemStackTo(original, 0, 18, false);
        if (!moved) return ItemStack.EMPTY;
        if (original.isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        return copy;
    }

    private void refundSenderItems(ServerPlayer player) {
        if (sealed) return;
        sealed = true; // prevents double refunds if close and cancel happen in the same tick
        for (int i = 0; i < Encomenda.CAPACITY; i++) {
            ItemStack stack = mail.removeItemNoUpdate(i);
            if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack);
        }
    }

    private void cancelSend(ServerPlayer player) {
        if (mode() != SEND || sealed) return;
        refundSenderItems(player);
        player.sendSystemMessage(Component.literal("§eEnvio cancelado. Itens devolvidos ao inventário."));
        if (courier != null && courier.isSenderFor(player.getUUID())) courier.beginDeparture();
        player.closeContainer();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer && mode() == SEND && !sealed) {
            // ESC / disconnect is also safe cancellation. Items are only refunded once.
            refundSenderItems(serverPlayer);
            if (courier != null && courier.isSenderFor(player.getUUID())) courier.beginDeparture();
        }
    }
}
