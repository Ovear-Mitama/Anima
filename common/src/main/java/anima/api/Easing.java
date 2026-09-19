package anima.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in interpolators (easing functions). Every entry is registered into the
 * {@link anima.engine.AnimationEngine} under the same key, so JSON
 * definitions can reference them by name (e.g. {@code "ease": "easeOutCubic"}).
 */
public final class Easing {
	public static final Interpolator LINEAR = t -> t;
	public static final Interpolator STEP = t -> t < 0.5f ? 0f : 1f;

	public static final Interpolator EASE_IN_QUAD = t -> t * t;
	public static final Interpolator EASE_OUT_QUAD = t -> 1f - (1f - t) * (1f - t);
	public static final Interpolator EASE_IN_OUT_QUAD = t ->
		t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;

	public static final Interpolator EASE_IN_CUBIC = t -> t * t * t;
	public static final Interpolator EASE_OUT_CUBIC = t -> 1f - (float) Math.pow(1f - t, 3f);
	public static final Interpolator EASE_IN_OUT_CUBIC = t ->
		t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;

	public static final Interpolator EASE_IN_QUART = t -> t * t * t * t;
	public static final Interpolator EASE_OUT_QUART = t -> 1f - (float) Math.pow(1f - t, 4f);
	public static final Interpolator EASE_IN_OUT_QUART = t ->
		t < 0.5f ? 8f * t * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 4f) / 2f;

	public static final Interpolator EASE_IN_QUINT = t -> t * t * t * t * t;
	public static final Interpolator EASE_OUT_QUINT = t -> 1f - (float) Math.pow(1f - t, 5f);
	public static final Interpolator EASE_IN_OUT_QUINT = t ->
		t < 0.5f ? 16f * t * t * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 5f) / 2f;

	public static final Interpolator EASE_IN_SINE = t -> 1f - (float) Math.cos(t * Math.PI / 2f);
	public static final Interpolator EASE_OUT_SINE = t -> (float) Math.sin(t * Math.PI / 2f);
	public static final Interpolator EASE_IN_OUT_SINE = t ->
		(float) (-(Math.cos(Math.PI * t) - 1f) / 2f);

	public static final Interpolator EASE_IN_EXPO = t -> t == 0f ? 0f : (float) Math.pow(2f, 10f * t - 10f);
	public static final Interpolator EASE_OUT_EXPO = t -> t == 1f ? 1f : 1f - (float) Math.pow(2f, -10f * t);
	public static final Interpolator EASE_IN_OUT_EXPO = t -> {
		if (t == 0f) return 0f;
		if (t == 1f) return 1f;
		return t < 0.5f ? (float) Math.pow(2f, 20f * t - 10f) / 2f
			: (2f - (float) Math.pow(2f, -20f * t + 10f)) / 2f;
	};

	public static final Interpolator EASE_IN_CIRC = t -> 1f - (float) Math.sqrt(1f - t * t);
	public static final Interpolator EASE_OUT_CIRC = t -> (float) Math.sqrt(1f - (float) Math.pow(t - 1f, 2f));
	public static final Interpolator EASE_IN_OUT_CIRC = t ->
		t < 0.5f
			? (1f - (float) Math.sqrt(1f - 4f * t * t)) / 2f
			: ((float) Math.sqrt(1f - (float) Math.pow(-2f * t + 2f, 2f)) + 1f) / 2f;

	private static final float BACK_C1 = 1.70158f;
	private static final float BACK_C3 = BACK_C1 + 1f;
	public static final Interpolator EASE_IN_BACK = t -> BACK_C3 * t * t * t - BACK_C1 * t * t;
	public static final Interpolator EASE_OUT_BACK = t -> {
		float u = t - 1f;
		return 1f + BACK_C3 * u * u * u + BACK_C1 * u * u;
	};
	public static final Interpolator EASE_IN_OUT_BACK = t -> {
		float c2 = BACK_C1 * 1.525f;
		return t < 0.5f
			? ((float) Math.pow(2f * t, 2f) * ((c2 + 1f) * 2f * t - c2)) / 2f
			: ((float) Math.pow(2f * t - 2f, 2f) * ((c2 + 1f) * (2f * t - 2f) + c2) + 2f) / 2f;
	};

	private static final float ELASTIC_C4 = (float) (2f * Math.PI / 3f);
	public static final Interpolator EASE_IN_ELASTIC = t ->
		t == 0f ? 0f : t == 1f ? 1f
			: -(float) Math.pow(2f, 10f * t - 10f) * (float) Math.sin((t * 10f - 10.75f) * ELASTIC_C4);
	public static final Interpolator EASE_OUT_ELASTIC = t ->
		t == 0f ? 0f : t == 1f ? 1f
			: (float) Math.pow(2f, -10f * t) * (float) Math.sin((t * 10f - 0.75f) * ELASTIC_C4) + 1f;
	public static final Interpolator EASE_IN_OUT_ELASTIC = t -> {
		float c5 = (float) (2f * Math.PI / 4.5f);
		if (t == 0f) return 0f;
		if (t == 1f) return 1f;
		return t < 0.5f
			? -(float) Math.pow(2f, 20f * t - 10f) * (float) Math.sin((20f * t - 11.125f) * c5) / 2f
			: (float) Math.pow(2f, -20f * t + 10f) * (float) Math.sin((20f * t - 11.125f) * c5) / 2f + 1f;
	};

	private static final float BOUNCE_N1 = 7.5625f;
	private static final float BOUNCE_D1 = 2.75f;
	public static final Interpolator EASE_OUT_BOUNCE = t -> {
		if (t < 1f / BOUNCE_D1) {
			return BOUNCE_N1 * t * t;
		} else if (t < 2f / BOUNCE_D1) {
			float u = t - 1.5f / BOUNCE_D1;
			return BOUNCE_N1 * u * u + 0.75f;
		} else if (t < 2.5f / BOUNCE_D1) {
			float u = t - 2.25f / BOUNCE_D1;
			return BOUNCE_N1 * u * u + 0.9375f;
		} else {
			float u = t - 2.625f / BOUNCE_D1;
			return BOUNCE_N1 * u * u + 0.984375f;
		}
	};
	public static final Interpolator EASE_IN_BOUNCE = t -> 1f - EASE_OUT_BOUNCE.apply(1f - t);
	public static final Interpolator EASE_IN_OUT_BOUNCE = t ->
		t < 0.5f ? (1f - EASE_OUT_BOUNCE.apply(1f - 2f * t)) / 2f
			: (1f + EASE_OUT_BOUNCE.apply(2f * t - 1f)) / 2f;

	/** All built-in interpolators keyed by their JSON name. */
	public static final Map<String, Interpolator> ALL = new LinkedHashMap<>();

	static {
		ALL.put("linear", LINEAR);
		ALL.put("step", STEP);
		ALL.put("easeInQuad", EASE_IN_QUAD);
		ALL.put("easeOutQuad", EASE_OUT_QUAD);
		ALL.put("easeInOutQuad", EASE_IN_OUT_QUAD);
		ALL.put("easeInCubic", EASE_IN_CUBIC);
		ALL.put("easeOutCubic", EASE_OUT_CUBIC);
		ALL.put("easeInOutCubic", EASE_IN_OUT_CUBIC);
		ALL.put("easeInQuart", EASE_IN_QUART);
		ALL.put("easeOutQuart", EASE_OUT_QUART);
		ALL.put("easeInOutQuart", EASE_IN_OUT_QUART);
		ALL.put("easeInQuint", EASE_IN_QUINT);
		ALL.put("easeOutQuint", EASE_OUT_QUINT);
		ALL.put("easeInOutQuint", EASE_IN_OUT_QUINT);
		ALL.put("easeInSine", EASE_IN_SINE);
		ALL.put("easeOutSine", EASE_OUT_SINE);
		ALL.put("easeInOutSine", EASE_IN_OUT_SINE);
		ALL.put("easeInExpo", EASE_IN_EXPO);
		ALL.put("easeOutExpo", EASE_OUT_EXPO);
		ALL.put("easeInOutExpo", EASE_IN_OUT_EXPO);
		ALL.put("easeInCirc", EASE_IN_CIRC);
		ALL.put("easeOutCirc", EASE_OUT_CIRC);
		ALL.put("easeInOutCirc", EASE_IN_OUT_CIRC);
		ALL.put("easeInBack", EASE_IN_BACK);
		ALL.put("easeOutBack", EASE_OUT_BACK);
		ALL.put("easeInOutBack", EASE_IN_OUT_BACK);
		ALL.put("easeInElastic", EASE_IN_ELASTIC);
		ALL.put("easeOutElastic", EASE_OUT_ELASTIC);
		ALL.put("easeInOutElastic", EASE_IN_OUT_ELASTIC);
		ALL.put("easeInBounce", EASE_IN_BOUNCE);
		ALL.put("easeOutBounce", EASE_OUT_BOUNCE);
		ALL.put("easeInOutBounce", EASE_IN_OUT_BOUNCE);
	}

	private Easing() {
	}
}
