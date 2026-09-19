package anima.platform;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import anima.manager.AnimatedTextureManager;

/** NeoForge implementation of {@link PlatformHooks}. */
public class NeoForgePlatformHooks extends PlatformHooks {
	private final List<Runnable> clientTicks = new CopyOnWriteArrayList<>();
	private final List<PreparableReloadListener> reloadListeners = new CopyOnWriteArrayList<>();
	private final List<WorldRenderHook> worldHooks = new CopyOnWriteArrayList<>();

	@Override
	public void addClientTickListener(Runnable runnable) {
		clientTicks.add(runnable);
	}

	@Override
	public void addWorldRenderListener(WorldRenderHook hook) {
		worldHooks.add(hook);
	}

	/** Draws all registered world-space hooks after entities are rendered. */
	public void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || worldHooks.isEmpty()) {
			return;
		}
		PoseStack pose = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		for (WorldRenderHook hook : worldHooks) {
			hook.render(pose, buffers, event.getCamera(), 0f);
		}
		buffers.endBatch();
	}

	@Override
	public void registerClientReloadListener(ResourceLocation id, PreparableReloadListener listener) {
		reloadListeners.add(listener);
	}

	public void onClientTick(ClientTickEvent.Post event) {
		for (Runnable runnable : clientTicks) {
			runnable.run();
		}
	}

	public void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
		for (PreparableReloadListener listener : reloadListeners) {
			event.registerReloadListener(listener);
		}
	}

	/** Registers the virtual animation pack as an always-active top-priority client pack. */
	public void onAddPackFinders(AddPackFindersEvent event) {
		if (event.getPackType() != PackType.CLIENT_RESOURCES) {
			return;
		}
		AnimatedTextureManager manager = AnimatedTextureManager.get();
		Pack pack = Pack.readMetaAndCreate(
			new PackLocationInfo(
				"anima:virtual",
				Component.translatable("anima.pack.title"),
				PackSource.BUILT_IN,
				Optional.empty()),
			new Pack.ResourcesSupplier() {
				@Override
				public PackResources openPrimary(PackLocationInfo location) {
					return manager.getVirtualPack();
				}

				@Override
				public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
					return manager.getVirtualPack();
				}
			},
			PackType.CLIENT_RESOURCES,
			new PackSelectionConfig(true, Pack.Position.TOP, false));
		event.addRepositorySource(onLoad -> onLoad.accept(pack));
	}
}
