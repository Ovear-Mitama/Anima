package anima.manager;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

import anima.engine.PlayMode;

/**
 * A parsed animation definition (from JSON or registered programmatically).
 * <p>
 * JSON resource-pack format ({@code assets/<modid>/texture_animations/<name>.json}):
 * <pre>
 * {
 *   "type": "gui_sprite" | "gui_dynamic" | "world_sprite" | "text",
 *   "texture": "anima:textures/gui/demo.png",   // gui_sprite sheet / gui_dynamic base / world_sprite target sprite
 *   "frames": [[u,v,w,h], ...],            // gui_sprite UV frames
 *   "frame_textures": ["ns:path", ...],    // gui_dynamic: separate frame images
 *   "frame_indices": [0,1,2],              // world_sprite: frame indices
 *   "frame_time": 50,                      // ms per frame
 *   "interpolate": false,
 *   "loop": true,
 *   "play_mode": "loop" | "once" | "pingpong",
 *   "texture_width": 16, "texture_height": 16,
 *   "effects": [ {"type":"blink","period":500}, ... ]
 * }
 * </pre>
 */
public final class AnimationDefinition {
	private final Identifier id;
	private final AnimationType type;
	private final Identifier texture;
	private final List<FrameSpec> frames;
	private final List<Identifier> frameTextures;
	private final int[] frameIndices;
	private final int frameTimeMs;
	private final boolean interpolate;
	private final boolean loop;
	private final PlayMode playMode;
	private final int textureWidth;
	private final int textureHeight;
	private final String displayName;
	private final List<EffectSpec> effects;

	private AnimationDefinition(Identifier id, AnimationType type, Identifier texture,
			List<FrameSpec> frames, List<Identifier> frameTextures, int[] frameIndices,
			int frameTimeMs, boolean interpolate, boolean loop, PlayMode playMode,
			int textureWidth, int textureHeight, List<EffectSpec> effects, String displayName) {
		this.id = id;
		this.type = type;
		this.texture = texture;
		this.frames = List.copyOf(frames);
		this.frameTextures = List.copyOf(frameTextures);
		this.frameIndices = frameIndices == null ? new int[0] : frameIndices.clone();
		this.frameTimeMs = Math.max(frameTimeMs, 1);
		this.interpolate = interpolate;
		this.loop = loop;
		this.playMode = playMode == null ? PlayMode.LOOP : playMode;
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
		this.displayName = displayName;
		this.effects = List.copyOf(effects);
	}

	public static AnimationDefinition fromJson(Identifier id, JsonObject json) {
		AnimationType type = AnimationType.fromString(optString(json, "type", "gui_sprite"));
		Identifier texture = parseLocation(json, "texture", id.getNamespace());

		List<FrameSpec> frames = new ArrayList<>();
		if (json.has("frames") && json.get("frames").isJsonArray()) {
			for (JsonElement el : json.getAsJsonArray("frames")) {
				if (el.isJsonArray()) {
					JsonArray arr = el.getAsJsonArray();
					frames.add(new FrameSpec(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(),
						arr.get(2).getAsFloat(), arr.get(3).getAsFloat()));
				} else if (el.isJsonObject()) {
					JsonObject o = el.getAsJsonObject();
					frames.add(new FrameSpec(
						optFloat(o, "u", 0f), optFloat(o, "v", 0f),
						optFloat(o, "w", 16f), optFloat(o, "h", 16f)));
				}
			}
		}

		List<Identifier> frameTextures = new ArrayList<>();
		if (json.has("frame_textures") && json.get("frame_textures").isJsonArray()) {
			for (JsonElement el : json.getAsJsonArray("frame_textures")) {
				frameTextures.add(Identifier.parse(el.getAsString()));
			}
		}

		int[] frameIndices = new int[0];
		if (json.has("frame_indices") && json.get("frame_indices").isJsonArray()) {
			JsonArray arr = json.getAsJsonArray("frame_indices");
			frameIndices = new int[arr.size()];
			for (int i = 0; i < arr.size(); i++) {
				frameIndices[i] = arr.get(i).getAsInt();
			}
		}

		boolean loop = optBool(json, "loop", true);
		PlayMode playMode = PlayMode.fromString(optString(json, "play_mode", loop ? "loop" : "once"));

		List<EffectSpec> effects = new ArrayList<>();
		if (json.has("effects") && json.get("effects").isJsonArray()) {
			for (JsonElement el : json.getAsJsonArray("effects")) {
				if (el.isJsonObject()) {
					JsonObject o = el.getAsJsonObject();
					String name = o.has("type") ? o.get("type").getAsString() : "fade";
					effects.add(new EffectSpec(name, o));
				}
			}
		}

		return new AnimationDefinition(id, type, texture, frames, frameTextures, frameIndices,
			optInt(json, "frame_time", 50), optBool(json, "interpolate", false), loop, playMode,
			optInt(json, "texture_width", 16), optInt(json, "texture_height", 16), effects,
			optString(json, "name", null));
	}

	/** Serializes this definition back to JSON (the counterpart of {@link #fromJson}). */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("type", type.name().toLowerCase());
		if (displayName != null) {
			json.addProperty("name", displayName);
		}
		if (texture != null) {
			json.addProperty("texture", texture.toString());
		}
		if (!frames.isEmpty()) {
			JsonArray arr = new JsonArray();
			for (FrameSpec f : frames) {
				JsonArray fv = new JsonArray();
				fv.add(f.u());
				fv.add(f.v());
				fv.add(f.w());
				fv.add(f.h());
				arr.add(fv);
			}
			json.add("frames", arr);
		}
		if (!frameTextures.isEmpty()) {
			JsonArray arr = new JsonArray();
			for (Identifier ft : frameTextures) {
				arr.add(ft.toString());
			}
			json.add("frame_textures", arr);
		}
		if (frameIndices.length > 0) {
			JsonArray arr = new JsonArray();
			for (int i : frameIndices) {
				arr.add(i);
			}
			json.add("frame_indices", arr);
		}
		if (frameTimeMs != 50) {
			json.addProperty("frame_time", frameTimeMs);
		}
		if (interpolate) {
			json.addProperty("interpolate", true);
		}
		json.addProperty("loop", loop);
		if (playMode != PlayMode.LOOP) {
			json.addProperty("play_mode", playMode.name().toLowerCase());
		}
		if (textureWidth != 16) {
			json.addProperty("texture_width", textureWidth);
		}
		if (textureHeight != 16) {
			json.addProperty("texture_height", textureHeight);
		}
		if (!effects.isEmpty()) {
			JsonArray arr = new JsonArray();
			for (EffectSpec spec : effects) {
				arr.add(spec.config().deepCopy());
			}
			json.add("effects", arr);
		}
		return json;
	}

	/** Builder for programmatic registration. */
	public static Builder builder(Identifier id, AnimationType type) {
		return new Builder(id, type);
	}

	// ------------------------------------------------------------------ accessors

	public Identifier id() {
		return id;
	}

	public AnimationType type() {
		return type;
	}

	public Identifier texture() {
		return texture;
	}

	/** Optional Chinese/display name from the {@code name} JSON field (may be null). */
	public String displayName() {
		return displayName;
	}

	public List<FrameSpec> frames() {
		return frames;
	}

	public List<Identifier> frameTextures() {
		return frameTextures;
	}

	public int[] frameIndices() {
		return frameIndices;
	}

	public int frameTimeMs() {
		return frameTimeMs;
	}

	public boolean interpolate() {
		return interpolate;
	}

	public boolean loop() {
		return loop;
	}

	public PlayMode playMode() {
		return playMode;
	}

	public int textureWidth() {
		return textureWidth;
	}

	public int textureHeight() {
		return textureHeight;
	}

	public List<EffectSpec> effects() {
		return effects;
	}

	// ------------------------------------------------------------------ helpers

	private static String optString(JsonObject json, String key, String def) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : def;
	}

	private static boolean optBool(JsonObject json, String key, boolean def) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : def;
	}

	private static int optInt(JsonObject json, String key, int def) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : def;
	}

	private static float optFloat(JsonObject json, String key, float def) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsFloat() : def;
	}

	private static Identifier parseLocation(JsonObject json, String key, String defNamespace) {
		String s = optString(json, key, null);
		if (s == null) {
			return null;
		}
		if (s.contains(":")) {
			return Identifier.parse(s);
		}
		return Identifier.fromNamespaceAndPath(defNamespace, s);
	}

	public static final class Builder {
		private final Identifier id;
		private final AnimationType type;
		private Identifier texture;
		private final List<FrameSpec> frames = new ArrayList<>();
		private final List<Identifier> frameTextures = new ArrayList<>();
		private int[] frameIndices;
		private int frameTimeMs = 50;
		private boolean interpolate;
		private boolean loop = true;
		private PlayMode playMode = PlayMode.LOOP;
		private int textureWidth = 16;
		private int textureHeight = 16;
		private String displayName;
		private final List<EffectSpec> effects = new ArrayList<>();

		private Builder(Identifier id, AnimationType type) {
			this.id = id;
			this.type = type;
		}

		public Builder texture(Identifier texture) {
			this.texture = texture;
			return this;
		}

		public Builder addFrame(float u, float v, float w, float h) {
			this.frames.add(new FrameSpec(u, v, w, h));
			return this;
		}

		public Builder addFrameTexture(Identifier rl) {
			this.frameTextures.add(rl);
			return this;
		}

		public Builder frameIndices(int... indices) {
			this.frameIndices = indices;
			return this;
		}

		public Builder frameTimeMs(int ms) {
			this.frameTimeMs = Math.max(ms, 1);
			return this;
		}

		public Builder interpolate(boolean interpolate) {
			this.interpolate = interpolate;
			return this;
		}

		public Builder loop(boolean loop) {
			this.loop = loop;
			return this;
		}

		public Builder playMode(PlayMode playMode) {
			this.playMode = playMode;
			return this;
		}

		public Builder textureSize(int w, int h) {
			this.textureWidth = w;
			this.textureHeight = h;
			return this;
		}

		public Builder addEffect(String name, JsonObject config) {
			this.effects.add(new EffectSpec(name, config == null ? new JsonObject() : config));
			return this;
		}

		public Builder displayName(String name) {
			this.displayName = name;
			return this;
		}

		public AnimationDefinition build() {
			return new AnimationDefinition(id, type, texture, frames, frameTextures, frameIndices,
				frameTimeMs, interpolate, loop, playMode, textureWidth, textureHeight, effects, displayName);
		}
	}
}
