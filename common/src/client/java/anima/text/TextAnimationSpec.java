package anima.text;

import java.util.ArrayList;
import java.util.List;

import anima.Anima;
import anima.api.IAnimationEffect;
import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;
import anima.manager.AnimatedTextureManager;
import anima.manager.AnimationDefinition;
import anima.manager.EffectSpec;

/**
 * A set of effects applied to text (color / alpha over time). Used both by the explicit
 * {@link TextAnimations#draw} helper and by the tag-based auto mode via the Font mixin.
 */
public final class TextAnimationSpec {
	private final List<IAnimationEffect> effects;

	public TextAnimationSpec(List<IAnimationEffect> effects) {
		this.effects = List.copyOf(effects);
	}

	public static TextAnimationSpec of(IAnimationEffect... effects) {
		return new TextAnimationSpec(List.of(effects));
	}

	/** Builds a spec from the {@code effects} of a {@code text}-type animation definition. */
	public static TextAnimationSpec fromDefinition(AnimationDefinition def) {
		List<IAnimationEffect> list = new ArrayList<>(def.effects().size());
		for (EffectSpec spec : def.effects()) {
			IAnimationEffect effect = AnimationEngine.get().createEffect(spec.name(), spec.config());
			if (effect != null) {
				list.add(effect);
			} else {
				Anima.LOGGER.warn("Unknown text animation effect '{}' in {}", spec.name(), def.id());
			}
		}
		return new TextAnimationSpec(list);
	}

	/** Computes the combined render modifier at the given global time (ms). */
	public RenderModifier computeModifier(float timeMs) {
		return computeModifier(timeMs, 0f);
	}

	/**
	 * Computes the combined render modifier at {@code timeMs} with an explicit animation
	 * {@code durationMs} (the value every effect receives as its duration — e.g. the length of
	 * a damage-number clip). Effects that scale with the clip duration (fade, scale, …) then
	 * behave exactly like in the timeline editor.
	 */
	public RenderModifier computeModifier(float timeMs, float durationMs) {
		RenderModifier.Builder builder = RenderModifier.builder();
		float timeSec = Math.max(timeMs, 0f) / 1000f;
		float durSec = Math.max(durationMs, 0f) / 1000f;
		for (IAnimationEffect effect : effects) {
			effect.apply(timeSec, durSec, AnimatedTextureManager.get().random(), builder);
		}
		return builder.build();
	}

	public List<IAnimationEffect> effects() {
		return effects;
	}
}
