package anima.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;

/**
 * Public API for text animation.
 * <ul>
 *   <li><b>Explicit helper</b>: {@link #draw(GuiGraphicsExtractor, Font, Component, int, int, int, TextAnimationSpec)} — the
 *       caller controls timing, suitable for HUD / damage numbers.</li>
 *   <li><b>Tag-based auto mode</b>: {@link #withTag(Component, String)} or {@link #parse(String)} mark a component with
 *       a {@code anima:textanim/<tag>} style; the Font mixin animates it wherever it is drawn
 *       (chat, buttons, …).</li>
 * </ul>
 * The marker replaces the style's font field, so components that use a custom font should use the explicit helper
 * instead. Measurement ({@link #width}) strips markers so advance widths stay correct.
 */
public final class TextAnimations {
	private static final Pattern TAG_PATTERN = Pattern.compile("<anim:([a-zA-Z0-9_]+)>(.*?)</anim>", Pattern.DOTALL);

	private TextAnimations() {
	}

	// ------------------------------------------------------------------ tag (auto) mode

	/** Marks a component so the Font mixin animates it with the given tag. */
	public static MutableComponent withTag(Component component, String tag) {
		FontDescription marker = AnimatedTextStyle.markerFor(tag);
		return component.copy().withStyle(style -> style.withFont(marker));
	}

	/** Parses {@code <anim:blink>…</anim>} tags inside a string into marker-styled segments. */
	public static Component parse(String text) {
		MutableComponent root = Component.literal("");
		Matcher matcher = TAG_PATTERN.matcher(text);
		int last = 0;
		while (matcher.find()) {
			if (matcher.start() > last) {
				root.append(Component.literal(text.substring(last, matcher.start())));
			}
			String tag = matcher.group(1);
			String inner = matcher.group(2);
			root.append(withTag(Component.literal(inner), tag));
			last = matcher.end();
		}
		if (last < text.length()) {
			root.append(Component.literal(text.substring(last)));
		}
		return root;
	}

	// ------------------------------------------------------------------ explicit helper

	/** Draws {@code text} with the animation spec applied (alpha + rgb + translate + scale). */
	public static int draw(GuiGraphicsExtractor guiGraphics, Font font, String text, int x, int y, int color, TextAnimationSpec spec) {
		return draw(guiGraphics, font, Component.literal(text), x, y, color, spec);
	}

	/** Draws {@code component} with the animation spec applied. */
	public static int draw(GuiGraphicsExtractor guiGraphics, Font font, Component component, int x, int y, int color, TextAnimationSpec spec) {
		return draw(guiGraphics, font, component, x, y, color, spec, AnimationEngine.get().globalTimeMs());
	}

	/** Draws {@code component} with the animation spec evaluated at a caller-provided time (ms).
	 *  Use this for effects with their own local clock (e.g. per-hit floating damage numbers). */
	public static int draw(GuiGraphicsExtractor guiGraphics, Font font, Component component, int x, int y, int color,
			TextAnimationSpec spec, float localMs) {
		return draw(guiGraphics, font, component, x, y, color, spec, localMs, 0f);
	}

	/**
	 * Draws {@code component} with the animation spec evaluated at {@code localMs} and an
	 * explicit clip {@code durationMs}. Other mods can drive the whole effect (time AND length)
	 * from code — e.g. a damage number that fades in and out over its own duration. The
	 * {@code x/y} screen position plus the modifier's own translate are the "xyz offset" hooks
	 * for code-controlled displacement.
	 */
	public static int draw(GuiGraphicsExtractor guiGraphics, Font font, Component component, int x, int y, int color,
			TextAnimationSpec spec, float localMs, float durationMs) {
		RenderModifier modifier = spec.computeModifier(localMs, durationMs);
		int finalColor = applyModifierColor(color, modifier);

		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().translate(modifier.tx, modifier.ty);
		guiGraphics.pose().scale(modifier.sx, modifier.sy);
		// 26.1 的 text(...) 不返回绘制后的 x 坐标，自行按字宽推算
		int result = x + font.width(component);
		guiGraphics.text(font, component, x, y, finalColor);
		guiGraphics.pose().popMatrix();
		return result;
	}

	/** Applies a render modifier's alpha/colour factors to a base ARGB colour. */
	public static int applyModifierColor(int color, RenderModifier modifier) {
		int alpha = Math.round(Math.min(Math.max(modifier.a, 0f), 1f) * 255f);
		int r = Math.round(Math.min(Math.max(modifier.r, 0f), 1f) * 255f);
		int g = Math.round(Math.min(Math.max(modifier.g, 0f), 1f) * 255f);
		int b = Math.round(Math.min(Math.max(modifier.b, 0f), 1f) * 255f);

		int baseA = (color >> 24) & 0xFF;
		int baseR = (color >> 16) & 0xFF;
		int baseG = (color >> 8) & 0xFF;
		int baseB = color & 0xFF;

		return (Math.round(baseA * (alpha / 255f)) << 24)
			| (Math.round(baseR * (r / 255f)) << 16)
			| (Math.round(baseG * (g / 255f)) << 8)
			| Math.round(baseB * (b / 255f));
	}

	// ------------------------------------------------------------------ measurement

	/** Width of a component with any marker styles stripped. */
	public static int width(Font font, Component component) {
		return font.width(stripMarkers(component));
	}

	/** Returns a copy of the component with marker styles (from the font field) removed. */
	public static Component stripMarkers(Component component) {
		return component.copy().withStyle(TextAnimations::restoreStyle);
	}

	private static Style restoreStyle(Style style) {
		if (AnimatedTextStyle.tagFromMarker(style.getFont()) != null) {
			return style.withFont(FontDescription.DEFAULT);
		}
		return style;
	}

	// ------------------------------------------------------------------ registration

	/** Registers a tag for the auto mode (also called by the JSON loader for {@code text} definitions). */
	public static void registerTag(String tag, TextAnimationSpec spec) {
		AnimatedTextStyle.registerTag(tag, spec);
	}
}
