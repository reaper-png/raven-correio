package fun.rederaven.correio.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable identity; contents are mutable only on the logical server's main thread. */
public final class Encomenda {
    public static final int CAPACITY = 18;
    public final UUID id;
    public final UUID from;
    public final String fromName;
    public final UUID to;
    public final String toName;
    public final long createdAt;
    public final List<ItemStack> items;

    public Encomenda(UUID id, UUID from, String fromName, UUID to, String toName, long createdAt, List<ItemStack> source) {
        this.id = id;
        this.from = from;
        this.fromName = fromName;
        this.to = to;
        this.toName = toName;
        this.createdAt = createdAt;
        this.items = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            this.items.add(i < source.size() ? source.get(i).copy() : ItemStack.EMPTY);
        }
    }

    public boolean empty() { return items.stream().allMatch(ItemStack::isEmpty); }

    /** Verify data-component/NBT persistence before taking ownership of any items.
     *  1.21.1 stores enchantments, name, lore, durability and mod data as components.
     */
    public static void validatePersistentStacks(List<ItemStack> stacks, HolderLookup.Provider lookup) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack original = stacks.get(i);
            if (original.isEmpty()) continue;
            Tag encoded;
            try {
                encoded = original.save(lookup);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Falha ao serializar item do slot " + i, exception);
            }
            ItemStack decoded = ItemStack.parse(lookup, encoded).orElse(null);
            if (decoded == null) throw new IllegalStateException("Item do slot " + i + " nao pode ser desserializado");
            if (original.getCount() != decoded.getCount()
                || !ItemStack.isSameItemSameComponents(original, decoded)) {
                throw new IllegalStateException("Item ou componentes alterados no round-trip do slot " + i);
            }
        }
    }

    public CompoundTag save(HolderLookup.Provider lookup) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("From", from);
        tag.putString("FromName", fromName);
        tag.putUUID("To", to);
        tag.putString("ToName", toName);
        tag.putLong("CreatedAt", createdAt);
        ListTag content = new ListTag();
        for (int slot = 0; slot < CAPACITY; slot++) {
            ItemStack item = items.get(slot);
            if (item.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            entry.put("Stack", item.save(lookup));
            content.add(entry);
        }
        tag.put("Items", content);
        return tag;
    }

    public static Encomenda load(CompoundTag tag, HolderLookup.Provider lookup) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) stacks.add(ItemStack.EMPTY);
        ListTag content = tag.getList("Items", Tag.TAG_COMPOUND);
        boolean[] seen = new boolean[CAPACITY];
        for (int i = 0; i < content.size(); i++) {
            CompoundTag entry = content.getCompound(i);
            int slot = entry.getByte("Slot") & 255;
            if (slot >= CAPACITY || seen[slot]) {
                throw new IllegalStateException("Slot duplicado/invalido na encomenda "
                    + tag.getUUID("Id") + ": " + slot);
            }
            seen[slot] = true;
            ItemStack decoded = ItemStack.parse(lookup, entry.get("Stack")).orElse(null);
            if (decoded == null || decoded.isEmpty()) {
                throw new IllegalStateException("Item da encomenda " + tag.getUUID("Id")
                    + " no slot " + slot + " não pôde ser recuperado; confira os mods instalados.");
            }
            stacks.set(slot, decoded);
        }
        return new Encomenda(tag.getUUID("Id"), tag.getUUID("From"), tag.getString("FromName"),
            tag.getUUID("To"), tag.getString("ToName"), tag.getLong("CreatedAt"), stacks);
    }
}
