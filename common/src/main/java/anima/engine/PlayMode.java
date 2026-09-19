package anima.engine;

/** Playback mode of a keyframe animation. */
public enum PlayMode {
	/** Plays once and stays on the last value. */
	ONCE,
	/** Loops forever. */
	LOOP,
	/** Plays forward then backward, looping. */
	PINGPONG;

	public static PlayMode fromString(String value) {
		if (value == null) {
			return LOOP;
		}
		return switch (value.toLowerCase()) {
			case "once" -> ONCE;
			case "pingpong", "ping_pong" -> PINGPONG;
			default -> LOOP;
		};
	}
}
