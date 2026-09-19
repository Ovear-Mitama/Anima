package anima.client.gui;

/**
 * A single animation-effect layer on the timeline. Created by dragging an effect from the
 * left palette; {@code startMs} is its position on the timeline, {@code durationMs} its
 * visible length, {@code speed} its playback rate. The center preview applies the effect to
 * the sample text while the playhead is inside {@code [startMs, startMs + durationMs]}.
 * <p>
 * For particle clips {@code particle} stores the chosen vanilla/mod particle registry id
 * (e.g. {@code "minecraft:flame"}, {@code "modid:xxx"}); empty for non-particle effects.
 * {@code params} holds a JSON string with effect-specific options (e.g. the 3D particle
 * count / offset keys) and may be edited from the property panel.
 */
public record CompositeClip(String effect, String displayName, String particle, float speed, float durationMs, float startMs, String params) {

	public static CompositeClip of(String effect, String displayName) {
		return new CompositeClip(effect, displayName, "", 1f, 1600f, 0f, "{}");
	}

	/** Convenience constructor keeping the legacy 6-arg signature (params default to {}). */
	public CompositeClip(String effect, String displayName, String particle, float speed, float durationMs, float startMs) {
		this(effect, displayName, particle, speed, durationMs, startMs, "{}");
	}

	public CompositeClip withParticle(String p) { return new CompositeClip(effect, displayName, p, speed, durationMs, startMs, params); }
	public CompositeClip withName(String n) { return new CompositeClip(effect, n, particle, speed, durationMs, startMs, params); }
	public CompositeClip withSpeed(float ns) { return new CompositeClip(effect, displayName, particle, ns, durationMs, startMs, params); }
	public CompositeClip withDuration(float nd) { return new CompositeClip(effect, displayName, particle, speed, nd, startMs, params); }
	public CompositeClip withStart(float ns) { return new CompositeClip(effect, displayName, particle, speed, durationMs, ns, params); }
	public CompositeClip withParams(String p) { return new CompositeClip(effect, displayName, particle, speed, durationMs, startMs, p); }
}
