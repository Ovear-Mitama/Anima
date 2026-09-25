package anima.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A custom, non-vanilla button in the VS-black style (flat background, accent border on
 * hover, centered text). Replacements for the ugly default button.
 */
public class ThemeButton extends Button {
	private ThemeButton(int x, int y, int w, int h, Component message, OnPress onPress) {
		super(x, y, w, h, message, onPress, s -> Component.empty());
	}

	public static ThemeButton of(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new ThemeButton(x, y, w, h, message, onPress);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		boolean over = isHoveredOrFocused();
		int border = over ? GuiTheme.ACCENT : GuiTheme.BORDER;
		int bg = over ? GuiTheme.PANEL_HOVER : GuiTheme.PANEL;
		g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
		g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, bg);
		net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
		int tx = getX() + (getWidth() - font.width(getMessage())) / 2;
		int ty = getY() + (getHeight() - 8) / 2 + 1; // +1: the caption otherwise hugs the top edge
		g.text(font, getMessage(), tx, ty, over ? GuiTheme.TEXT : GuiTheme.SUBTEXT);
	}
}