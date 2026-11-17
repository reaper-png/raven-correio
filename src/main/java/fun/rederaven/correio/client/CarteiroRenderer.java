package fun.rederaven.correio.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import fun.rederaven.correio.entity.CarteiroEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual code only. The dedicated server chooses the skin and syncs its parameters
 * through entity data. The physical client has no RavenCorreio configuration.
 */
public final class CarteiroRenderer extends HumanoidMobRenderer<CarteiroEntity, PlayerModel<CarteiroEntity>> {
    private static final Logger LOG = LoggerFactory.getLogger("RavenCorreio");
    private static final ResourceLocation STEVE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final Map<UUID, CompletableFuture<PlayerSkin>> NICK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<ResourceLocation>> URL_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;

    private final PlayerModel<CarteiroEntity> wideModel;
    private final PlayerModel<CarteiroEntity> slimModel;

    public CarteiroRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.35F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    @Override
    public void render(CarteiroEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        boolean slim = entity.skinSlim();
        if (entity.skinUrl().isEmpty()) {
            UUID skinId = entity.skinId().orElse(null);
            if (skinId != null) {
                CompletableFuture<PlayerSkin> loaded = NICK_CACHE.get(skinId);
                if (loaded != null && loaded.isDone()) {
                    PlayerSkin playerSkin = loaded.getNow(null);
                    if (playerSkin != null) slim = playerSkin.model() == PlayerSkin.Model.SLIM;
                }
            }
        }
        this.model = slim ? slimModel : wideModel;
        super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarteiroEntity entity) {
        String url = entity.skinUrl();
        if (!url.isEmpty()) {
            // A revision change makes an existing PNG URL re-download after /correio reload.
            String key = entity.skinRevision() + "|" + url;
            CompletableFuture<ResourceLocation> future = URL_CACHE.computeIfAbsent(key, k -> downloadUrlSkin(url));
            return future.getNow(STEVE);
        }
        UUID id = entity.skinId().orElse(null);
        if (id == null || entity.skinName().isEmpty()) return STEVE;
        CompletableFuture<PlayerSkin> future = NICK_CACHE.computeIfAbsent(id, uuid ->
            CompletableFuture.supplyAsync(() -> Minecraft.getInstance()
                .getMinecraftSessionService().fetchProfile(uuid, true))
                .thenCompose(result -> result == null
                    ? CompletableFuture.completedFuture(null)
                    : Minecraft.getInstance().getSkinManager().getOrLoad(result.profile()))
                .exceptionally(ex -> {
                    LOG.warn("Nao foi possivel carregar skin Mojang do Carteiro", ex);
                    return null;
                }));
        if (!future.isDone()) return STEVE;
        PlayerSkin skin = future.getNow(null);
        return skin == null ? STEVE : skin.texture();
    }

    private static CompletableFuture<ResourceLocation> downloadUrlSkin(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadPng(url);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).thenCompose(image -> {
            CompletableFuture<ResourceLocation> registered = new CompletableFuture<>();
            // OpenGL operations must happen on Minecraft's render thread.
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                try {
                    DynamicTexture texture = new DynamicTexture(image);
                    ResourceLocation id = client.getTextureManager().register("ravencorreio_carteiro", texture);
                    registered.complete(id);
                } catch (Exception exception) {
                    image.close();
                    registered.completeExceptionally(exception);
                }
            });
            return registered;
        }).exceptionally(exception -> {
            LOG.warn("Falha ao carregar PNG HTTPS do Carteiro: {}", exception.toString());
            return STEVE;
        });
    }

    private static NativeImage loadPng(String url) throws IOException {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IOException("URL invalida", exception);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null)
            throw new IOException("Somente HTTPS e permitido");
        // Redirects are forbidden: do not silently download from another host.
        HttpURLConnection connection = (HttpURLConnection) parsed.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "image/png");
        connection.setRequestProperty("User-Agent", "RavenCorreio/0.2 Minecraft/1.21.1");
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("HTTP " + connection.getResponseCode());
            if (connection.getContentLengthLong() > MAX_SKIN_BYTES)
                throw new IOException("Skin muito grande (maximo 2 MiB)");
            byte[] bytes;
            try (var stream = connection.getInputStream()) {
                bytes = stream.readNBytes(MAX_SKIN_BYTES + 1);
            }
            if (bytes.length > MAX_SKIN_BYTES) throw new IOException("Skin muito grande");
            if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P'
                || bytes[2] != 'N' || bytes[3] != 'G')
                throw new IOException("O link nao retornou um arquivo PNG");
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                image.close();
                throw new IOException("A skin precisa ter 64x64 pixels");
            }
            return image;
        } finally {
            connection.disconnect();
        }
    }

    @Override
    protected boolean shouldShowName(CarteiroEntity entity) { return true; }
}
