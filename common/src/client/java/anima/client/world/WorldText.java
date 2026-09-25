package anima.client.world;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import anima.engine.RenderModifier;
import anima.text.TextAnimationSpec;
import anima.text.TextAnimations;

/**
 * Public helper that draws text anchored to a <b>world position</b> by projecting it to the
 * screen (see {@link WorldProjection}) and painting it on the GUI layer — the approach used by
 * HUD-style effects such as floating damage numbers.
 * <p>
 * The animation can be driven by a {@link TextAnimationSpec} with its own local clock
 * ({@code localMs}), so every instance animates independently of the global engine time.
 */
public final class WorldText {
	private WorldText() {
	}

	/** Projects the world position and draws {@code text} there, centred horizontally. */
	public static boolean draw(GuiGraphicsExtractor g, Font font, String text, double worldX, double worldY, double worldZ,
			int color, TextAnimationSpec spec, float localMs, boolean shadow) {
		RenderModifier modifier = spec == null ? null : spec.computeModifier(localMs);
		return draw(g, font, text, worldX, worldY, worldZ, color, modifier, shadow, true);
	}

	/**
	 * Draws {@code text} at a world position plus an explicit {@code offX/offY/offZ} offset,
	 * evaluated at {@code localMs} with a clip {@code durationMs} — the code-level hook for
	 * "时长 + xyz 偏移" (e.g. random displacement for jumping damage numbers, like Damage-Engine).
	 */
	public static boolean draw(GuiGraphicsExtractor g, Font font, String text, double worldX, double worldY, double worldZ,
			double offX, double offY, double offZ, int color, TextAnimationSpec spec, float localMs, float durationMs, boolean shadow) {
		RenderModifier modifier = spec == null ? null : spec.computeModifier(localMs, durationMs);
		return draw(g, font, text, worldX + offX, worldY + offY, worldZ + offZ, color, modifier, shadow, true);
	}

	/**
	 * Draws a world-anchored {@link Component} (so styles such as bold survive) with an extra
	 * <b>screen-space offset in GUI pixels</b> — the hook for callers that compute their own
	 * random spread / drift (e.g. Damage-Engine damage numbers). The animation comes from
	 * {@code spec} evaluated at {@code localMs} with a clip {@code durationMs}, and
	 * {@code scaleMul / alphaMul} stack the caller's own size / opacity on top.
	 * <p>
	 * (x, y) is the CENTRE of the text and scaling grows it in place.
	 *
	 * @return {@code false} when the anchor is behind the camera or the text is fully transparent
	 */
	public static boolean drawAnchored(GuiGraphicsExtractor g, Font font, Component text, double worldX, double worldY,
			double worldZ, float offsetX, float offsetY, int color, TextAnimationSpec spec,
			float localMs, float durationMs, boolean shadow, float scaleMul, float alphaMul) {
		float[] p = WorldProjection.project(worldX, worldY, worldZ);
		if (p == null) {
			return false; // behind the camera
		}
		RenderModifier modifier = spec == null ? null : spec.computeModifier(localMs, durationMs);
		return drawAtCentered(g, font, text, p[0] + offsetX, p[1] + offsetY, color, modifier, shadow, scaleMul, alphaMul);
	}

	/** Projects the world position and draws {@code text} with a precomputed modifier. */
	public static boolean draw(GuiGraphicsExtractor g, Font font, String text, double worldX, double worldY, double worldZ,
			int color, RenderModifier modifier, boolean shadow, boolean centered) {
		float[] p = WorldProjection.project(worldX, worldY, worldZ);
		if (p == null) {
			return false; // behind the camera
		}
		float x = centered ? p[0] - font.width(text) / 2f : p[0];
		return drawAt(g, font, text, x, p[1] - 4f, color, modifier, shadow);
	}

	/** Draws {@code text} at screen coordinates with the modifier applied (no projection). */
	public static boolean drawAt(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color,
			RenderModifier modifier, boolean shadow) {
		return drawAt(g, font, text, x, y, color, modifier, shadow, 1f, 1f);
	}

	/**
	 * Centered variant of {@link #drawAt}: (x, y) is the CENTER of the text. The editor preview
	 * uses this so changing the object's scale grows the text in place instead of shifting it.
	 */
	public static boolean drawAtCentered(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color,
			RenderModifier modifier, boolean shadow, float scaleMul, float alphaMul) {
		int finalColor = color;
		float tx = 0f;
		float ty = 0f;
		float sx = scaleMul;
		float sy = scaleMul;
		if (modifier != null) {
			finalColor = TextAnimations.applyModifierColor(color, modifier);
			tx = modifier.tx;
			ty = modifier.ty;
			sx = modifier.sx * scaleMul;
			sy = modifier.sy * scaleMul;
		}
		int alpha = (finalColor >>> 24) & 0xFF;
		alpha = Math.round(alpha * Math.max(0f, Math.min(1f, alphaMul)));
		if (alpha <= 2) {
			return false; // invisible
		}
		finalColor = (alpha << 24) | (finalColor & 0x00FFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().translate(tx, ty);
		g.pose().scale(sx, sy); // scaling happens around the anchor → the text simply grows
		g.text(font, text, -font.width(text) / 2, -4, finalColor, shadow);
		g.pose().popMatrix();
		return true;
	}

	/** {@link Component} variant of {@link #drawAtCentered} (keeps styles such as bold). */
	public static boolean drawAtCentered(GuiGraphicsExtractor g, Font font, Component text, float x, float y, int color,
			RenderModifier modifier, boolean shadow, float scaleMul, float alphaMul) {
		int finalColor = color;
		float tx = 0f;
		float ty = 0f;
		float sx = scaleMul;
		float sy = scaleMul;
		if (modifier != null) {
			finalColor = TextAnimations.applyModifierColor(color, modifier);
			tx = modifier.tx;
			ty = modifier.ty;
			sx = modifier.sx * scaleMul;
			sy = modifier.sy * scaleMul;
		}
		int alpha = (finalColor >>> 24) & 0xFF;
		alpha = Math.round(alpha * Math.max(0f, Math.min(1f, alphaMul)));
		if (alpha <= 2) {
			return false; // invisible
		}
		finalColor = (alpha << 24) | (finalColor & 0x00FFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().translate(tx, ty);
		g.pose().scale(sx, sy); // scaling happens around the anchor → the text simply grows
		g.text(font, text, -font.width(text) / 2, -4, finalColor, shadow);
		g.pose().popMatrix();
		return true;
	}

	/**
	 * Draws {@code text} at screen coordinates with the modifier applied plus an extra object
	 * scale / opacity (used by object properties with keyframes).
	 */
	public static boolean drawAt(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color,
			RenderModifier modifier, boolean shadow, float scaleMul, float alphaMul) {
		int finalColor = color;
		float tx = 0f;
		float ty = 0f;
		float sx = scaleMul;
		float sy = scaleMul;
		if (modifier != null) {
			finalColor = TextAnimations.applyModifierColor(color, modifier);
			tx = modifier.tx;
			ty = modifier.ty;
			sx = modifier.sx * scaleMul;
			sy = modifier.sy * scaleMul;
		}
		int alpha = (finalColor >>> 24) & 0xFF;
		alpha = Math.round(alpha * Math.max(0f, Math.min(1f, alphaMul)));
		if (alpha <= 2) {
			return false; // invisible
		}
		finalColor = (alpha << 24) | (finalColor & 0x00FFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(tx, ty);
		g.pose().scale(sx, sy);
		// translate to the exact (fractional) position and draw at 0,0 — like Damage-Engine —
		// so the text follows the camera smoothly instead of snapping to whole pixels
		g.pose().translate(x, y);
		g.text(font, text, 0, 0, finalColor, shadow);
		g.pose().popMatrix();
		return true;
	}
}
