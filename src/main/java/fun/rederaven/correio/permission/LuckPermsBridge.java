package fun.rederaven.correio.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional LuckPerms API dependency. This class is only reached if LuckPerms is loaded. */
final class LuckPermsBridge {
    private static final Logger LOG = LoggerFactory.getLogger("RavenCorreio/Permissions");
    private static boolean warned;

    private LuckPermsBridge() {}

    static boolean allowed(ServerPlayer player, String node, boolean publicDefault) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUUID());
            if (user == null) {
                warnOnce("LuckPerms ainda nao carregou o usuario: " + player.getGameProfile().getName(), null);
                return false;
            }
            Tristate result = user.getCachedData().getPermissionData().checkPermission(node);
            if (result == Tristate.TRUE) return true;
            if (result == Tristate.FALSE) return false;
            // UNDEFINED: retain previous mod behavior unless explicitly granted/denied.
            return publicDefault || player.hasPermissions(2);
        } catch (RuntimeException | LinkageError e) {
            warnOnce("LuckPerms indisponivel; negando permissão para evitar bypass", e);
            return false;
        }
    }

    private static void warnOnce(String message, Throwable cause) {
        if (warned) return;
        warned = true;
        if (cause == null) LOG.warn(message);
        else LOG.warn(message, cause);
    }
}
