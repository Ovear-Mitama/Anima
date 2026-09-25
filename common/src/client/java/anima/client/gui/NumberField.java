package anima.client.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
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
		setValue(String.valueOf(initial));
		setResponder(this::onText);
	}

	/** 26.1 起 {@code EditBox} 不再提供输入过滤器，改为在插入前自行校验（与原过滤器语义一致）。 */
	@Override
	public void insertText(String text) {
		String candidate = getValue().substring(0, getCursorPosition()) + text
			+ getValue().substring(getCursorPosition());
		if (candidate.isEmpty() || candidate.equals("-") || candidate.chars().allMatch(Character::isDigit)) {
			super.insertText(text);
		}
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
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		boolean r = super.mouseClicked(event, bl);
		if (event.button() == 0 && isMouseOver(event.x(), event.y())) {
			draggingVal = true;
			dragStartVal = current();
			dragStartX = event.x();
		}
		return r;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingVal && event.button() == 0) {
			int delta = (int) Math.round((event.x() - dragStartX) / 2.0) * step;
			apply(clamp(dragStartVal + delta));
			// 拖到系统屏幕边缘就环绕光标，并同步拖拽起点，这样可以一直朝同一方向拖
			dragStartX += DragCursor.wrapAtScreenEdge();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingVal = false;
		return super.mouseReleased(event);
	}

	/** Custom draw: VS-style underlined input (|____|, no box). Focus draws a thicker accent line. */
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
		int thick = 1; // the underline only changes COLOUR when focused, never thickness
		// the underline only — no box around the digits
		g.fill(x, lineY - thick + 1, x + w, lineY + 1, col);
		net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
		String v = getValue();
		// text is drawn one size bigger, slightly raised so it sits inside the short field
		g.pose().pushMatrix();
		g.pose().translate(x + 1, y + (h - 8) / 2 - 1);
		g.pose().scale(1.12f, 1.12f);
		if (v.isEmpty()) {
			g.text(font, "0", 0, 0, GuiTheme.SUBTEXT);
		} else {
			g.text(font, v, 0, 0, GuiTheme.TEXT);
		}
		g.pose().popMatrix();
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

