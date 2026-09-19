package anima.platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

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
			WorldRenderEvents.AFTER_ENTITIES.register(context -> {
				for (WorldRenderHook h : worldHooks) {
					h.render(context.matrixStack(), context.consumers(), context.camera(), 0f);
				}
			});
		}
	}

	@Override
	public void registerClientReloadListener(ResourceLocation id, PreparableReloadListener listener) {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return id;
			}

			@Override
			public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
					ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
					Executor backgroundExecutor, Executor gameExecutor) {
				return listener.reload(barrier, manager, prepareProfiler, applyProfiler,
					backgroundExecutor, gameExecutor);
			}
		});
	}
}
