package anima.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import anima.engine.AnimationEngine;
import anima.text.AnimatedTextStyle;
import anima.text.TextAnimationSpec;
import anima.text.TextAnimations;

/**
 * Client-side manager for all animated textures. Loads definitions, creates GUI sprite
 * instances, tracks in-world sprite animations (served by the {@link VirtualAnimationPack})
 * and advances the engine clock.
 */
public final class AnimatedTextureManager {
	private static AnimatedTextureManager INSTANCE;

	private final RandomSource random = RandomSource.create();
	private final Map<ResourceLocation, AnimationDefinition> definitions = new HashMap<>();
	/** Original definitions loaded from resource packs (unmodified by config overrides). */
	private final Map<ResourceLocation, AnimationDefinition> resourceDefinitions = new HashMap<>();
	private final Map<ResourceLocation, AnimatedGuiSprite> guiSprites = new HashMap<>();
	private final Map<ResourceLocation, WorldSpriteAnimationSpec> worldSprites = new HashMap<>();
	private VirtualAnimationPack virtualPack;

	private AnimatedTextureManager() {
	}

	public static AnimatedTextureManager get() {
		if (INSTANCE == null) {
			INSTANCE = new AnimatedTextureManager();
		}
		return INSTANCE;
	}

	/** Advances the global animation clock. Called every client tick. */
	public void tickClient(float deltaMs) {
		AnimationEngine.get().tickClient(deltaMs);
	}

	/** Elapsed game time in ms since the previous tick, from the client's delta tracker. */
	public static float clientTickDeltaMs() {
		return Minecraft.getInstance().getTimer().getGameTimeDeltaTicks() * 50f;
	}

	// ------------------------------------------------------------------ definitions

	public void registerDefinition(AnimationDefinition definition) {
		definitions.put(definition.id(), definition);
		if (definition.type() == AnimationType.TEXT) {
			TextAnimations.registerTag(definition.id().getPath(),
				TextAnimationSpec.fromDefinition(definition));
		}
	}

	/** Records a definition as coming from a resource pack (restorable after override removal). */
	public void registerResourceDefinition(AnimationDefinition definition) {
		resourceDefinitions.put(definition.id(), definition);
	}

	/** The definitions loaded from resource packs (unaffected by config overrides). */
	public Map<ResourceLocation, AnimationDefinition> resourceDefinitions() {
		return resourceDefinitions;
	}

	/** All currently registered definitions (resource-pack + config overrides). */
	public Map<ResourceLocation, AnimationDefinition> definitions() {
		return definitions;
	}

	/** Applies/updates a definition (used by the editor). Rebuilds the GUI sprite cache. */
	public void applyDefinition(AnimationDefinition definition) {
		registerDefinition(definition);
		closeGuiSprites(definition.id());
		if (definition.type() == AnimationType.WORLD_SPRITE && definition.texture() != null) {
			registerWorldSprite(definition.texture(),
				new WorldSpriteAnimationSpec(definition.frameIndices(), definition.frameTimeMs(), definition.interpolate()));
		}
	}

	/** Applies a {@code text}-type definition as a tag. */
	public void applyTextTag(AnimationDefinition definition) {
		if (definition.type() == AnimationType.TEXT) {
			TextAnimations.registerTag(definition.id().getPath(),
				TextAnimationSpec.fromDefinition(definition));
		}
	}

	public AnimationDefinition definition(ResourceLocation id) {
		return definitions.get(id);
	}

	/** Restores a resource-pack definition for {@code id}, removing any config override. */
	public void resetDefinition(ResourceLocation id) {
		AnimationDefinition res = resourceDefinitions.get(id);
		closeGuiSprites(id);
		if (res != null) {
			applyDefinition(res);
		} else {
			definitions.remove(id);
		}
	}

	public void clearDefinitions() {
		closeGuiTextures();
		definitions.clear();
		resourceDefinitions.clear();
		AnimatedTextStyle.clearTags();
	}

	// ------------------------------------------------------------------ GUI sprites

	/** Returns the shared template sprite for a looping definition. */
	public AnimatedGuiSprite getGuiSprite(ResourceLocation id) {
		return guiSprites.computeIfAbsent(id, this::createGuiSprite);
	}

	/** Creates a fresh instance (own timeline) for one-shot animations. */
	public AnimatedGuiSprite createGuiInstance(ResourceLocation id) {
		AnimationDefinition def = definitions.get(id);
		if (def == null) {
			return null;
		}
		return new AnimatedGuiSprite(def, AnimationEngine.get().globalTimeMs());
	}

	/** Convenience: draws the shared template sprite at the given position/size. */
	public void drawAnimated(GuiGraphics guiGraphics, ResourceLocation id, int x, int y, int w, int h) {
		AnimatedGuiSprite sprite = getGuiSprite(id);
		if (sprite != null) {
			sprite.draw(guiGraphics, x, y, w, h, AnimationEngine.get().globalTimeMs());
		}
	}

	/** Releases all dynamic GUI textures (e.g. on resource reload). */
	public void closeGuiTextures() {
		guiSprites.values().forEach(AnimatedGuiSprite::close);
		guiSprites.clear();
	}

	/** Releases the cached GUI sprite for a single id. */
	public void closeGuiSprites(ResourceLocation id) {
		AnimatedGuiSprite sprite = guiSprites.remove(id);
		if (sprite != null) {
			sprite.close();
		}
	}

	// ------------------------------------------------------------------ world sprites

	/** Registers (or replaces) an in-world sprite frame animation. Applied on next reload. */
	public void registerWorldSprite(ResourceLocation sprite, WorldSpriteAnimationSpec spec) {
		worldSprites.put(sprite, spec);
	}

	public WorldSpriteAnimationSpec worldSprite(ResourceLocation sprite) {
		return worldSprites.get(sprite);
	}

	public Map<ResourceLocation, WorldSpriteAnimationSpec> worldSprites() {
		return worldSprites;
	}

	/** Removes the given world-sprite animations (used by the JSON loader on reload). */
	public void removeWorldSprites(Set<ResourceLocation> sprites) {
		sprites.forEach(worldSprites::remove);
	}

	/** The virtual pack serving synthesized {@code .png.mcmeta} files. */
	public VirtualAnimationPack getVirtualPack() {
		if (virtualPack == null) {
			virtualPack = new VirtualAnimationPack(this);
		}
		return virtualPack;
	}

	public RandomSource random() {
		return random;
	}

	private AnimatedGuiSprite createGuiSprite(ResourceLocation id) {
		AnimationDefinition def = definitions.get(id);
		if (def == null) {
			return null;
		}
		return new AnimatedGuiSprite(def, AnimationEngine.get().globalTimeMs());
	}
}
