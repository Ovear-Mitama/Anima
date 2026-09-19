package anima.engine;

/**
 * Mutable playback state for one animation instance. Tracks the current time (in ms) and
 * handles {@link PlayMode} wrapping (loop / pingpong / stop-at-end).
 */
public class AnimationTimeline {
	private final KeyframeAnimation animation;
	private float timeMs;
	private boolean playing = true;
	private int loopCount;

	public AnimationTimeline(KeyframeAnimation animation) {
		this.animation = animation;
	}

	/** Advances the timeline by {@code deltaMs} milliseconds. */
	public void tick(float deltaMs) {
		if (!playing) {
			return;
		}
		float duration = Math.max(animation.durationMs(), 0.001f);
		timeMs += Math.max(deltaMs, 0f);
		switch (animation.playMode()) {
			case ONCE -> {
				if (timeMs >= duration) {
					timeMs = duration;
					playing = false;
				}
			}
			case LOOP -> {
				if (timeMs >= duration) {
					loopCount += (int) (timeMs / duration);
					timeMs %= duration;
				}
			}
			case PINGPONG -> {
				float period = duration * 2f;
				if (timeMs >= period) {
					loopCount += (int) (timeMs / period);
					timeMs %= period;
				}
			}
		}
	}

	/** Returns the current sampled value, applying pingpong mirroring. */
	public float sample() {
		switch (animation.playMode()) {
			case PINGPONG -> {
				float duration = Math.max(animation.durationMs(), 0.001f);
				float period = duration * 2f;
				float local = timeMs % period;
				return animation.sample(local <= duration ? local : period - local);
			}
			default -> {
				return animation.sample(timeMs);
			}
		}
	}

	public void play() {
		this.playing = true;
	}

	public void pause() {
		this.playing = false;
	}

	public void reset() {
		this.timeMs = 0f;
		this.playing = true;
		this.loopCount = 0;
	}

	public boolean isPlaying() {
		return playing;
	}

	public float timeMs() {
		return timeMs;
	}

	public int loopCount() {
		return loopCount;
	}
}
