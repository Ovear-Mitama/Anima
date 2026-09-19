package anima.engine;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import anima.api.Easing;
import anima.api.IAnimationEffect;
import anima.api.Interpolator;
import anima.effects.BuiltinEffects;

/**
 * The core animation engine (client-side singleton).
 * <p>
 * Holds the global clock and the registries for interpolators, effects and keyframe
 * animations. Built-in interpolators and effects are registered in {@link #init()}, which
 * platform entrypoints call during mod initialization.
 */
public final class AnimationEngine {
	private static AnimationEngine INSTANCE;

	private final Map<String, Interpolator> interpolators = new HashMap<>();
	private final Map<String, IAnimationEffect.Factory> effects = new HashMap<>();
	private final Map<String, KeyframeAnimation> animations = new HashMap<>();
	private float globalTimeMs;

	private AnimationEngine() {
	}

	public static AnimationEngine get() {
		if (INSTANCE == null) {
			INSTANCE = new AnimationEngine();
		}
		return INSTANCE;
	}

	/** Registers built-in interpolators and effects. Safe to call once from each entrypoint. */
	public void init() {
		Easing.ALL.forEach(this.interpolators::put);
		BuiltinEffects.register(this);
	}

	/** Advances the global clock. Called every client tick. */
	public void tickClient(float deltaMs) {
		globalTimeMs += Math.max(deltaMs, 0f);
	}

	public float globalTimeMs() {
		return globalTimeMs;
	}

	// ------------------------------------------------------------------ interpolators

	public void registerInterpolator(String name, Interpolator interpolator) {
		interpolators.put(name, interpolator);
	}

	public Interpolator interpolator(String name) {
		return interpolators.getOrDefault(name, Easing.LINEAR);
	}

	// ------------------------------------------------------------------ effects

	public void registerEffect(String name, IAnimationEffect.Factory factory) {
		effects.put(name, factory);
	}

	/** Registers a fixed effect instance under the given name (ignores JSON config). */
	public void registerEffectInstance(String name, IAnimationEffect effect) {
		effects.put(name, json -> effect);
	}

	public IAnimationEffect createEffect(String name, JsonObject json) {
		IAnimationEffect.Factory factory = effects.get(name);
		return factory == null ? null : factory.create(json);
	}

	public boolean hasEffect(String name) {
		return effects.containsKey(name);
	}

	// ------------------------------------------------------------------ animations

	public void registerAnimation(String id, KeyframeAnimation animation) {
		animations.put(id, animation);
	}

	public KeyframeAnimation animation(String id) {
		return animations.get(id);
	}

	public AnimationTimeline createTimeline(String id) {
		KeyframeAnimation animation = animations.get(id);
		if (animation == null) {
			return null;
		}
		return new AnimationTimeline(animation);
	}
}
