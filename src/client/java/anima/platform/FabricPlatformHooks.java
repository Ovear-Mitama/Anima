package anima.platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.Camera;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/** Fabric implementation of {@link PlatformHooks}. */
public class FabricPlatformHooks extends PlatformHooks {
	private final java.util.List<WorldRenderHook> worldHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
	private boolean worldEventRegistered;

	@Override
	public void addClientTickListener(Runnable runnable) {
		ClientTickEvents.END_CLIENT_TICK.register(client -> runnable.run());
	}

	@Override
	public void addWorldRenderListener(WorldRenderHook hook) {
		worldHooks.add(hook);
		if (!worldEventRegistered) {
			worldEventRegistered = true;
			// 26.1：WorldRenderEvents 更名为 LevelRenderEvents，相机 / 缓冲改由上下文提供
			LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
				Camera camera = context.gameRenderer().getMainCamera();
				for (WorldRenderHook h : worldHooks) {
					h.render(context.poseStack(), context.bufferSource(), camera, 0f);
				}
			});
		}
	}

	@Override
	public void registerClientReloadListener(Identifier id, PreparableReloadListener listener) {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return id;
			}

			// 26.1：reload 改为 (SharedState, Executor, PreparationBarrier, Executor) 四参形式
			@Override
			public CompletableFuture<Void> reload(SharedState state, Executor prepareExecutor,
					PreparationBarrier barrier, Executor applyExecutor) {
				return listener.reload(state, prepareExecutor, barrier, applyExecutor);
			}
		});
	}
}
