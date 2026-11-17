package fun.rederaven.correio.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-owned configuration: NEVER loaded from the physical client's config folder.
 * Saved per world in world/serverconfig/ravencorreio.json and hot-reloaded via command.
 * Only the selected skin parameters are synchronized through CarteiroEntity data.
 */
public final class ServerSettings {
    private static final Logger LOG = LoggerFactory.getLogger("RavenCorreio");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");
    // Motion v2 migrates v0.3.3 defaults just once, without rewriting customized speeds.
    private static final int SENDER_MOTION_VERSION = 2;
    private static final double OLD_DEFAULT_WALK = 0.48;
    private static final double OLD_DEFAULT_FLY = 0.85;
    private static final double DEFAULT_WALK = 0.16;
    private static final double DEFAULT_FLY = 0.24;
    private static final List<String> DEFAULT_HOSTS = List.of(
        "textures.minecraft.net", "minotar.net", "mc-heads.net", "i.imgur.com"
    );

    public record Settings(String skinType, String skinValue, boolean skinSlim,
                           boolean allowSendToSelf, int maxPendingParcelsPerPlayer,
                           List<String> allowedSkinHosts, double senderSpawnDistance,
                           double senderWalkSpeed, double senderFlySpeed) {
        public Settings {
            allowedSkinHosts = List.copyOf(allowedSkinHosts);
        }
        public Settings withSkin(String type, String value, boolean slim) {
            return new Settings(type, value, slim, allowSendToSelf, maxPendingParcelsPerPlayer,
                allowedSkinHosts, senderSpawnDistance, senderWalkSpeed, senderFlySpeed);
        }
    }

    private static volatile Settings current = defaults();
    private static int revision;
    private ServerSettings() {}

    public static Settings get() { return current; }
    public static int revision() { return revision; }

    private static Settings defaults() {
        return new Settings("nickname", "Notch", false, true, 15, DEFAULT_HOSTS,
            50.0, DEFAULT_WALK, DEFAULT_FLY);
    }

    public static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("ravencorreio.json");
    }

    public static synchronized Settings reload(MinecraftServer server) throws IOException {
        Path file = path(server);
        if (Files.notExists(file)) {
            write(file, defaults());
        }
        // Always parse and validate the complete file BEFORE replacing the live settings.
        JsonElement raw;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            raw = JsonParser.parseReader(reader);
        } catch (RuntimeException exception) {
            throw new IOException("JSON invalido: " + exception.getMessage(), exception);
        }
        if (!raw.isJsonObject()) throw new IOException("A raiz da configuracao precisa ser um objeto JSON.");
        Settings parsed;
        boolean migrateMotion;
        try {
            JsonObject obj = raw.getAsJsonObject();
            JsonObject skin = obj.getAsJsonObject("skin");
            if (skin == null) throw new IllegalArgumentException("Campo 'skin' ausente");
            String type = skin.get("type").getAsString();
            String value = skin.get("value").getAsString();
            boolean slim = skin.has("slim") && skin.get("slim").getAsBoolean();
            boolean self = obj.get("allowSendToSelf").getAsBoolean();
            int max = obj.get("maxPendingParcelsPerPlayer").getAsInt();
            JsonArray jsonHosts = obj.getAsJsonArray("allowedSkinHosts");
            List<String> hosts = new ArrayList<>();
            if (jsonHosts == null) hosts.addAll(DEFAULT_HOSTS);
            else for (JsonElement element : jsonHosts) hosts.add(element.getAsString().toLowerCase(Locale.ROOT));
            double walk = obj.has("senderWalkSpeed")
                ? obj.get("senderWalkSpeed").getAsDouble() : DEFAULT_WALK;
            double fly = obj.has("senderFlySpeed")
                ? obj.get("senderFlySpeed").getAsDouble() : DEFAULT_FLY;
            // The user's existing v0.3.3 JSON explicitly contains the old defaults;
            // without migration, changing Java defaults would have no visible effect.
            migrateMotion = !obj.has("senderMotionVersion")
                || obj.get("senderMotionVersion").getAsInt() < SENDER_MOTION_VERSION;
            if (migrateMotion && Double.compare(walk, OLD_DEFAULT_WALK) == 0
                && Double.compare(fly, OLD_DEFAULT_FLY) == 0) {
                walk = DEFAULT_WALK;
                fly = DEFAULT_FLY;
            }
            parsed = new Settings(type, value, slim, self, max, hosts,
                obj.has("senderSpawnDistance") ? obj.get("senderSpawnDistance").getAsDouble() : 50.0,
                walk, fly);
            validate(parsed);
        } catch (RuntimeException exception) {
            throw new IOException("Configuracao invalida: " + exception.getMessage(), exception);
        }
        // Persist the migrated speeds to the server's JSON. On later reloads,
        // intentional administrator changes to these values are respected.
        if (migrateMotion) write(file, parsed);
        current = parsed;
        revision++;
        LOG.info("Configuracao do RavenCorreio carregada de {}", file);
        return current;
    }

    public static synchronized void setSkin(MinecraftServer server, String type, String value, boolean slim) throws IOException {
        Settings next = current.withSkin(type, value, slim);
        validate(next);
        write(path(server), next);
        current = next;
        revision++;
    }

    private static void validate(Settings settings) {
        if (settings.maxPendingParcelsPerPlayer() < 1 || settings.maxPendingParcelsPerPlayer() > 100)
            throw new IllegalArgumentException("maxPendingParcelsPerPlayer deve ficar entre 1 e 100");
        if (!Double.isFinite(settings.senderSpawnDistance()) || settings.senderSpawnDistance() < 10.0
            || settings.senderSpawnDistance() > 128.0)
            throw new IllegalArgumentException("senderSpawnDistance deve ficar entre 10 e 128 blocos");
        if (!Double.isFinite(settings.senderWalkSpeed()) || settings.senderWalkSpeed() < 0.05
            || settings.senderWalkSpeed() > 1.0)
            throw new IllegalArgumentException("senderWalkSpeed deve ficar entre 0.05 e 1.0");
        if (!Double.isFinite(settings.senderFlySpeed()) || settings.senderFlySpeed() < 0.05
            || settings.senderFlySpeed() > 2.0)
            throw new IllegalArgumentException("senderFlySpeed deve ficar entre 0.05 e 2.0");
        if (settings.allowedSkinHosts().isEmpty()) throw new IllegalArgumentException("allowedSkinHosts nao pode ficar vazio");
        for (String host : settings.allowedSkinHosts()) {
            if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("Host invalido: " + host);
        }
        switch (settings.skinType()) {
            case "nickname" -> {
                if (!NICK.matcher(settings.skinValue()).matches())
                    throw new IllegalArgumentException("Nickname de skin invalido (3 a 16 caracteres)");
            }
            case "url" -> validateUrl(settings.skinValue(), settings.allowedSkinHosts());
            default -> throw new IllegalArgumentException("skin.type deve ser 'nickname' ou 'url'");
        }
    }

    public static void validateUrl(String value, List<String> allowedHosts) {
        try {
            if (value.length() > 512 || value.contains("\\") || value.contains(" "))
                throw new IllegalArgumentException("Link muito longo ou contem caracteres invalidos");
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Use URL HTTPS direta, sem porta, usuario ou fragmento");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowedHosts.contains(host))
                throw new IllegalArgumentException("Host nao autorizado: " + host
                    + " (adicione em allowedSkinHosts no servidor e use /correio reload)");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("URL da skin invalida: " + ex.getMessage(), ex);
        }
    }

    private static void write(Path target, Settings settings) throws IOException {
        Files.createDirectories(target.getParent());
        JsonObject root = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("type", settings.skinType());
        skin.addProperty("value", settings.skinValue());
        skin.addProperty("slim", settings.skinSlim());
        root.add("skin", skin);
        root.addProperty("allowSendToSelf", settings.allowSendToSelf());
        root.addProperty("maxPendingParcelsPerPlayer", settings.maxPendingParcelsPerPlayer());
        root.addProperty("senderMotionVersion", SENDER_MOTION_VERSION);
        root.addProperty("senderSpawnDistance", settings.senderSpawnDistance());
        root.addProperty("senderWalkSpeed", settings.senderWalkSpeed());
        root.addProperty("senderFlySpeed", settings.senderFlySpeed());
        JsonArray hosts = new JsonArray();
        settings.allowedSkinHosts().forEach(hosts::add);
        root.add("allowedSkinHosts", hosts);
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(temp, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
