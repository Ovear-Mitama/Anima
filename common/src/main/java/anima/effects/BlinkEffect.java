package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.IAnimationEffect;
import anima.engine.RenderModifier;

/**
 * Periodic alpha blink between {@code minAlpha} and {@code maxAlpha} using a sine wave.
 */
public final class BlinkEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> new BlinkEffect(
		json.has("period") ? json.get("period").getAsFloat() : 500f,
		json.has("min_alpha") ? json.get("min_alpha").getAsFloat() : 0.1f,
		json.has("max_alpha") ? json.get("max_alpha").getAsFloat() : 1f);

	private final float periodMs;
	private final float minAlpha;
	private final float maxAlpha;

	public BlinkEffect(float periodMs, float minAlpha, float maxAlpha) {
		this.periodMs = Math.max(periodMs, 1f);
		this.minAlpha = Math.min(Math.max(minAlpha, 0f), 1f);
		this.maxAlpha = Math.min(Math.max(maxAlpha, 0f), 1f);
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float periodSec = periodMs / 1000f;
		float wave = 0.5f + 0.5f * (float) Math.sin(2f * Math.PI * timeSec / periodSec);
		float alpha = minAlpha + (maxAlpha - minAlpha) * wave;
		modifier.alpha(Math.min(Math.max(alpha, 0f), 1f));
	}
}
