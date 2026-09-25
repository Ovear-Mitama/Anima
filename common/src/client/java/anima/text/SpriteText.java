package anima.text;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import anima.engine.RenderModifier;

/**
 * 纹理文字（字形图集）：把一张贴图按 {@code cellW×cellH} 切成格子（例如数字 0-9、符号），
 * 再用格子横向拼接出任意字符串 —— 用于「自定义图片拼数字」的伤害数字 / 跳字等场景。
 * <p>
 * 它直接复用本库的动画数据：传入 {@link RenderModifier}（或 {@link TextAnimationSpec} 与
 * 本地时钟 / 时长）即可获得位移、缩放、透明度与颜色动画，和字体文字的行为一致。
 * <p>
 * 贴图布局约定：第 {@code i} 个字符对应第 {@code i} 格，按行优先排列
 * （{@code columns} 列一行）；因此贴图宽 = {@code columns × cellW}，
 * 高 = {@code 行数 × cellH}。
 *
 * <pre>{@code
 * // 例：一张 10 格的数字图集，用本库的动画（DE 式跳字）
 * SpriteText.Atlas digits = SpriteText.Atlas.of(
 *     Identifier.parse("mymod:textures/gui/digits.png"), 8, 12, "0123456789");
 * SpriteText.draw(g, digits, String.valueOf(damage), x, y, 0xFFFFFFFF, spec, localMs, 1000f);
 * }</pre>
 */
public final class SpriteText {

	private SpriteText() {
	}

	/**
	 * 字形图集。{@code charset} 里第 i 个字符使用第 i 格；{@code scale} 是整体缩放
	 * （图集美术尺寸→屏幕尺寸的基准倍率）。
	 */
	public record Atlas(Identifier texture, int cellW, int cellH, int columns, String charset, float scale) {

		/** 常用构造：格子按 charset 长度自动一行排布，缩放 1×。 */
		public static Atlas of(Identifier texture, int cellW, int cellH, String charset) {
			String cs = charset == null || charset.isEmpty() ? "0123456789" : charset;
			return new Atlas(texture, Math.max(1, cellW), Math.max(1, cellH),
				Math.max(1, cs.length()), cs, 1f);
		}

		/** 从 JSON 读取：{@code {texture, cell_width, cell_height, columns, charset, scale}}。 */
		public static Atlas fromJson(JsonObject json) {
			if (json == null || !json.has("texture")) {
				return null;
			}
			String cs = json.has("charset") ? json.get("charset").getAsString() : "0123456789";
			int cellW = json.has("cell_width") ? json.get("cell_width").getAsInt() : 8;
			int cellH = json.has("cell_height") ? json.get("cell_height").getAsInt() : 8;
			int columns = json.has("columns") ? json.get("columns").getAsInt() : Math.max(1, cs.length());
			float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1f;
			return new Atlas(Identifier.parse(json.get("texture").getAsString()),
				Math.max(1, cellW), Math.max(1, cellH), Math.max(1, columns), cs, scale);
		}

		/** 图集贴图宽度（列数 × 格宽）。 */
		public int textureWidth() {
			return columns * cellW;
		}

		/** 图集贴图高度（行数 × 格高）。 */
		public int textureHeight() {
			return Math.max(1, (charset.length() + columns - 1) / columns) * cellH;
		}
	}

	/** 字符在图集中的格子序号，找不到时返回 -1（该字符留空）。 */
	public static int indexOf(Atlas atlas, char ch) {
		return atlas == null ? -1 : atlas.charset().indexOf(ch);
	}

	/** 字符串绘制后的宽度（图集单位，未乘 {@code scale}）。 */
	public static float width(Atlas atlas, String text) {
		if (atlas == null || text == null) {
			return 0f;
		}
		return (float) text.length() * atlas.cellW() * atlas.scale();
	}

	/** 以 (x, y) 为左上角绘制，{@code modifier} 提供位移/缩放/颜色/透明度（可为 null）。 */
	public static void draw(GuiGraphicsExtractor g, Atlas atlas, String text, float x, float y, int color, RenderModifier m) {
		if (atlas == null || text == null || text.isEmpty()) {
			return;
		}
		float r = 1f, gg = 1f, b = 1f, a = 1f;
		float tx = 0f, ty = 0f, sx = 1f, sy = 1f;
		if (m != null) {
			r = m.r;
			gg = m.g;
			b = m.b;
			a = m.a;
			tx = m.tx;
			ty = m.ty;
			sx = m.sx;
			sy = m.sy;
		}
		float baseA = ((color >>> 24) & 0xFF) / 255f;
		float baseR = ((color >> 16) & 0xFF) / 255f;
		float baseG = ((color >> 8) & 0xFF) / 255f;
		float baseB = (color & 0xFF) / 255f;
		float k = atlas.scale();
		// 26.1 起 GUI 没有全局 setColor，改为把染色直接传给 blit
		int tint = argb(baseA * a, baseR * r, baseG * gg, baseB * b);
		g.pose().pushMatrix();
		g.pose().translate(x + tx, y + ty);
		g.pose().scale(k * sx, k * sy);
		int texW = atlas.textureWidth();
		int texH = atlas.textureHeight();
		float cx = 0f;
		for (int i = 0; i < text.length(); i++) {
			int idx = indexOf(atlas, text.charAt(i));
			if (idx < 0) {
				cx += atlas.cellW();
				continue;
			}
			int u = (idx % atlas.columns()) * atlas.cellW();
			int v = (idx / atlas.columns()) * atlas.cellH();
			g.blit(RenderPipelines.GUI_TEXTURED, atlas.texture(), Math.round(cx), 0,
				(float) u, (float) v, atlas.cellW(), atlas.cellH(), texW, texH, tint);
			cx += atlas.cellW();
		}
		g.pose().popMatrix();
	}

	/** 把 0-1 的分量系数打包成 ARGB（越界值夹紧）。 */
	private static int argb(float a, float r, float g, float b) {
		return (Math.round(clamp01(a) * 255f) << 24) | (Math.round(clamp01(r) * 255f) << 16)
			| (Math.round(clamp01(g) * 255f) << 8) | Math.round(clamp01(b) * 255f);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/** 用动画规格 + 本地时钟 / 片段时长绘制（与 {@code TextAnimations.draw} 的时长语义一致）。 */
	public static void draw(GuiGraphicsExtractor g, Atlas atlas, String text, float x, float y, int color,
			TextAnimationSpec spec, float localMs, float durationMs) {
		RenderModifier m = spec == null ? null : spec.computeModifier(localMs, durationMs);
		draw(g, atlas, text, x, y, color, m);
	}
}
