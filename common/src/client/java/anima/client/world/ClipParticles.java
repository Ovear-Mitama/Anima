package anima.client.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 把编辑器导出的「粒子剪辑」JSON 播放成<b>真实的世界 3D 粒子</b>。
 * <p>
 * 剪辑格式与编辑器导出的完全一致（{@code effect} 为 {@code particle_3d} / {@code particle_group}）：
 * <pre>{@code
 * {"effect":"particle_3d","start":0,"duration":800,"speed":1,
 *  "particle":"minecraft:flame",
 *  "params":"{\"count\":12,\"dx\":0.2,\"dy\":0.2,\"dz\":0.2,\"speed\":0.01,\"ox\":0,\"oy\":0,\"oz\":0}"}
 * }</pre>
 * 规则与编辑器预览相同：<b>数量是整条剪辑的总量</b>，粒子在剪辑窗口内按进度陆续发出（剪辑越短爆发越急），
 * 发射点是对象位置 + 剪辑自身的偏移 {@code ox/oy/oz}，之后由原版粒子引擎自行模拟，不跟随文本移动。
 * <p>
 * 用法：{@link #parse} 得到可复用的模板（配置不变就不用重复解析），每个动画实例
 * （一次跳字、一个预览对象）用 {@link #newPlayer()} 建一个播放器，之后用同一个毫秒时钟
 * 调用 {@link Player#emit}；时钟回退（重播）时会自动清零重发。
 */
public final class ClipParticles {

	/** 一条解析好的粒子剪辑（{@code slots} = 需要多少个发射计数：分组按子项数量）。 */
	private record Clip(String effect, float startMs, float durationMs, float speed, String id, JsonObject params,
			int slots) {
	}

	private final List<Clip> clips;

	private ClipParticles(List<Clip> clips) {
		this.clips = clips;
	}

	/** 解析编辑器导出的剪辑 JSON；文本 / 逐字剪辑会被忽略。 */
	public static ClipParticles parse(JsonArray json) {
		List<Clip> out = new ArrayList<>();
		if (json != null) {
			for (JsonElement el : json) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				String effect = o.has("effect") ? o.get("effect").getAsString() : "";
				if (!effect.startsWith("particle_")) {
					continue;
				}
				JsonObject params = parseParams(o);
				boolean group = "particle_group".equals(effect);
				String id = o.has("particle") ? o.get("particle").getAsString() : "";
				if (id.isBlank()) {
					id = group ? firstItemId(params) : "minecraft:flame";
				}
				out.add(new Clip(effect,
					o.has("start") ? o.get("start").getAsFloat() : 0f,
					o.has("duration") ? o.get("duration").getAsFloat() : 1000f,
					o.has("speed") ? o.get("speed").getAsFloat() : 1f,
					id, params, group ? Math.max(1, groupItems(params).size()) : 1));
			}
		}
		return new ClipParticles(out);
	}

	/** 没有任何粒子剪辑时为 true（调用方可以据此完全不建播放器）。 */
	public boolean isEmpty() {
		return clips.isEmpty();
	}

	/** 建一个属于某个动画实例的播放器（自带发射计数）。 */
	public Player newPlayer() {
		return new Player();
	}

	/** 一次播放：每个动画实例一个，计数器互不干扰。 */
	public final class Player {
		private final int[][] done;
		private float lastTimeMs = -1f;

		private Player() {
			done = new int[clips.size()][];
			for (int i = 0; i < clips.size(); i++) {
				done[i] = new int[clips.get(i).slots()];
			}
		}

		/** 让所有计数归零（时钟回退时由 {@link #emit} 自动调用，也可手动调用重播）。 */
		public void restart() {
			for (int[] counters : done) {
				Arrays.fill(counters, 0);
			}
			lastTimeMs = -1f;
		}

		/**
		 * 在对象位置 {@code (x, y, z)} 处、时钟 {@code timeMs}（毫秒，从动画开始算）处
		 * 发射已经到期的粒子。可以每帧调用；同一时刻重复调用不会重复发射。
		 */
		public void emit(double x, double y, double z, float timeMs) {
			if (timeMs < lastTimeMs) {
				restart();
			}
			lastTimeMs = timeMs;
			for (int i = 0; i < clips.size(); i++) {
				Clip c = clips.get(i);
				int[] counters = done[i];
				float local = (timeMs - c.startMs()) * c.speed();
				float dur = Math.max(1f, c.durationMs());
				if (local < 0f || local > dur) {
					Arrays.fill(counters, 0); // 窗口之外 → 再次进入时会重放
					continue;
				}
				double ox = num(c.params(), "ox", 0);
				double oy = num(c.params(), "oy", 0);
				double oz = num(c.params(), "oz", 0);
				double px = x + ox;
				double py = y + oy;
				double pz = z + oz;
				if ("particle_group".equals(c.effect())) {
					// 分组的每个子项有自己的窗口 [s, s+d] 与自己的数量 / 散布 / 速度
					JsonArray items = groupItems(c.params());
					for (int k = 0; k < items.size() && k < counters.length; k++) {
						JsonElement el = items.get(k);
						if (!el.isJsonObject()) {
							continue;
						}
						JsonObject item = el.getAsJsonObject();
						if (!item.has("id")) {
							continue;
						}
						float s = (float) num(item, "s", 0);
						float d = (float) Math.max(16.0,
							Math.min(num(item, "d", dur), Math.max(16.0, dur - s)));
						float itemLocal = local - s;
						if (itemLocal < 0f || itemLocal > d) {
							counters[k] = 0;
							continue;
						}
						counters[k] += emitDue(item.get("id").getAsString(), px, py, pz,
							(int) Math.round(num(item, "count", 1)),
							num(item, "dx", 0), num(item, "dy", 0), num(item, "dz", 0),
							num(item, "speed", 0), itemLocal / d, counters, k);
					}
					continue;
				}
				counters[0] += emitDue(c.id(), px, py, pz,
					(int) Math.round(num(c.params(), "count", 1)),
					num(c.params(), "dx", 0), num(c.params(), "dy", 0), num(c.params(), "dz", 0),
					num(c.params(), "speed", 0), local / dur, counters, 0);
			}
		}
	}

	/** 只发射在剪辑 {@code progress} 处「到期」的粒子（总量 = 数量）。总量为 1 时立即发射其唯一粒子。 */
	private static int emitDue(String id, double x, double y, double z, int total,
			double deltaX, double deltaY, double deltaZ, double speed, float progress, int[] done, int idx) {
		if (total <= 0 || progress <= 0f) {
			return 0;
		}
		int want = Math.max(1, (int) Math.floor(total * progress));
		int missing = Math.max(0, want - done[idx]);
		if (missing <= 0) {
			return 0;
		}
		int n = Math.min(missing, 8); // 每帧最多几个，使爆发保持平滑
		return WorldParticles.emit(id, x, y, z, n, deltaX, deltaY, deltaZ, speed);
	}

	/** 剪辑的 {@code params} 是一个 JSON <b>字符串</b>（编辑器这样存的），这里解析成对象。 */
	private static JsonObject parseParams(JsonObject clip) {
		if (clip.has("params") && clip.get("params").isJsonPrimitive()) {
			try {
				JsonElement el = JsonParser.parseString(clip.get("params").getAsString());
				if (el.isJsonObject()) {
					return el.getAsJsonObject();
				}
			} catch (Exception ignored) {
				// 写坏了就当没有参数，用默认值
			}
		}
		return new JsonObject();
	}

	private static JsonArray groupItems(JsonObject params) {
		if (params != null && params.has("items") && params.get("items").isJsonArray()) {
			return params.getAsJsonArray("items");
		}
		return new JsonArray();
	}

	private static String firstItemId(JsonObject params) {
		JsonArray items = groupItems(params);
		if (items.size() > 0 && items.get(0).isJsonObject()) {
			JsonElement id = items.get(0).getAsJsonObject().get("id");
			if (id != null) {
				return id.getAsString();
			}
		}
		return "minecraft:flame";
	}

	private static double num(JsonObject o, String key, double dflt) {
		if (o == null || !o.has(key)) {
			return dflt;
		}
		try {
			return o.get(key).getAsDouble();
		} catch (Exception ignored) {
			return dflt;
		}
	}
}
