package anima.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Thin loader abstraction. Platform entrypoints install an implementation once at startup
 * so shared code can register client ticks and resource reload listeners without depending
 * on Fabric or NeoForge classes.
 */
public abstract class PlatformHooks {
	private static PlatformHooks INSTANCE;

	public static PlatformHooks get() {
		return INSTANCE;
	}

	/**
	 * Installs the platform implementation. Must be called before any client code runs.
	 * <p>
	 * Anything another mod registered before this point (see
	 * {@code AnimaApi.onWorldRender} / {@code onClientTick} / {@code onClientReload}) is
	 * registered now, so those hooks can never be lost to entrypoint ordering.
	 */
	public static void set(PlatformHooks hooks) {
		INSTANCE = hooks;
		anima.api.AnimaApi.flushPendingHooks();
	}

	/** Registers a listener invoked at the end of every client tick. */
	public abstract void addClientTickListener(Runnable runnable);

	/** Registers a client-side resource reload listener. */
	public abstract void registerClientReloadListener(ResourceLocation id, PreparableReloadListener listener);

	/** Registers a hook invoked during the world render pass (for world-space drawing). */
	public abstract void addWorldRenderListener(WorldRenderHook hook);
}
