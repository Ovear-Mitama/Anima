package anima.engine;

import anima.api.IAnimationEffect;

/**
 * A timed occurrence of an effect: which effect, when it started (ms) and how long it runs.
 */
public record EffectInstance(IAnimationEffect effect, float startMs, float durationMs) {
}
