package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.IAnimationEffect;
import anima.engine.RenderModifier;

/**
 * Fade in / hold / fade out driven by the alpha channel. The alpha curve is piecewise
 * linear; {@code loop} makes the whole curve repeat.
 */
public final class FadeEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> new FadeEffect(
		json.has("fade_in") ? json.get("fade_in").getAsFloat() : 200f,
		json.has("hold") ? json.get("hold").getAsFloat() : 0f,
		json.has("fade_out") ? json.get("fade_out").getAsFloat() : 200f,
		json.has("loop") && json.get("loop").getAsBoolean());

	private final float fadeInMs;
	private final float holdMs;
	private final float fadeOutMs;
	private final boolean loop;

	public FadeEffect(float fadeInMs, float holdMs, float fadeOutMs, boolean loop) {
		this.fadeInMs = Math.max(fadeInMs, 0f);
		this.holdMs = Math.max(holdMs, 0f);
		this.fadeOutMs = Math.max(fadeOutMs, 0f);
		this.loop = loop;
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float totalMs = fadeInMs + holdMs + fadeOutMs;
		if (totalMs <= 0f) {
			modifier.alpha(1f);
			return;
		}
		float tMs;
		if (loop) {
			tMs = timeSec * 1000f % totalMs;
		} else {
			tMs = Math.min(timeSec * 1000f, totalMs);
		}
		float alpha;
		if (tMs < fadeInMs) {
			alpha = fadeInMs <= 0f ? 1f : tMs / fadeInMs;
		} else if (tMs <= fadeInMs + holdMs) {
			alpha = 1f;
		} else {
			float out = fadeOutMs <= 0f ? 0f : (tMs - fadeInMs - holdMs) / fadeOutMs;
			alpha = Math.max(0f, 1f - out);
		}
		modifier.alpha(Math.min(Math.max(alpha, 0f), 1f));
	}
}
