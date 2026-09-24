package anima.client.gui;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * 客户端按键绑定入口：不开 Mod Menu 也能进编辑器（NeoForge 同样走这里）。
 * 平台入口负责注册这两个 {@link KeyMapping}，并每 tick 调用 {@link #handle()}。
 * <p>
 * 两个按键<b>默认都未指定</b>，请在「选项 → 按键控制 → 杂项」里自行绑定。
 */
public final class EditorKeybinds {
	/** 打开 / 关闭动画编辑器（时间线界面）。默认未指定。 */
	public static final KeyMapping OPEN_EDITOR = new KeyMapping(
		"key.anima.editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
		"key.categories.misc");

	/** 编辑器内播放 / 暂停。默认未指定（编辑器里空格也能播放 / 暂停）。 */
	public static final KeyMapping PLAY_PAUSE = new KeyMapping(
		"key.anima.play_pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
		"key.categories.misc");

	private EditorKeybinds() {
	}

	/** 按键按下且当前没有其它界面时打开编排 / 时间线编辑器。
	 *  在世界里它以浮窗形式打开，世界会在背后继续渲染。 */
	public static void handle() {
		if (OPEN_EDITOR.consumeClick()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.screen == null) {
				mc.setScreen(new CompositeEditScreen(null, mc.level != null));
			}
		}
	}
}
