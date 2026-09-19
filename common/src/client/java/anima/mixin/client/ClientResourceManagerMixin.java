package anima.mixin.client;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import anima.manager.AnimatedTextureManager;

/**
 * Fabric-only registration of the {@link net.minecraft.server.packs.PackResources} overlay
 * that synthesizes animation {@code .mcmeta} files. Pushes the shared virtual pack to the top
 * of every client-resource namespace manager (re-run on every reload because the manager is
 * rebuilt on reload).
 */
@Mixin(MultiPackResourceManager.class)
public class ClientResourceManagerMixin {
	@Shadow
	private Map<String, FallbackResourceManager> namespacedManagers;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void tal$pushVirtualAnimationPack(PackType type, List<PackResources> packs, CallbackInfo ci) {
		if (type != PackType.CLIENT_RESOURCES) {
			return;
		}
		PackResources virtualPack = AnimatedTextureManager.get().getVirtualPack();
		for (FallbackResourceManager manager : namespacedManagers.values()) {
			manager.push(virtualPack);
		}
	}
}
