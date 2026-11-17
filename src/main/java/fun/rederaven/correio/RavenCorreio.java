package fun.rederaven.correio;

import fun.rederaven.correio.entity.CarteiroEntity;
import fun.rederaven.correio.menu.CorreioMenu;
import fun.rederaven.correio.server.CorreioServer;
import fun.rederaven.correio.permission.CorreioPermissions;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(RavenCorreio.MOD_ID)
public final class RavenCorreio {
    public static final String MOD_ID = "ravencorreio";

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final Supplier<EntityType<CarteiroEntity>> CARTEIRO = ENTITIES.register("carteiro",
        () -> EntityType.Builder.<CarteiroEntity>of(CarteiroEntity::new, MobCategory.MISC)
            .sized(0.6F, 1.8F).clientTrackingRange(12).updateInterval(2).build("ravencorreio:carteiro"));

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final Supplier<MenuType<CorreioMenu>> CORREIO_MENU = MENUS.register("correio",
        () -> new MenuType<>(CorreioMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public RavenCorreio(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(this::registerAttributes);
        NeoForge.EVENT_BUS.register(new CorreioServer());
        NeoForge.EVENT_BUS.addListener(CorreioPermissions::registerNodes);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        AttributeSupplier attributes = net.minecraft.world.entity.Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.27)
            .add(Attributes.FOLLOW_RANGE, 32.0)
            .build();
        event.put(CARTEIRO.get(), attributes);
    }
}
