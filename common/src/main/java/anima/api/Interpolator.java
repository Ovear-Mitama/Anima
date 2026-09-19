package anima.api;

/**
 * Maps a normalized time {@code t} in {@code [0, 1]} to an eased progress value.
 * Used by keyframe animations and one-shot effects.
 */
@FunctionalInterface
public interface Interpolator {
	float apply(float t);

	/** Convenience: {@code t} clamped into {@code [0, 1]}. */
	default float applyClamped(float t) {
		return apply(Math.min(Math.max(t, 0f), 1f));
	}
}
