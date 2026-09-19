package anima.fabric;

import net.fabricmc.api.ModInitializer;

import anima.engine.AnimationEngine;

/** Fabric entrypoint. Registers built-in interpolators and effects. */
public class AnimaFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		AnimationEngine.get().init();
	}
}
