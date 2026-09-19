package anima.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A runtime-registered frame animation for an in-world sprite (blocks, items, entities).
 * Emitted as a synthesized {@code .png.mcmeta} by the {@link VirtualAnimationPack} and parsed
 * by vanilla's atlas pipeline, so it is compatible with Sodium/Iris.
 *
 * @param frames      frame indices (empty = let vanilla infer from the texture dimensions)
 * @param frameTimeMs milliseconds per frame (converted to vanilla ticks, 1 tick = 50 ms)
 * @param interpolate whether to interpolate between frames
 */
public record WorldSpriteAnimationSpec(int[] frames, int frameTimeMs, boolean interpolate) {
	public WorldSpriteAnimationSpec {
		frames = frames == null ? new int[0] : frames.clone();
	}

	public JsonObject toMcmetaJson() {
		JsonObject anim = new JsonObject();
		if (frames.length > 0) {
			JsonArray arr = new JsonArray();
			for (int f : frames) {
				arr.add(f);
			}
			anim.add("frames", arr);
		}
		anim.addProperty("frametime", Math.max(1, Math.round(frameTimeMs / 50f)));
		if (interpolate) {
			anim.addProperty("interpolate", true);
		}
		JsonObject root = new JsonObject();
		root.add("animation", anim);
		return root;
	}
}
