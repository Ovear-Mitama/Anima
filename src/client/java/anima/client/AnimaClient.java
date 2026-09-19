package anima.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import anima.Anima;
import anima.client.gui.EditorKeybinds;
import anima.demo.DemoHud;
import anima.manager.AnimationJsonLoader;
import anima.manager.AnimatedTextureManager;
import anima.platform.FabricPlatformHooks;
import anima.platform.PlatformHooks;

/** Fabric client entrypoint. Wires the tick, the JSON loader and the virtual animation pack. */
public class AnimaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PlatformHooks.set(new FabricPlatformHooks());

		AnimatedTextureManager manager = AnimatedTextureManager.get();
		PlatformHooks.get().addClientTickListener(() -> manager.tickClient(AnimatedTextureManager.clientTickDeltaMs()));
		PlatformHooks.get().registerClientReloadListener(Anima.id("animation_definitions"),
			new AnimationJsonLoader(manager));

		// F7 (default) opens the editor without needing Mod Menu
		KeyBindingHelper.registerKeyBinding(EditorKeybinds.OPEN_EDITOR);
		PlatformHooks.get().addClientTickListener(EditorKeybinds::handle);

		// keep the world projection matrix up to date for WorldProjection (world→screen helpers)
		PlatformHooks.get().addWorldRenderListener((pose, buffers, camera, partialTick) ->
			anima.client.world.WorldProjection.captureProjection(
				com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()));

		HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> {
			DemoHud.render(guiGraphics);
		});
	}
}
