package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.Easing;
import anima.api.IAnimationEffect;
import anima.api.Interpolator;
import anima.engine.RenderModifier;

/**
 * Translates the rendered element from {@code from} to {@code to} (screen pixels) with an
 * easing function, optionally looping.
 */
public final class SlideEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> {
		float[] from = readPair(json, "from", 0f, 0f);
		float[] to = readPair(json, "to", 0f, 0f);
		return new SlideEffect(from[0], from[1], to[0], to[1],
			json.has("duration") ? json.get("duration").getAsFloat() : 300f,
			Easing.ALL.getOrDefault(json.has("ease") ? json.get("ease").getAsString() : "linear", Easing.LINEAR),
			json.has("loop") && json.get("loop").getAsBoolean());
	};

	private final float fromX;
	private final float fromY;
	private final float toX;
	private final float toY;
	private final float durationMs;
	private final Interpolator ease;
	private final boolean loop;

	public SlideEffect(float fromX, float fromY, float toX, float toY, float durationMs, Interpolator ease, boolean loop) {
		this.fromX = fromX;
		this.fromY = fromY;
		this.toX = toX;
		this.toY = toY;
		this.durationMs = Math.max(durationMs, 1f);
		this.ease = ease == null ? Easing.LINEAR : ease;
		this.loop = loop;
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float durSec = durationMs / 1000f;
		float t = loop ? (timeSec % durSec) / durSec : Math.min(timeSec / durSec, 1f);
		float e = ease.applyClamped(t);
		float x = fromX + (toX - fromX) * e;
		float y = fromY + (toY - fromY) * e;
		modifier.translate(x, y);
	}

	private static float[] readPair(JsonObject json, String key, float dx, float dy) {
		if (json.has(key) && json.get(key).isJsonArray()) {
			var arr = json.getAsJsonArray(key);
			return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat()};
		}
		return new float[]{dx, dy};
	}
}
