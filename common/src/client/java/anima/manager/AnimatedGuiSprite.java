package anima.manager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import anima.Anima;
import anima.api.IAnimationEffect;
import anima.engine.AnimationEngine;
import anima.engine.EffectInstance;
import anima.engine.RenderModifier;

/**
 * A single animated GUI sprite instance. For {@code gui_sprite} definitions it blits UV
 * regions of an existing sheet texture; for {@code gui_dynamic} definitions it lazily stacks
 * the configured frame images into one {@link DynamicTexture}. Effects are evaluated against
 * the engine's global clock.
 */
public final class AnimatedGuiSprite {
	private final AnimationDefinition def;
	private final float startMs;
	private final List<EffectInstance> effects;
	private final ResourceLocation blitTexture;

	private List<FrameSpec> frames;
	private DynamicTexture dynamicTexture;
	private boolean resolved;
	private boolean closed;

	public AnimatedGuiSprite(AnimationDefinition def, float startMs) {
		this.def = def;
		this.startMs = startMs;
		this.effects = resolveEffects(def);
		this.blitTexture = def.type() == AnimationType.GUI_DYNAMIC
			? Anima.id("dynamic/" + def.id().getPath())
			: def.texture();
	}

	/** The current frame, or {@code null} if there are no frames. */
	public FrameSpec currentFrame(float timeMs) {
		ensureResolved();
		if (frames == null || frames.isEmpty()) {
			return null;
		}
		if (frames.size() == 1) {
			return frames.get(0);
		}
		float local = Math.max(0f, timeMs - startMs);
		int idx;
		if (def.loop()) {
			idx = (int) (local / def.frameTimeMs()) % frames.size();
		} else {
			idx = Math.min((int) (local / def.frameTimeMs()), frames.size() - 1);
		}
		return frames.get(Math.max(idx, 0));
	}

	/** Computes the combined render modifier from all effects at {@code timeMs}. */
	public RenderModifier computeModifier(float timeMs) {
		float local = Math.max(0f, timeMs - startMs);
		RenderModifier.Builder builder = RenderModifier.builder();
		for (EffectInstance effect : effects) {
			effect.effect().apply(local / 1000f, effect.durationMs() / 1000f,
				AnimatedTextureManager.get().random(), builder);
		}
		return builder.build();
	}

	/** Draws the current frame at {@code (x, y)} with size {@code (w, h)}. */
	public void draw(GuiGraphics guiGraphics, int x, int y, int w, int h, float timeMs) {
		FrameSpec frame = currentFrame(timeMs);
		if (frame == null || closed) {
			return;
		}
		RenderModifier m = computeModifier(timeMs);
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(x + m.tx, y + m.ty, 0f);
		guiGraphics.pose().scale(m.sx, m.sy, 1f);
		guiGraphics.setColor(m.r, m.g, m.b, m.a);
		guiGraphics.blit(blitTexture, 0, 0, w, h,
			frame.u() + m.uvU, frame.v() + m.uvV,
			(int) frame.w(), (int) frame.h(),
			(int) def.textureWidth(), (int) def.textureHeight());
		guiGraphics.setColor(1f, 1f, 1f, 1f);
		guiGraphics.pose().popPose();
	}

	/** Releases the underlying dynamic texture. Safe to call multiple times. */
	public void close() {
		if (dynamicTexture != null) {
			Minecraft.getInstance().getTextureManager().release(blitTexture);
			dynamicTexture.close();
			dynamicTexture = null;
		}
		closed = true;
	}

	private void ensureResolved() {
		if (resolved) {
			return;
		}
		resolved = true;
		if (def.type() == AnimationType.GUI_DYNAMIC) {
			frames = buildDynamicFrames();
		} else {
			frames = def.frames();
		}
	}

	private List<FrameSpec> buildDynamicFrames() {
		if (def.frameTextures().isEmpty()) {
			return List.of();
		}
		Minecraft mc = Minecraft.getInstance();
		NativeImage[] images = new NativeImage[def.frameTextures().size()];
		int w = 0;
		int h = 0;
		try {
			for (int i = 0; i < images.length; i++) {
				ResourceLocation rl = def.frameTextures().get(i);
				try (InputStream is = mc.getResourceManager().open(rl)) {
					images[i] = NativeImage.read(is);
				}
				if (i == 0) {
					w = images[0].getWidth();
					h = images[0].getHeight();
				}
			}

			NativeImage stacked = new NativeImage(w, h * images.length, false);
			for (int i = 0; i < images.length; i++) {
				for (int x = 0; x < w; x++) {
					for (int y = 0; y < h; y++) {
						stacked.setPixelRGBA(x, y + i * h, images[i].getPixelRGBA(x, y));
					}
				}
				images[i].close();
			}

			dynamicTexture = new DynamicTexture(stacked);
			mc.getTextureManager().register(blitTexture, dynamicTexture);
			dynamicTexture.upload();

			List<FrameSpec> specs = new ArrayList<>(images.length);
			for (int i = 0; i < images.length; i++) {
				specs.add(new FrameSpec(0f, i * h, w, h));
			}
			return specs;
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to build animated GUI texture for {}", def.id(), e);
			closePartial(images, images.length);
			return List.of();
		}
	}

	private static void closePartial(NativeImage[] images, int upTo) {
		for (int i = 0; i < upTo; i++) {
			if (images[i] != null) {
				images[i].close();
			}
		}
	}

	private static List<EffectInstance> resolveEffects(AnimationDefinition def) {
		List<EffectInstance> list = new ArrayList<>(def.effects().size());
		for (EffectSpec spec : def.effects()) {
			IAnimationEffect effect = AnimationEngine.get().createEffect(spec.name(), spec.config());
			if (effect != null) {
				list.add(new EffectInstance(effect, 0f, 0f));
			} else {
				Anima.LOGGER.warn("Unknown animation effect '{}' in {}", spec.name(), def.id());
			}
		}
		return list;
	}
}
