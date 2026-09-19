package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.IAnimationEffect;
import anima.engine.RenderModifier;

/**
 * Periodic color/brightness pulse. Multiplies the color channels by a sine wave between
 * {@code minFactor} and {@code maxFactor}; an optional {@code color} tints the pulse.
 */
public final class PulseEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> new PulseEffect(
		json.has("period") ? json.get("period").getAsFloat() : 800f,
		json.has("min_factor") ? json.get("min_factor").getAsFloat() : 0.5f,
		json.has("max_factor") ? json.get("max_factor").getAsFloat() : 1f,
		readColor(json));

	private final float periodMs;
	private final float minFactor;
	private final float maxFactor;
	private final float cr;
	private final float cg;
	private final float cb;

	public PulseEffect(float periodMs, float minFactor, float maxFactor, int color) {
		this.periodMs = Math.max(periodMs, 1f);
		this.minFactor = minFactor;
		this.maxFactor = maxFactor;
		this.cr = ((color >> 16) & 0xFF) / 255f;
		this.cg = ((color >> 8) & 0xFF) / 255f;
		this.cb = (color & 0xFF) / 255f;
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float periodSec = periodMs / 1000f;
		float wave = 0.5f + 0.5f * (float) Math.sin(2f * Math.PI * timeSec / periodSec);
		float m = minFactor + (maxFactor - minFactor) * wave;
		modifier.mulColor(cr * m, cg * m, cb * m);
	}

	private static int readColor(JsonObject json) {
		if (json.has("color") && json.get("color").isJsonArray()) {
			var arr = json.getAsJsonArray("color");
			int r = (int) (arr.get(0).getAsFloat() * 255f) & 0xFF;
			int g = (int) (arr.get(1).getAsFloat() * 255f) & 0xFF;
			int b = (int) (arr.get(2).getAsFloat() * 255f) & 0xFF;
			return (r << 16) | (g << 8) | b;
		}
		return 0xFFFFFF;
	}
}
