package anima.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text input in the same VS-style as {@link NumberField}: underline only (no box), a dim
 * placeholder when empty, and a blinking caret when focused. Used for the particle picker's
 * search bar and the clip-name input in the properties panel.
 */
public class SearchField extends EditBox {
	private final String placeholder;
	private final Consumer<String> onChanged;

	public SearchField(Font font, int x, int y, int w, int h, String placeholder, Consumer<String> onChanged) {
		super(font, x, y, w, h, Component.literal(placeholder));
		this.placeholder = placeholder;
		this.onChanged = onChanged;
		setMaxLength(64);
		setResponder(this::onText);
	}

	private void onText(String s) {
		onChanged.accept(s);
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		boolean foc = isFocused();
		boolean over = isHoveredOrFocused();
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int lineY = y + h - 2;
		int col = foc ? GuiTheme.ACCENT : (over ? GuiTheme.SUBTEXT : GuiTheme.BORDER);
		g.fill(x, lineY, x + w, lineY + 1, col);
		Font font = Minecraft.getInstance().font;
		String v = getValue();
		if (v.isEmpty()) {
			g.text(font, placeholder, x + 1, y + 2, 0xFF6A6A6A);
		} else {
			g.text(font, v, x + 1, y + 2, GuiTheme.TEXT);
		}
		if (foc && (System.currentTimeMillis() / 500L) % 2L == 0L) {
			// 光标画在真实的插入点，而不是永远画在末尾（否则方向键移动光标时看不出来）
			String before = v.substring(0, Math.max(0, Math.min(getCursorPosition(), v.length())));
			int caretX = x + 1 + font.width(before);
			g.fill(caretX, y, caretX + 1, lineY - 1, GuiTheme.ACCENT);
		}
	}
}
