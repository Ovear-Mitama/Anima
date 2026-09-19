package anima.engine;

import java.util.List;

/**
 * An immutable keyframe animation definition: a duration, a playback mode and an ordered
 * list of keyframes. {@link #sample(float)} returns the interpolated value at a time in
 * milliseconds (the caller is responsible for wrapping the time according to the play mode).
 */
public record KeyframeAnimation(float durationMs, PlayMode playMode, List<Keyframe> keyframes) {
	public KeyframeAnimation {
		keyframes = List.copyOf(keyframes);
	}

	public float sample(float tMs) {
		if (keyframes.isEmpty()) {
			return 0f;
		}
		if (keyframes.size() == 1) {
			return keyframes.get(0).value();
		}
		Keyframe prev = keyframes.get(0);
		for (int i = 1; i < keyframes.size(); i++) {
			Keyframe next = keyframes.get(i);
			if (tMs <= next.timeMs()) {
				return interpolate(prev, next, tMs);
			}
			prev = next;
		}
		return prev.value();
	}

	private static float interpolate(Keyframe a, Keyframe b, float tMs) {
		float span = Math.max(b.timeMs() - a.timeMs(), 0.001f);
		float t = Math.min(Math.max((tMs - a.timeMs()) / span, 0f), 1f);
		float eased = a.interpolator().applyClamped(t);
		return a.value() + (b.value() - a.value()) * eased;
	}
}
