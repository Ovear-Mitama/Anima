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
			// F7 (default) opens the editor without needing Mod Menu
			modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(EditorKeybinds.OPEN_EDITOR));
			PlatformHooks.get().addClientTickListener(EditorKeybinds::handle);
			// world-space drawing (e.g. the preview object placed inside the config world);
			// RenderLevelStageEvent lives on the MOD bus, not the game event bus
			modBus.addListener((RenderLevelStageEvent event) -> hooks.onRenderLevelStage(event));
			// keep the world projection matrix up to date for WorldProjection (world→screen helpers)
			PlatformHooks.get().addWorldRenderListener((pose, buffers, camera, partialTick) ->
				anima.client.world.WorldProjection.captureProjection(
					com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()));
		}
	}
}
