package anima.manager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
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
public class AnimationJsonLoader extends SimpleJsonResourceReloadListener {
	public static final String DIRECTORY = "texture_animations";

	private final AnimatedTextureManager manager;
	private final Set<ResourceLocation> jsonWorldSprites = new HashSet<>();

	public AnimationJsonLoader(AnimatedTextureManager manager) {
		super(new GsonBuilder().create(), DIRECTORY);
		this.manager = manager;
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager, ProfilerFiller profiler) {
		manager.clearDefinitions();
		manager.removeWorldSprites(jsonWorldSprites);
		jsonWorldSprites.clear();

		for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
			ResourceLocation id = entry.getKey();
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
