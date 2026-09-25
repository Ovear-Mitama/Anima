package anima.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;

import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;
import anima.text.AnimatedTextStyle;
import anima.text.TextAnimationSpec;

/**
 * Applies tag-based text animations. When a glyph's style carries our
 * {@code anima:textanim/<tag>} font marker, the style color is replaced
 * with the animated RGB and the (final) alpha field is updated per glyph.
 * <p>
 * 26.1 起原版的 {@code Font$StringRenderOutput} 被 {@code Font$PreparedTextBuilder} 取代，
 * 字形颜色由 {@code ARGB.color(builder.color 的 alpha, style 的 RGB)} 合成 ——
 * 颜色来自 Style，alpha 来自整段共用的 {@code color} 字段，所以两者都要改。
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public class FontPreparedTextBuilderMixin {
	/** 整段文字共用的基础颜色（含 alpha），对应旧版 StringRenderOutput 的 alpha 字段。 */
	@Shadow
	@Final
	@Mutable
	private int color;

	@Unique
	private boolean anima$baseColorInit;

	@Unique
	private int anima$baseColor;

	@ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
		at = @At("HEAD"), argsOnly = true, index = 2)
	private Style anima$applyAnimatedStyle(Style style) {
		// color 被这次绘制调用的每个字形共用，先记住任何本库写入之前的原始值。
		if (!anima$baseColorInit) {
			anima$baseColorInit = true;
			anima$baseColor = this.color;
		}
		String tag = AnimatedTextStyle.tagFromMarker(style.getFont());
		TextAnimationSpec spec = tag == null ? null : AnimatedTextStyle.get(tag);
		if (spec == null) {
			this.color = anima$baseColor;
			return style;
		}

		RenderModifier modifier = spec.computeModifier(AnimationEngine.get().globalTimeMs());
		int rgb = (Math.round(clamp01(modifier.r) * 255f) << 16)
			| (Math.round(clamp01(modifier.g) * 255f) << 8)
			| Math.round(clamp01(modifier.b) * 255f);
		// Alpha 写在共享 color 字段上（字形的 alpha 只从这里取），RGB 写在 Style 上。
		// 基础 alpha 保持不变，避免动画过程中把文字整体画成半透明。
		this.color = ARGB.color(Math.round(clamp01(modifier.a) * ARGB.alpha(anima$baseColor)),
			anima$baseColor & 0x00FFFFFF);
		return style.withFont(FontDescription.DEFAULT).withColor(TextColor.fromRgb(rgb));
	}

	@Unique
	private static float clamp01(float value) {
		return Math.min(Math.max(value, 0f), 1f);
	}
}
