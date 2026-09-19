package anima.manager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * A virtual resource pack that synthesizes {@code .png.mcmeta} files for every registered
 * in-world sprite animation. It is pushed to the top of the client resource stack, so the
 * animation metadata wins over lower-priority packs and is consumed by the vanilla atlas
 * pipeline (and therefore Sodium/Iris as well).
 */
public final class VirtualAnimationPack implements PackResources {
	private static final PackLocationInfo LOCATION = new PackLocationInfo(
		"anima:virtual",
		Component.translatable("anima.pack.title"),
		PackSource.BUILT_IN,
		Optional.empty());

	private final AnimatedTextureManager manager;

	public VirtualAnimationPack(AnimatedTextureManager manager) {
		this.manager = manager;
	}

	@Override
	public IoSupplier<InputStream> getRootResource(String... elements) {
		return null;
	}

	@Override
	public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
		if (packType != PackType.CLIENT_RESOURCES) {
			return null;
		}
		ResourceLocation sprite = animationMetaToSprite(location);
		if (sprite == null) {
			return null;
		}
		WorldSpriteAnimationSpec spec = manager.worldSprite(sprite);
		if (spec == null) {
			return null;
		}
		byte[] bytes = spec.toMcmetaJson().toString().getBytes(StandardCharsets.UTF_8);
		return () -> new ByteArrayInputStream(bytes);
	}

	@Override
	public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
		if (packType != PackType.CLIENT_RESOURCES || !"textures".equals(path)) {
			return;
		}
		for (Map.Entry<ResourceLocation, WorldSpriteAnimationSpec> entry : manager.worldSprites().entrySet()) {
			ResourceLocation sprite = entry.getKey();
			if (!sprite.getNamespace().equals(namespace)) {
				continue;
			}
			byte[] bytes = entry.getValue().toMcmetaJson().toString().getBytes(StandardCharsets.UTF_8);
			resourceOutput.accept(animationMetaLocation(sprite), () -> new ByteArrayInputStream(bytes));
		}
	}

	@Override
	public Set<String> getNamespaces(PackType packType) {
		if (packType != PackType.CLIENT_RESOURCES) {
			return Set.of();
		}
		Set<String> namespaces = new HashSet<>();
		for (ResourceLocation sprite : manager.worldSprites().keySet()) {
			namespaces.add(sprite.getNamespace());
		}
		return namespaces;
	}

	@Override
	public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
		if (serializer == PackMetadataSection.TYPE) {
			return (T) new PackMetadataSection(
				Component.translatable("anima.pack.title"),
				SharedConstants.RESOURCE_PACK_FORMAT,
				Optional.empty());
		}
		return null;
	}

	@Override
	public PackLocationInfo location() {
		return LOCATION;
	}

	@Override
	public void close() {
	}

	/** {@code <ns>:<path>} → {@code <ns>:textures/<path>.png.mcmeta}. */
	public static ResourceLocation animationMetaLocation(ResourceLocation sprite) {
		return ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(),
			"textures/" + sprite.getPath() + ".png.mcmeta");
	}

	/** {@code <ns>:textures/<path>.png.mcmeta} → {@code <ns>:<path>}, or {@code null}. */
	public static ResourceLocation animationMetaToSprite(ResourceLocation meta) {
		String path = meta.getPath();
		if (!path.startsWith("textures/") || !path.endsWith(".png.mcmeta")) {
			return null;
		}
		String spritePath = path.substring("textures/".length(), path.length() - ".png.mcmeta".length());
		return ResourceLocation.fromNamespaceAndPath(meta.getNamespace(), spritePath);
	}
}
