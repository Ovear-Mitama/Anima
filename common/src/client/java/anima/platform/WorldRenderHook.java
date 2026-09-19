package anima.platform;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Callback invoked once per frame during the world render pass, so client code can draw things
 * in world space (the pose stack already contains the camera transform). Platform entrypoints
 * wire this to their own world-render event.
 */
@FunctionalInterface
public interface WorldRenderHook {
	void render(PoseStack pose, MultiBufferSource buffers, Camera camera, float partialTick);
}
