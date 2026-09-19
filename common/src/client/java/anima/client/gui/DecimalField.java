package anima.client.gui;

import java.util.function.DoubleConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Like {@link NumberField} but for fractional values (3 decimals): position / scale / opacity.
 * Left-drag changes the value in small steps (0.01 per 2px) instead of whole units, typing keeps
 * any precision up to 3 decimals, and Left/Right arrows step by {@code step}.
 */
public class DecimalField extends EditBox {
	private static final int DECIMALS = 3;

	private final double min;
	private final double max;
	private final double step;
	private final DoubleConsumer onChanged;

	private boolean draggingVal;
	private double dragStartVal;
	private double dragStartX;

	public DecimalField(net.minecraft.client.gui.Font font, int x, int y, int w, int h,
			double min, double max, double step, String label, double initial, DoubleConsumer onChanged) {
		super(font, x, y, w, h, Component.literal(label));
		this.min = min;
		this.max = max;
		this.step = step <= 0 ? 0.01 : step;
		this.onChanged = onChanged;
		setMaxLength(12);
		setFilter(s -> s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")
				|| s.matches("-?\\d*(\\.\\d{0,3})?"));
		setValue(format(initial));
		setResponder(this::onText);
	}

	/** Always shows up to 3 decimals, trimming trailing zeros (1 -> "1", 0.5 -> "0.5"). */
	public static String format(double v) {
		String s = String.format(java.util.Locale.ROOT, "%." + DECIMALS + "f", v);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "");
			s = s.replaceAll("\\.$", "");
		}
		return s.isEmpty() ? "0" : s;
	}

	private void onText(String s) {
		if (!s.isBlank() && !s.equals("-") && !s.equals(".") && !s.equals("-.")) {
			try {
				onChanged.accept(clamp(Double.parseDouble(s)));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private double clamp(double v) {
		return Math.max(min, Math.min(max, v));
	}

	private double current() {
		try {
			return Double.parseDouble(getValue().isEmpty() ? "0" : getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void apply(double value) {
		value = clamp(value);
		setValue(format(value));
		onChanged.accept(value);
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
			// 0.01 per 2px — fine enough for world positions and scale
			double delta = Math.round((mouseX - dragStartX) / 2.0) * step;
			apply(dragStartVal + delta);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingVal = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (isFocused()) {
			if (keyCode == 263) { // GLFW left arrow → decrease
				apply(current() - step);
				return true;
			}
			if (keyCode == 262) { // GLFW right arrow → increase
				apply(current() + step);
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/** Same VS-style underlined input as NumberField (|____|, no box). */
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
			int caretX = x + 1 + Math.round(font.width(v) * 1.12f) + 1;
			g.fill(caretX, y, caretX + 1, lineY - 2, GuiTheme.ACCENT);
		}
	}
}

