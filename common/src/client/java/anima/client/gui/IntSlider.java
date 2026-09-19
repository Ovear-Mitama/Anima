package anima.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slim integer slider mapping a {@code [min,max]} range onto the vanilla slider.
 */
public class IntSlider extends AbstractSliderButton {
	private final int min;
	private final int max;
	private final java.util.function.IntConsumer applier;
	private final String label;

	public IntSlider(int x, int y, int width, int height, String label, int min, int max, int value,
			java.util.function.IntConsumer applier) {
		super(x, y, width, height, Component.literal(label + ": " + value), toNorm(value, min, max));
		this.label = label;
		this.min = min;
		this.max = max;
		this.applier = applier;
		updateMessage();
	}

	public int value() {
		int v = min + (int) Math.round(value * (max - min));
		return Math.max(min, Math.min(max, v));
	}

	private static double toNorm(int value, int min, int max) {
		if (max <= min) {
			return 0d;
		}
		return (double) (Math.max(min, Math.min(max, value)) - min) / (double) (max - min);
	}

	public void setValue(int v) {
		this.value = toNorm(v, min, max);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label + ": " + value()));
	}

	@Override
	protected void applyValue() {
		applier.accept(value());
		updateMessage();
	}
}