package anima.text;

import java.util.List;

import anima.client.world.WorldText3D;

/**
 * 逐字文本动画的<b>运行时求值</b>：打字入场 / 打字出场 / 下落 / 飘入。
 * <p>
 * 每个字有自己的延迟与运动（按整段文字错峰），与编辑器里的预览共用同一份算法。用法是把剪辑列表
 * 交给 {@link #compute}，再把得到的 {@link WorldText3D.Glyph} 数组传给
 * {@code AnimaApi.drawWorldText3D(..., glyphs)}。
 *
 * <pre>{@code
 * List<CharClips.Clip> clips = List.of(
 *     new CharClips.Clip("char_drift_in", 0f, 300f),      // 飘入
 *     new CharClips.Clip("typewriter_out", 3300f, 1000f)); // 打字出场
 * WorldText3D.Glyph[] glyphs = AnimaApi.charGlyphs("42", clips, localMs);
 * }</pre>
 */
public final class CharClips {

	/** 一个逐字剪辑：在整段动画时间轴上的起点、时长（ms）与播放速度。 */
	public record Clip(String effect, float startMs, float durationMs, float speed) {
		public Clip(String effect, float startMs, float durationMs) {
			this(effect, startMs, durationMs, 1f);
		}
	}

	private CharClips() {
	}

	/** 该名字是否是逐字剪辑（打字入场/出场、下落、飘入）。 */
	public static boolean isCharClip(String key) {
		return switch (key) {
			case "typewriter_in", "typewriter_out", "char_fall_in", "char_fall_random_in", "char_drift_in" -> true;
			default -> false;
		};
	}

	/** 是否有剪辑在 {@code timeMs} 处于播放中。 */
	public static boolean isActive(List<Clip> clips, float timeMs) {
		if (clips == null) {
			return false;
		}
		for (Clip c : clips) {
			float local = (timeMs - c.startMs()) * c.speed();
			if (local >= 0 && local <= c.durationMs()) {
				return true;
			}
		}
		return false;
	}

	/** 叠加所有活跃剪辑后的逐字变换；没有活跃剪辑时返回 {@code null}（调用方按整段绘制）。 */
	public static WorldText3D.Glyph[] compute(String text, List<Clip> clips, float timeMs) {
		if (text == null || text.isEmpty() || clips == null || clips.isEmpty()) {
			return null;
		}
		WorldText3D.Glyph[] out = null;
		for (Clip c : clips) {
			if (!isCharClip(c.effect())) {
				continue;
			}
			float local = (timeMs - c.startMs()) * c.speed();
			if (local < 0 || local > c.durationMs()) {
				continue;
			}
			if (out == null) {
				out = new WorldText3D.Glyph[text.length()];
				for (int i = 0; i < out.length; i++) {
					out[i] = new WorldText3D.Glyph();
				}
			}
			applyClip(c.effect(), local, Math.max(1f, c.durationMs()), out);
		}
		return out;
	}

	/**
	 * 加入一个逐字剪辑的作用。字符依次开始（间隔 {@code dur / length}），动画像打字机一样扫过
	 * 整段文字——但每个字有自己的运动，而不是整块一起移动。
	 */
	private static void applyClip(String effect, float localMs, float durMs, WorldText3D.Glyph[] out) {
		int len = out.length;
		if (len == 0) {
			return;
		}
		boolean exit = "typewriter_out".equals(effect);
		// 单个字的运动时长 + 逐字出现的错峰步长
		boolean typewriter = exit || "typewriter_in".equals(effect);
		float anim;
		float delayStep;
		if (typewriter) {
			// 打字机类靠错峰体现"一个字一个字出现"：保留完整错峰，错峰必须在剪辑结束前
			// 收尾，否则打字出场会在剪辑结束时还留着字没走完
			anim = Math.max(90f, Math.min(420f, durMs / Math.max(1, len) * 3f));
			float span = Math.max(0f, durMs - anim);
			delayStep = len <= 1 ? 0f : span / (len - 1);
		} else {
			// 位移型（下落 / 随机下落 / 飘入）：把剪辑时长拆成「逐字出现的错峰」与
			// 「单字运动」两段，<b>两者都随动画时长等比变化</b> —— 否则改时长只改变运动
			// 快慢，逐字出现的节奏永远是同一个固定值。错峰只占 1/4（最多 500ms），
			// 免得字与字被位移拉开（"8.0" 看起来像 "8 . 0"）。
			float span = Math.min(durMs * 0.25f, 500f);
			delayStep = len <= 1 ? 0f : span / (len - 1);
			anim = Math.max(60f, durMs - delayStep * (len - 1));
		}
		for (int i = 0; i < len; i++) {
			int order = exit ? (len - 1 - i) : i; // 出场时最后一个字先走
			float delay = order * delayStep;
			float t = clamp01((localMs - delay) / anim);
			boolean started = localMs >= delay;
			float ease = easeOutCubic(t);
			WorldText3D.Glyph c = out[i];
			switch (effect) {
				case "char_fall_in", "char_fall_random_in" -> {
					boolean rnd = "char_fall_random_in".equals(effect);
					// 下落：从上方落下；随机下落：每个字的高度/横向偏移/倾斜都不同
					float startH = rnd ? 26f + prand(i, 7.3f) * 64f : 46f;
					float startX = rnd ? (prand(i, 3.7f) - 0.5f) * 20f : 0f;
					float fall = startH * (1f - ease) * (1f - ease); // 加速下落（越接近落点越快）
					c.dy -= fall;
					c.dx += (1f - ease) * startX;
					// 落下时带一点倾斜，落地前摆正（随机下落倾斜更明显）
					float tilt = rnd ? (prand(i, 5.1f) - 0.5f) * 0.5f : 0.10f;
					c.rot += (1f - ease) * tilt;
					c.alpha *= started ? clamp01(t * 1.6f) : 0f;
				}
				case "char_drift_in" -> {
					// 飘入：主要从上往下落到自己的目标位置（横向只带一点点偏移），"飘"的感觉由
					// 轻微摆动 + 单字旋转承担；t=1 时偏移精确归零。
					float rest = 1f - t;
					float drift = rest * rest; // 与落地同步衰减
					c.dx += -14f * drift + (float) Math.sin(t * Math.PI) * 2f * rest;
					c.dy += -30f * drift;
					c.rot += -0.34f * drift + (float) Math.sin(t * Math.PI * 1.6f) * 0.10f * rest;
					c.alpha *= started ? clamp01(t * 1.4f) : 0f;
				}
				case "typewriter_in" -> {
					// 单字跳动入场：先稍微放大并向右偏，再回到原位
					float pop = 1f - ease;
					c.scale *= 1f + 0.35f * pop;
					c.dx += 4f * pop;
					c.rot += 0.10f * pop;
					c.alpha *= started ? clamp01(t * 1.8f) : 0f;
				}
				case "typewriter_out" -> {
					// 单字跳动出场：放大 + 右移后淡出，从最后一个字开始
					c.scale *= 1f + 0.35f * ease;
					c.dx += 4f * ease;
					c.rot += -0.10f * ease;
					c.alpha *= started ? clamp01(1f - t) : 1f;
				}
				default -> {
				}
			}
		}
	}

	private static float easeOutCubic(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}

	/** 稳定的伪随机（同一个字每次求值结果一致，避免抖动）。 */
	private static float prand(int i, float seed) {
		double v = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453;
		return (float) (v - Math.floor(v));
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
