package anima.manager;

import java.io.BufferedReader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import anima.Anima;

/**
 * Loads animation definitions from {@code assets/<modid>/texture_animations/*.json}.
 * <ul>
 *   <li>{@code gui_sprite} / {@code gui_dynamic} → GUI sprites (drawn via
 *       {@link AnimatedTextureManager#drawAnimated}).</li>
 *   <li>{@code world_sprite} → registered for the {@link VirtualAnimationPack} overlay.</li>
 *   <li>{@code text} → registered as a text-animation tag.</li>
 * </ul>
 * Definitions are re-applied on every resource reload (F3+T). A malformed file only logs a
 * warning and is skipped.
 */
public class AnimationJsonLoader extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> {
	public static final String DIRECTORY = "texture_animations";

	private static final FileToIdConverter CONVERTER = FileToIdConverter.json(DIRECTORY);

	private final AnimatedTextureManager manager;
	private final Gson gson = new GsonBuilder().create();
	private final Set<Identifier> jsonWorldSprites = new HashSet<>();

	public AnimationJsonLoader(AnimatedTextureManager manager) {
		this.manager = manager;
	}

	/**
	 * 26.1 起 {@code SimpleJsonResourceReloadListener} 改为基于 Codec，这里直接按目录扫描 + Gson 解析，
	 * 键的形态（{@code namespace:文件名}，去掉目录与扩展名）与原行为一致。
	 */
	@Override
	protected Map<Identifier, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
		Map<Identifier, JsonElement> result = new LinkedHashMap<>();
		for (Map.Entry<Identifier, Resource> entry : CONVERTER.listMatchingResources(resourceManager).entrySet()) {
			try (BufferedReader reader = entry.getValue().openAsReader()) {
				JsonElement json = gson.fromJson(reader, JsonElement.class);
				if (json != null && json.isJsonObject()) {
					result.put(entry.getKey(), json);
				}
			} catch (Exception e) {
				Anima.LOGGER.warn("Failed to read animation definition {}: {}", entry.getKey(), e.toString());
			}
		}
		return result;
	}

	@Override
	protected void apply(Map<Identifier, JsonElement> objects, ResourceManager resourceManager, ProfilerFiller profiler) {
		manager.clearDefinitions();
		manager.removeWorldSprites(jsonWorldSprites);
		jsonWorldSprites.clear();

		for (Map.Entry<Identifier, JsonElement> entry : objects.entrySet()) {
			Identifier id = entry.getKey();
			try {
				AnimationDefinition definition = AnimationDefinition.fromJson(id, entry.getValue().getAsJsonObject());
				manager.registerResourceDefinition(definition);
				manager.registerDefinition(definition);
				if (definition.type() == AnimationType.WORLD_SPRITE && definition.texture() != null) {
					WorldSpriteAnimationSpec spec = new WorldSpriteAnimationSpec(
						definition.frameIndices(), definition.frameTimeMs(), definition.interpolate());
					manager.registerWorldSprite(definition.texture(), spec);
					jsonWorldSprites.add(definition.texture());
				}
			} catch (Exception e) {
				Anima.LOGGER.warn("Failed to load animation definition {}: {}", id, e.toString());
			}
		}

		// Re-apply user overrides from config (win over resource-pack definitions).
		AnimationConfigStore store = AnimationConfigStore.get();
		store.reload();
		store.loadInto(manager);
	}
}
