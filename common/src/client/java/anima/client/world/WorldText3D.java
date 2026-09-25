package anima.client.world;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import anima.engine.RenderModifier;

/**
 * 真正的 3D 世界文本：在<b>世界渲染阶段</b>用世界坐标直接绘制（有透视、随距离缩放、会被方块
 * 遮挡），而不是把世界坐标投影到 2D HUD 上再画字。
 * <p>
 * 默认姿态与名字牌一致（{@code translate → cameraOrientation → scale(s, -s, s)}），所以文字
 * 始终正对相机但确实坐在世界里；每 1 GUI 像素 = {@code worldScale} 个方块。
 * <p>
 * 逐字动画（打字/下落/飘入…）通过传入 {@link Glyph} 数组实现：每个字有自己的位移 / 缩放 /
 * 绕字形中心旋转 / 透明度，和时间线编辑器里的表现一致。
 *
 * <pre>{@code
 * // 在世界渲染回调里（平台入口已把 buffers / camera 交给你）
 * WorldText3D.draw(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, WorldText3D.DEFAULT_SCALE, modifier);
 * }</pre>
 */
public final class WorldText3D {
	private WorldText3D() {
	}

	/** 默认像素→方块比例：1 GUI 像素 = 0.025 方块（9px 高的字约 0.22 方块，与名字牌相当）。 */
	public static final float DEFAULT_SCALE = 0.025f;

	/** 一个小小的高度偏移，让文字以锚点为中心（字形框高 9px）。 */
	private static final float GLYPH_HALF = 4.5f;

	/** 阴影偏移：与游戏内文字一致，右下各 1 GUI 像素（与正文共面，无 Z 偏移）。 */
	private static final float SHADOW_OFFSET = 1f;

	/** 阴影亮度：与游戏内文字一致，RGB ×0.25（alpha 不变）。 */
	private static final float SHADOW_DIM = 0.25f;

	/** 单个字形的变换（GUI 像素位移 / 额外缩放 / 绕字形中心旋转 / 透明度）。 */
	public static final class Glyph {
		public float dx;
		public float dy;
		public float scale = 1f;
		public float rot; // 弧度
		public float alpha = 1f;
	}

	/** 整段文本 + {@link RenderModifier}（位移 / 缩放 / 透明度 / 颜色）的便捷重载。 */
	public static boolean draw(MultiBufferSource buffers, Camera camera, String text,
			double x, double y, double z, int color, float worldScale, RenderModifier m) {
		float offX = m == null ? 0f : m.tx;
		float offY = m == null ? 0f : m.ty;
		float scaleMul = m == null ? 1f : m.sx;
		float alphaMul = m == null ? 1f : m.a;
		int rgb = m == null ? color : ((color >>> 24) << 24) | ((Math.round(clamp(m.r) * ((color >> 16) & 0xFF)) << 16)
			| (Math.round(clamp(m.g) * ((color >> 8) & 0xFF)) << 8) | Math.round(clamp(m.b) * (color & 0xFF)));
		return draw(buffers, camera, text, x, y, z, rgb, worldScale, offX, offY, scaleMul, null, alphaMul);
	}

	/**
	 * 把 {@code text} 画在世界的 {@code (x, y, z)} 处（billboard，面向相机）。
	 *
	 * @param glyphs   可选的逐字变换（null = 整段一起画）
	 * @param offX/offY 整段的像素位移；{@code scaleMul} 额外的整体缩放
	 * @param alphaMul 整体透明度系数（与字形自身的 alpha 相乘）
	 * @return 是否真的画了东西
	 */
	public static boolean draw(MultiBufferSource buffers, Camera camera, String text,
			double x, double y, double z, int color, float worldScale,
			float offX, float offY, float scaleMul, Glyph[] glyphs, float alphaMul) {
		if (buffers == null || camera == null || text == null || text.isEmpty() || worldScale <= 0f) {
			return false;
		}
		Font font = Minecraft.getInstance().font;
		Vec3 cam = camera.position();
		// 相机空间：世界坐标 - 相机位置，然后对齐相机朝向（与名字牌相同的姿态）
		// 整段缩放（scaleMul）作用在基础矩阵上：字与字的推进间距跟着一起缩放，和原版名字牌的
		// poseStack.scale 一致。若改成"按每个字各自中心"缩放，字会变小但间距不变（看起来像 "8 . 0"）。
		float s = Math.max(0.01f, scaleMul);
		Matrix4f base = new Matrix4f()
			.translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
			.rotate(camera.rotation())
			.scale(worldScale * s, -worldScale * s, worldScale * s);
		int baseA = (color >>> 24) & 0xFF;
		int rgb = color & 0x00FFFFFF;
		boolean drawn = false;
		float cx = -font.width(text) / 2f; // 以锚点为中心
		for (int i = 0; i < text.length(); i++) {
			Glyph g = glyphs != null && i < glyphs.length ? glyphs[i] : null;
			String ch = String.valueOf(text.charAt(i));
			float w = font.width(ch);
			float a = clamp((g == null ? 1f : g.alpha) * alphaMul);
			if (a > 0.02f) {
				float dx = (g == null ? 0f : g.dx) + offX;
				float dy = (g == null ? 0f : g.dy) + offY;
				float sc = (g == null ? 1f : g.scale);
				Matrix4f m = new Matrix4f(base);
				// 先在「像素空间」里移到该字形的中心，再做旋转 / 缩放（顺序：先缩放后旋转）
				m.translate(cx + w / 2f + dx, dy, 0f);
				if (sc != 1f) {
					m.scale(sc, sc, 1f);
				}
				if (g != null && g.rot != 0f) {
					m.rotateZ(g.rot);
				}
				int col = (Math.round(baseA * a) << 24) | rgb;
				// 交给原版单次 drawInBatch 的 dropShadow：原版会在同一缓冲内先画阴影、再画正文，
				// 顺序有保证（阴影永远在正文之下），并配合 POLYGON_OFFSET（glPolygonOffset(-1,-10)
				// 把整个字形深度偏向相机）提升远距离深度精度。
				// 阴影参与深度测试 → 会被挡在文字前的实体/方块正确遮挡，不再"穿墙/穿实体"。
				font.drawInBatch(ch, -w / 2f, -GLYPH_HALF, col, true, m, buffers,
					Font.DisplayMode.POLYGON_OFFSET, 0, LightCoordsUtil.FULL_BRIGHT);
				drawn = true;
			}
			cx += w;
		}
		return drawn;
	}

	/**
	 * {@link Component} 版：逐字用 {@link FormattedCharSequence} 绘制，样式（粗体、颜色等）保留。
	 * 参数含义与 {@link #draw(MultiBufferSource, Camera, String, double, double, double, int, float, float, float, float, Glyph[], float)} 一致。
	 */
	public static boolean draw(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, int color, float worldScale,
			float offX, float offY, float scaleMul, Glyph[] glyphs, float alphaMul) {
		return drawComponent(buffers, camera, text, x, y, z, color, worldScale, offX, offY, scaleMul, glyphs, alphaMul,
			Font.DisplayMode.NORMAL);
	}

	/**
	 * {@link #draw(MultiBufferSource, Camera, Component, double, double, double, int, float, float, float, float, Glyph[], float)}
	 * 的「穿透」版：{@code seeThrough = true} 时用 see-through 文字渲染类型（不做深度测试），
	 * 所以不会被方块 / 实体遮挡，永远盖在世界之上（伤害跳字这类不想被生物挡住的东西用它）。
	 */
	public static boolean draw(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, int color, float worldScale,
			float offX, float offY, float scaleMul, Glyph[] glyphs, float alphaMul, boolean seeThrough) {
		return drawComponent(buffers, camera, text, x, y, z, color, worldScale, offX, offY, scaleMul, glyphs, alphaMul,
			seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL);
	}

	private static boolean drawComponent(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, int color, float worldScale,
			float offX, float offY, float scaleMul, Glyph[] glyphs, float alphaMul, Font.DisplayMode mode) {
		if (buffers == null || camera == null || text == null || worldScale <= 0f) {
			return false;
		}
		String raw = text.getString();
		if (raw.isEmpty()) {
			return false;
		}
		Font font = Minecraft.getInstance().font;
		Style style = text.getStyle();
		Vec3 cam = camera.position();
		// 相机空间：世界坐标 - 相机位置，然后对齐相机朝向（与名字牌相同的姿态）
		float s = Math.max(0.01f, scaleMul);
		Matrix4f base = new Matrix4f()
			.translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
			.rotate(camera.rotation())
			.scale(worldScale * s, -worldScale * s, worldScale * s);
		// 逐字宽度（含样式带来的加粗宽度）先量好，才能把整段居中
		FormattedCharSequence[] chars = new FormattedCharSequence[raw.length()];
		float[] widths = new float[raw.length()];
		float total = 0f;
		for (int i = 0; i < raw.length(); i++) {
			chars[i] = FormattedCharSequence.forward(String.valueOf(raw.charAt(i)), style);
			widths[i] = font.width(chars[i]);
			total += widths[i];
		}
		int baseA = (color >>> 24) & 0xFF;
		int rgb = color & 0x00FFFFFF;
		boolean drawn = false;
		float cx = -total / 2f; // 以锚点为中心
		for (int i = 0; i < chars.length; i++) {
			Glyph g = glyphs != null && i < glyphs.length ? glyphs[i] : null;
			float w = widths[i];
			float a = clamp((g == null ? 1f : g.alpha) * alphaMul);
			if (a > 0.02f) {
				float dx = (g == null ? 0f : g.dx) + offX;
				float dy = (g == null ? 0f : g.dy) + offY;
				float sc = (g == null ? 1f : g.scale);
				Matrix4f m = new Matrix4f(base);
				m.translate(cx + w / 2f + dx, dy, 0f);
				if (sc != 1f) {
					m.scale(sc, sc, 1f);
				}
				if (g != null && g.rot != 0f) {
					m.rotateZ(g.rot);
				}
				int col = (Math.round(baseA * a) << 24) | rgb;
				if (mode == Font.DisplayMode.SEE_THROUGH) {
					// 穿透通道：阴影与正文用同一个渲染类型（都不做深度测试）。
					// 阴影若参与深度测试，文字穿墙可见时阴影会被前面的方块/实体吃掉，
					// 看起来就是"跳字在墙后没了阴影"；两者同为 see-through 才能始终成对出现。
					// 阴影先提交、正文后提交，在同一缓冲里顺序有保证 → 正文稳定压在阴影之上；
					// 1px 平面偏移 + 零 Z 位移保证两者共面，不会 z-fighting。
					font.drawInBatch(chars[i], -w / 2f, -GLYPH_HALF, shadowColor(col), false,
						new Matrix4f(m).translate(SHADOW_OFFSET, SHADOW_OFFSET, 0f), buffers,
						Font.DisplayMode.SEE_THROUGH, 0, LightCoordsUtil.FULL_BRIGHT);
					font.drawInBatch(chars[i], -w / 2f, -GLYPH_HALF, col, false, m, buffers,
						mode, 0, LightCoordsUtil.FULL_BRIGHT);
				} else {
					// 常规通道只补画正文（不带阴影）：它是与穿透通道配套的"遮挡"补画，
					// 若再画阴影，阴影会落在穿透正文之后/与正文共面，重现 z-fighting 或压暗。
					font.drawInBatch(chars[i], -w / 2f, -GLYPH_HALF, col, false, m, buffers,
						mode, 0, LightCoordsUtil.FULL_BRIGHT);
				}
				drawn = true;
			}
			cx += w;
		}
		return drawn;
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/** 阴影色：与原版 dropShadow 一致，RGB ×0.25，alpha 不变。 */
	private static int shadowColor(int argb) {
		return (argb & 0xFF000000)
			| (Math.round(((argb >> 16) & 0xFF) * SHADOW_DIM) << 16)
			| (Math.round(((argb >> 8) & 0xFF) * SHADOW_DIM) << 8)
			| Math.round((argb & 0xFF) * SHADOW_DIM);
	}
}
