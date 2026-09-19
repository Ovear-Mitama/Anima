package anima.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;
import anima.text.AnimatedTextStyle;
import anima.text.TextAnimationSpec;

/**
 * Applies tag-based text animations. When a glyph's style carries our
 * {@code anima:textanim/<tag>} font marker, the style color is replaced
 * with the animated RGB and the (final) alpha field is updated per glyph.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public class FontStringRenderOutputMixin {
	@Shadow
	@Final
	@Mutable
	private float a;

	@Shadow
	@Final
	private float dimFactor;

	@Unique
	private boolean tal$baseAlphaInit;

	@Unique
	private float tal$baseAlpha;

	@ModifyVariable(method = "accept", at = @At("HEAD"), argsOnly = true, index = 2)
	private Style tal$applyAnimatedStyle(Style style) {
		String tag = AnimatedTextStyle.tagFromMarker(style.getFont());
		if (tag == null) {
			tal$ensureBaseAlpha();
			this.a = tal$baseAlpha;
			return style;
		}
		TextAnimationSpec spec = AnimatedTextStyle.get(tag);
		if (spec == null) {
			tal$ensureBaseAlpha();
			this.a = tal$baseAlpha;
			return style;
		}

		RenderModifier modifier = spec.computeModifier(AnimationEngine.get().globalTimeMs());
		int rgb = (Math.round(clamp01(modifier.r) * 255f) << 16)
			| (Math.round(clamp01(modifier.g) * 255f) << 8)
			| Math.round(clamp01(modifier.b) * 255f);
		this.a = clamp01(modifier.a) * this.dimFactor;
		return style.withFont(Style.DEFAULT_FONT).withColor(TextColor.fromRgb(rgb));
	}

	@Unique
	private void tal$ensureBaseAlpha() {
		if (!tal$baseAlphaInit) {
			tal$baseAlphaInit = true;
			tal$baseAlpha = this.a;
		}
	}

	@Unique
	private static float clamp01(float value) {
		return Math.min(Math.max(value, 0f), 1f);
	}
}
