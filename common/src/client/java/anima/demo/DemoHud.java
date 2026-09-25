package anima.demo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import anima.Anima;
import anima.manager.AnimatedTextureManager;
import anima.text.AnimatedTextStyle;
import anima.text.TextAnimationSpec;
import anima.text.TextAnimations;

/**
 * Demo HUD that shows off the library: an animated GUI sprite, an explicitly animated text
 * and a tag-based (auto) animated text. Set {@link #ENABLED} to {@code false} (or remove the
 * platform registrations) in production. Only renders while the bundled demo definitions
 * ({@code texture_animations/demo_*.json}) are present.
 */
public final class DemoHud {
	/** Set to false to disable the demo entirely. */
	public static boolean ENABLED = true;

	private DemoHud() {
	}

	public static void extractRenderState(GuiGraphicsExtractor guiGraphics) {
		if (!ENABLED) {
			return;
		}
		if (anima.client.gui.ConfigWorldLauncher.isConfigWorld()) {
			return; // no demo previews inside the sandbox config world
		}
		AnimatedTextureManager manager = AnimatedTextureManager.get();
		if (manager.definition(Anima.id("demo_gui")) == null) {
			return;
		}

		int screenWidth = guiGraphics.guiWidth();
		Font font = Minecraft.getInstance().font;

		// 1. Animated GUI sprite (frame cycling + blink effect).
		manager.drawAnimated(guiGraphics, Anima.id("demo_gui"),
			screenWidth / 2 - 32, 16, 64, 64);

		// 2. Explicit text animation (caller-controlled, e.g. HUD damage numbers).
		TextAnimationSpec spec = AnimatedTextStyle.get("demo_text");
		if (spec != null) {
			TextAnimations.draw(guiGraphics, font, Component.literal("Explicit fade+blink"),
				screenWidth / 2 - 70, 92, 0xFFFFFFFF, spec);
		}

		// 3. Tag-based auto animation (any text component, animated by the Font mixin).
		guiGraphics.text(font,
			TextAnimations.parse("<anim:demo_text>Tag-based animated text</anim:demo_text>"),
			screenWidth / 2 - 78, 112, 0xFFFFFFFF);
	}
}
