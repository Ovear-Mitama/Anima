package anima.effects;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.api.IAnimationEffect;
import anima.engine.RenderModifier;

/**
 * Position shake (jitter). A deterministic sine-based offset combined with per-frame random
 * jitter, optionally decaying to zero over {@code durationMs}.
 */
public final class ShakeEffect implements IAnimationEffect {
	public static final IAnimationEffect.Factory FACTORY = json -> new ShakeEffect(
		json.has("amplitude") ? json.get("amplitude").getAsFloat() : 3f,
		json.has("frequency") ? json.get("frequency").getAsFloat() : 12f,
		json.has("duration") ? json.get("duration").getAsFloat() : 300f,
		json.has("decay") && json.get("decay").getAsBoolean());

	private final float amplitude;
	private final float frequency;
	private final float durationMs;
	private final boolean decay;

	public ShakeEffect(float amplitude, float frequency, float durationMs, boolean decay) {
		this.amplitude = Math.max(amplitude, 0f);
		this.frequency = Math.max(frequency, 0.1f);
		this.durationMs = Math.max(durationMs, 1f);
		this.decay = decay;
	}

	@Override
	public void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier) {
		float envelope = 1f;
		if (decay) {
			envelope = Math.max(0f, 1f - timeSec / (durationMs / 1000f));
		}
		float phase = timeSec * frequency * (float) (2f * Math.PI);
		float jitterX = random.nextFloat() * 2f - 1f;
		float jitterY = random.nextFloat() * 2f - 1f;
		float x = ((float) Math.sin(phase) * 0.6f + jitterX * 0.4f) * amplitude * envelope;
		float y = ((float) Math.cos(phase * 1.31f) * 0.6f + jitterY * 0.4f) * amplitude * envelope;
		modifier.translate(x, y);
	}
}
