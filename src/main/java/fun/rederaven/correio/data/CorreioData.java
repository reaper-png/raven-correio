package fun.rederaven.correio.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Stored in Overworld SavedData so parcels survive logout, dimensions, and restarts. */
public final class CorreioData extends SavedData {
    private static final String FILE = "ravencorreio_parcels";
    private final Map<UUID, Encomenda> parcels = new LinkedHashMap<>();

    public static CorreioData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(CorreioData::new, CorreioData::load), FILE);
    }

    public static CorreioData load(CompoundTag tag, HolderLookup.Provider lookup) {
        CorreioData data = new CorreioData();
        ListTag list = tag.getList("Parcels", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Encomenda parcel = Encomenda.load(list.getCompound(i), lookup);
                if (data.parcels.containsKey(parcel.id)) {
                    throw new IllegalStateException("ID de encomenda duplicado: " + parcel.id);
                }
                if (!parcel.empty()) data.parcels.put(parcel.id, parcel);
            } catch (RuntimeException ex) {
                // Never silently drop a parcel and later save over it. Stop loading so
                // an administrator can restore the missing mod or repair the data.
                throw new IllegalStateException("Encomenda " + i
                    + " não pôde ser carregada. Não inicie o mundo sem recuperar os mods/dados.", ex);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (Encomenda parcel : parcels.values()) list.add(parcel.save(lookup));
        tag.put("Parcels", list);
        return tag;
    }

    public void add(Encomenda parcel) {
        if (parcels.containsKey(parcel.id)) throw new IllegalStateException("Duplicate parcel id");
        parcels.put(parcel.id, parcel);
        setDirty();
    }
    public Encomenda get(UUID id) { return parcels.get(id); }
    public Encomenda firstFor(UUID uuid) {
        return parcels.values().stream().filter(p -> p.to.equals(uuid)).findFirst().orElse(null);
    }
    public int pendingFor(UUID uuid) {
        return (int) parcels.values().stream().filter(p -> p.to.equals(uuid)).count();
    }
    public void modified(Encomenda parcel) {
        if (parcel.empty()) parcels.remove(parcel.id);
        setDirty();
    }
    public Collection<Encomenda> all() { return java.util.Collections.unmodifiableCollection(parcels.values()); }
}
