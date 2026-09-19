package anima.client.gui;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Client keybind entrypoint so the editor is reachable without Mod Menu (and on NeoForge).
 * The platform entrypoints register {@link #OPEN_EDITOR} and call {@link #handle()} every tick.
 */
public final class EditorKeybinds {
	/** Default: F7 opens the animation editor / timeline screen. */
	public static final KeyMapping OPEN_EDITOR = new KeyMapping(
		"key.anima.editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7,
		"key.categories.misc");

	private EditorKeybinds() {
	}

	/** Opens the composite/timeline editor when the key is pressed and no other GUI is open.
	 *  Inside a world it opens as a floating window so the world keeps rendering behind it. */
	public static void handle() {
		if (OPEN_EDITOR.consumeClick()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.screen == null) {
				mc.setScreen(new CompositeEditScreen(null, mc.level != null));
			}
		}
	}
}
