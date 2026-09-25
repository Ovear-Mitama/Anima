package anima.platform;

import net.minecraft.resources.Identifier;
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
	public abstract void registerClientReloadListener(Identifier id, PreparableReloadListener listener);

	/** Registers a hook invoked during the world render pass (for world-space drawing). */
	public abstract void addWorldRenderListener(WorldRenderHook hook);

	/**
	 * 编辑器在世界里打开期间，让原版移动键「有界面打开时也能被读到」。
	 * <p>
	 * NeoForge 把 {@code keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint} 的冲突上下文
	 * 设成 {@code IN_GAME}（见 {@code Options#setForgeKeybindProperties}），而
	 * {@code KeyMapping#isDown()} 会额外要求该上下文处于激活状态 —— 只要
	 * {@code Minecraft#screen} 非空就恒为 false。于是编辑器转发的 WASD
	 * （{@code KeyMapping#setDown(true)}）在 NeoForge 上完全无效：{@code KeyboardInput#tick}
	 * 读到的永远是「没按下」。这里在编辑器打开期间放宽上下文，关闭时还原。
	 * <p>
	 * Fabric 用的是原版 {@code isDown()}（只看字段），默认实现为空即可。
	 */
	public void allowMovementKeysInGui(boolean allow) {
	}
}
