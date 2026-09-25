package anima.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import anima.Anima;
import anima.client.gui.EditorKeybinds;
import anima.client.world.WorldProjection;
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

		// 编辑器按键（默认未指定，需在「按键控制」里自行绑定）；不开 Mod Menu 也能进编辑器
		KeyMappingHelper.registerKeyMapping(EditorKeybinds.OPEN_EDITOR);
		KeyMappingHelper.registerKeyMapping(EditorKeybinds.PLAY_PAUSE);
		PlatformHooks.get().addClientTickListener(EditorKeybinds::handle);

		// keep the world projection matrix up to date for WorldProjection (world→screen helpers)
		PlatformHooks.get().addWorldRenderListener((pose, buffers, camera, partialTick) ->
			WorldProjection.captureProjection(WorldProjection.projectionFrom(camera)));

		// 26.1：HudRenderCallback 改为 HudElementRegistry（HudElement#extractRenderState）
		HudElementRegistry.addLast(Anima.id("demo_hud"),
			(guiGraphics, deltaTracker) -> DemoHud.extractRenderState(guiGraphics));
	}
}
