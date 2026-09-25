package anima.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import anima.manager.AnimatedTextureManager;

/** NeoForge implementation of {@link PlatformHooks}. */
public class NeoForgePlatformHooks extends PlatformHooks {
	private final List<Runnable> clientTicks = new CopyOnWriteArrayList<>();
	private final Map<Identifier, PreparableReloadListener> reloadListeners = new LinkedHashMap<>();
	private final List<WorldRenderHook> worldHooks = new CopyOnWriteArrayList<>();

	@Override
	public void addClientTickListener(Runnable runnable) {
		clientTicks.add(runnable);
	}

	@Override
	public void addWorldRenderListener(WorldRenderHook hook) {
		worldHooks.add(hook);
	}

	/** Draws all registered world-space hooks after the translucent features of the level. */
	public void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentFeatures event) {
		if (worldHooks.isEmpty()) {
			return;
		}
		// 26.1：事件自身不再提供相机，改从主相机取（与 Fabric 的 LevelRenderContext 保持一致）
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		PoseStack pose = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		for (WorldRenderHook hook : worldHooks) {
			hook.render(pose, buffers, camera, 0f);
		}
		buffers.endBatch();
	}

	@Override
	public void registerClientReloadListener(Identifier id, PreparableReloadListener listener) {
		reloadListeners.put(id, listener);
	}

	/** 这些移动键的上下文被 NeoForge 设成 IN_GAME，编辑器打开期间需要临时放宽。 */
	private final java.util.Map<KeyMapping, IKeyConflictContext> savedKeyContexts = new java.util.HashMap<>();

	@Override
	public void allowMovementKeysInGui(boolean allow) {
		Minecraft mc = Minecraft.getInstance();
		KeyMapping[] keys = {
			mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
			mc.options.keyJump, mc.options.keyShift, mc.options.keySprint
		};
		if (allow) {
			savedKeyContexts.clear();
			for (KeyMapping km : keys) {
				// 26.1：冲突上下文相关方法移到了 IKeyMappingExtension 接口上
				IKeyMappingExtension ext = (IKeyMappingExtension) km;
				savedKeyContexts.put(km, ext.getKeyConflictContext());
				ext.setKeyConflictContext(KeyConflictContext.UNIVERSAL);
			}
		} else if (!savedKeyContexts.isEmpty()) {
			savedKeyContexts.forEach((km, ctx) -> ((IKeyMappingExtension) km).setKeyConflictContext(ctx));
			savedKeyContexts.clear();
		}
	}

	public void onClientTick(ClientTickEvent.Post event) {
		for (Runnable runnable : clientTicks) {
			runnable.run();
		}
	}

	/** 26.1：RegisterClientReloadListenersEvent 更名为 AddClientReloadListenersEvent，且按 id 注册。 */
	public void onAddReloadListeners(AddClientReloadListenersEvent event) {
		reloadListeners.forEach(event::addListener);
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
