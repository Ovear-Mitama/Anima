package anima.engine;

import anima.api.Interpolator;

/**
 * A single keyframe: at {@code timeMs} the animation takes {@code value}, easing from the
 * previous keyframe with {@code interpolator}.
 */
public record Keyframe(float timeMs, float value, Interpolator interpolator) {
	public Keyframe {
		if (interpolator == null) {
			interpolator = anima.api.Easing.LINEAR;
		}
	}
}
