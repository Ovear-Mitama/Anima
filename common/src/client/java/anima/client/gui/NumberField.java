package anima.client.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 数值输入框：可以直接打字，也可以按住左键横向拖动来连续改值（拖 2 像素走一个 {@code step}）。
 * 拖动到系统屏幕边缘时光标会环绕到对面边缘，因此可以一直朝同一方向拖。
 * <p>
 * 左右方向键<b>不再</b>用于加减数值——那是文本里移动光标的按键，抢过来就没法改光标位置了。
 */
public class NumberField extends EditBox {
	private final int min;
	private final int max;
	private final int step;
	private final IntConsumer onChanged;

	// left-drag value editing
	private boolean draggingVal;
	private int dragStartVal;
	private double dragStartX;

	public NumberField(net.minecraft.client.gui.Font font, int x, int y, int w, int h,
			int min, int max, int step, String label, int initial, IntConsumer onChanged) {
		super(font, x, y, w, h, Component.literal(label));
		this.min = min;
		this.max = max;
		this.step = Math.max(1, step);
		this.onChanged = onChanged;
		setMaxLength(10);
		setFilter(s -> s.chars().allMatch(Character::isDigit) || s.equals("-") || s.isEmpty());
		setValue(String.valueOf(initial));
		setResponder(this::onText);
	}

	private void onText(String s) {
		if (!s.isBlank() && !s.equals("-")) {
			try {
				onChanged.accept(clamp(Integer.parseInt(s)));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private int clamp(int v) {
		return Math.max(min, Math.min(max, v));
	}

	private int current() {
		try {
			return Integer.parseInt(getValue().isEmpty() ? "0" : getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void apply(int value) {
		setValue(String.valueOf(value));
		onText(String.valueOf(value));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean r = super.mouseClicked(mouseX, mouseY, button);
		if (button == 0 && isMouseOver(mouseX, mouseY)) {
			draggingVal = true;
			dragStartVal = current();
			dragStartX = mouseX;
		}
		return r;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (draggingVal && button == 0) {
			int delta = (int) Math.round((mouseX - dragStartX) / 2.0) * step;
			apply(clamp(dragStartVal + delta));
			// 拖到系统屏幕边缘就环绕光标，并同步拖拽起点，这样可以一直朝同一方向拖
			dragStartX += DragCursor.wrapAtScreenEdge();
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingVal = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	/** Custom draw: VS-style underlined input (|____|, no box). Focus draws a thicker accent line. */
	@Override
	public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		boolean foc = isFocused();
		boolean over = isHoveredOrFocused();
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int lineY = y + h - 2;
		int col = foc ? GuiTheme.ACCENT : (over ? GuiTheme.SUBTEXT : GuiTheme.BORDER);
		int thick = 1; // the underline only changes COLOUR when focused, never thickness
		// the underline only — no box around the digits
		g.fill(x, lineY - thick + 1, x + w, lineY + 1, col);
		net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
		String v = getValue();
		// text is drawn one size bigger, slightly raised so it sits inside the short field
		g.pose().pushPose();
		g.pose().translate(x + 1, y + (h - 8) / 2 - 1, 0f);
		g.pose().scale(1.12f, 1.12f, 1f);
		if (v.isEmpty()) {
			g.drawString(font, "0", 0, 0, GuiTheme.SUBTEXT);
		} else {
			g.drawString(font, v, 0, 0, GuiTheme.TEXT);
		}
		g.pose().popPose();
		// blinking caret (only every other 500ms) when focused — aligned with the digits, which
		// are drawn one size bigger and raised, so the caret sits slightly above the baseline
		if (foc && (System.currentTimeMillis() / 500L) % 2L == 0L) {
			// 光标要画在真实的插入点，而不是永远画在末尾 —— 否则左右方向键移动光标时
			// 看起来光标没动，实际插入位置已经变了
			String before = v.substring(0, Math.max(0, Math.min(getCursorPosition(), v.length())));
			int caretX = x + 1 + Math.round(font.width(before) * 1.12f) + 1;
			g.fill(caretX, y, caretX + 1, lineY - 2, GuiTheme.ACCENT);
		}
	}
}

