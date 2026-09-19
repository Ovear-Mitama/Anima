package anima.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import anima.engine.RenderModifier;

/**
 * Custom preview renderer used by {@link CompositeEditScreen}. The library provides a default
 * that draws its own sample text; other mods that want to preview their own content can register
 * a renderer via {@code CompositeEditScreen.setPreviewRenderer(...)} and draw whatever applies.
 *
 * @param g        the graphics context
 * @param font     the GUI font
 * @param x, y, w, h  the preview canvas rectangle (center area)
 * @param modifier aggregate of all currently-active effects (apply alpha/rgb/offset yourself)
 */
@FunctionalInterface
public interface PreviewRenderer {
	void render(GuiGraphics g, Font font, int x, int y, int w, int h, RenderModifier modifier);
}