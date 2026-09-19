package anima.api;

import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import anima.engine.RenderModifier;

/**
 * A single animation effect applied to a render modifier over time.
 * <p>
 * {@code timeSec} is the elapsed time since the effect started, {@code durationSec} is the
 * configured duration (effects may ignore it, e.g. for looping effects). Effects are
 * expected to be deterministic where possible; {@link RandomSource} is provided for
 * jitter-style effects.
 */
@FunctionalInterface
public interface IAnimationEffect {
	void apply(float timeSec, float durationSec, RandomSource random, RenderModifier.Builder modifier);

	/** Creates an effect from a JSON configuration object. */
	@FunctionalInterface
	interface Factory {
		IAnimationEffect create(JsonObject json);
	}
}
