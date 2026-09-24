package anima.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import anima.client.gui.EditorKeybinds;
import anima.demo.DemoHud;
import anima.engine.AnimationEngine;
import anima.platform.NeoForgePlatformHooks;
import anima.platform.PlatformHooks;

/** NeoForge entrypoint. */
@Mod("anima")
public class AnimaNeoForge {
	public AnimaNeoForge(IEventBus modBus) {
		AnimationEngine.get().init();

		NeoForgePlatformHooks hooks = new NeoForgePlatformHooks();
		PlatformHooks.set(hooks);

		modBus.addListener((AddPackFindersEvent event) -> hooks.onAddPackFinders(event));
		modBus.addListener((RegisterClientReloadListenersEvent event) -> hooks.onRegisterReloadListeners(event));
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> hooks.onClientTick(event));
		NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
			DemoHud.render(event.getGuiGraphics());
		});

		if (FMLEnvironment.dist == Dist.CLIENT) {
			// 编辑器按键（默认未指定，需在「按键控制」里自行绑定）；不开 Mod Menu 也能进编辑器
			modBus.addListener((RegisterKeyMappingsEvent event) -> {
				event.register(EditorKeybinds.OPEN_EDITOR);
				event.register(EditorKeybinds.PLAY_PAUSE);
			});
			PlatformHooks.get().addClientTickListener(EditorKeybinds::handle);
			// world-space drawing (e.g. the preview object placed inside the config world);
			// RenderLevelStageEvent 只实现 Event(不是 IModBusEvent),按 NeoForge 文档它挂在
			// 主事件总线 NeoForge.EVENT_BUS 上,注册到 mod bus 会在构造时直接抛异常
			NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> hooks.onRenderLevelStage(event));
			// keep the world projection matrix up to date for WorldProjection (world→screen helpers)
			PlatformHooks.get().addWorldRenderListener((pose, buffers, camera, partialTick) ->
				anima.client.world.WorldProjection.captureProjection(
					com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()));
		}
	}
}
