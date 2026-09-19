package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.Easing;
import anima.api.IAnimationEffect;
import anima.api.Interpolator;
import anima.engine.RenderModifier;

/**
 * Scales the rendered element from {@code from} to {@code to} with an easing function,
 * optionally looping.
 */
public final class ScaleEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> new ScaleEffect(
		json.has("from") ? json.get("from").getAsFloat() : 1f,
		json.has("to") ? json.get("to").getAsFloat() : 1f,
		json.has("duration") ? json.get("duration").getAsFloat() : 300f,
		Easing.ALL.getOrDefault(json.has("ease") ? json.get("ease").getAsString() : "linear", Easing.LINEAR),
		json.has("loop") && json.get("loop").getAsBoolean());

	private final float from;
	private final float to;
	private final float durationMs;
	private final Interpolator ease;
	private final boolean loop;

	public ScaleEffect(float from, float to, float durationMs, Interpolator ease, boolean loop) {
		this.from = from;
		this.to = to;
		this.durationMs = Math.max(durationMs, 1f);
		this.ease = ease == null ? Easing.LINEAR : ease;
		this.loop = loop;
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float durSec = durationMs / 1000f;
		float t = loop ? (timeSec % durSec) / durSec : Math.min(timeSec / durSec, 1f);
		float e = ease.applyClamped(t);
		float s = from + (to - from) * e;
		modifier.scale(s, s);
	}
}
