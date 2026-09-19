package anima.effects;

import anima.engine.AnimationEngine;

/**
 * Registers all built-in effects into the {@link AnimationEngine}.
 */
public final class BuiltinEffects {
	private BuiltinEffects() {
	}

	public static void register(AnimationEngine engine) {
		engine.registerEffect("fade", FadeEffect.FACTORY);
		engine.registerEffect("blink", BlinkEffect.FACTORY);
		engine.registerEffect("pulse", PulseEffect.FACTORY);
		engine.registerEffect("shake", ShakeEffect.FACTORY);
		engine.registerEffect("slide", SlideEffect.FACTORY);
		engine.registerEffect("scale", ScaleEffect.FACTORY);
	}
}
