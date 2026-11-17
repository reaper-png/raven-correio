package fun.rederaven.correio.permission;

import fun.rederaven.correio.RavenCorreio;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.function.Predicate;

/**
 * Permission nodes for Raven Correio (server authoritative).
 * LuckPerms, when installed, is queried directly so it works regardless of
 * the NeoForge permissionHandler selected in neoforge-server.toml.
 */
public final class CorreioPermissions {
    private CorreioPermissions() {}

    // Public commands: available by default. LuckPerms explicit FALSE denies access.
    public static final PermissionNode<Boolean> ENVIAR = node("enviar", true);
    public static final PermissionNode<Boolean> PENDENTES = node("pendentes", true);
    public static final PermissionNode<Boolean> RECEBER = node("receber", true);

    // Administrative commands: OP level 2 by default; LuckPerms TRUE allows non-OP.
    public static final PermissionNode<Boolean> RELOAD = node("reload", false);
    public static final PermissionNode<Boolean> SKIN_VER = node("skin.ver", false);
    public static final PermissionNode<Boolean> SKIN_NICK = node("skin.nick", false);
    public static final PermissionNode<Boolean> SKIN_URL = node("skin.url", false);
    public static final PermissionNode<Boolean> SKIN_URLSLIM = node("skin.urlslim", false);

    private static PermissionNode<Boolean> node(String suffix, boolean publicByDefault) {
        PermissionNode<Boolean> node = new PermissionNode<>(RavenCorreio.MOD_ID, "command." + suffix,
            PermissionTypes.BOOLEAN,
            (player, uuid, context) -> publicByDefault || (player != null && player.hasPermissions(2)));
        node.setInformation(Component.literal("Raven Correio: " + suffix),
            Component.literal("Permissão para /correio " + suffix.replace('.', ' ')));
        return node;
    }

    /** Registered in NeoForge's server event bus, not the mod lifecycle bus. */
    public static void registerNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(ENVIAR, PENDENTES, RECEBER, RELOAD,
            SKIN_VER, SKIN_NICK, SKIN_URL, SKIN_URLSLIM);
    }

    /** Used by Brigadier .requires() to hide denied subcommands and reject their execution. */
    public static Predicate<CommandSourceStack> requires(PermissionNode<Boolean> node) {
        return source -> allowed(source, node);
    }

    public static boolean allowed(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source.getEntity() instanceof ServerPlayer player) return allowed(player, node);
        // Console and command blocks may only execute admin operations.
        return !isPublic(node) && source.hasPermission(4);
    }

    public static boolean allowed(ServerPlayer player, PermissionNode<Boolean> node) {
        if (ModList.get().isLoaded("luckperms")) {
            // No LuckPerms dependency is linked until the mod is actually installed.
            // Fail closed if LP is present but not available, avoiding bypasses of FALSE.
            return LuckPermsBridge.allowed(player, node.getNodeName(), isPublic(node));
        }
        // Without LuckPerms, use the registered NeoForge permission node.
        // Allows other NeoForge permission handlers to provide grants too.
        return PermissionAPI.getPermission(player, node);
    }

    private static boolean isPublic(PermissionNode<Boolean> node) {
        return node == ENVIAR || node == PENDENTES || node == RECEBER;
    }
}
