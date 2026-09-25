package anima.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import anima.Anima;
import anima.client.lang.L10n;

/**
 * Persists user-edited animation definitions to the Minecraft config directory
 * ({@code config/anima/animations/}). Overrides registered here win over
 * resource-pack definitions of the same id. Files are plain {@code texture_animations}
 * JSON, so they are easy to hand-edit or share.
 */
public final class AnimationConfigStore {
	private static AnimationConfigStore INSTANCE;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String SUBDIR = "animations";

	private final Path dir;
	private final Map<Identifier, AnimationDefinition> overrides = new LinkedHashMap<>();

	private AnimationConfigStore() {
		this.dir = defaultDir();
	}

	public static AnimationConfigStore get() {
		if (INSTANCE == null) {
			INSTANCE = new AnimationConfigStore();
		}
		return INSTANCE;
	}

	/** The folder where user animation JSON files live. */
	public Path dir() {
		return dir;
	}

	/** Loads all persisted overrides from disk (returns a fresh map; does not touch the manager). */
	public Map<Identifier, AnimationDefinition> loadAll() {
		Map<Identifier, AnimationDefinition> result = new LinkedHashMap<>();
		if (!Files.isDirectory(dir)) {
			return result;
		}
		try (var stream = Files.list(dir)) {
			stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				try {
					String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
					Identifier id = idFromFileName(p.getFileName().toString());
					if (id != null) {
						JsonObject json = GSON.fromJson(content, JsonObject.class);
						result.put(id, AnimationDefinition.fromJson(id, json));
					}
				} catch (Exception e) {
					Anima.LOGGER.warn("Failed to load animation config {}: {}", p, e.toString());
				}
			});
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to list animation config directory {}", dir, e);
		}
		return result;
	}

	/** Loads persisted overrides into memory (not registered into the manager). */
	public void reload() {
		overrides.clear();
		overrides.putAll(loadAll());
	}

	/** Registers all persisted overrides into the {@link AnimatedTextureManager}. */
	public void loadInto(AnimatedTextureManager manager) {
		if (overrides.isEmpty()) {
			return;
		}
		for (Map.Entry<Identifier, AnimationDefinition> e : overrides.entrySet()) {
			applyTo(manager, e.getKey(), e.getValue());
		}
	}

	/** The current in-memory override for {@code id}, or {@code null}. */
	public AnimationDefinition get(Identifier id) {
		return overrides.get(id);
	}

	/** Saves an override to disk (and memory). */
	public void save(Identifier id, AnimationDefinition def) {
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(fileNameFor(id));
			Files.writeString(file, GSON.toJson(def.toJson()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to save animation {}: {}", id, e.toString());
		}
		overrides.put(id, def);
	}

	/** Deletes an override from disk (and memory). Does not touch the manager. */
	public void delete(Identifier id) {
		overrides.remove(id);
		try {
			Files.deleteIfExists(dir.resolve(fileNameFor(id)));
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to delete animation {}: {}", id, e.toString());
		}
	}

	/** Registers (or updates) a definition in the manager, including text tags and world sprites. */
	public static void applyTo(AnimatedTextureManager manager, Identifier id, AnimationDefinition def) {
		manager.registerDefinition(def);
		if (def.type() == AnimationType.WORLD_SPRITE && def.texture() != null) {
			manager.registerWorldSprite(def.texture(),
				new WorldSpriteAnimationSpec(def.frameIndices(), def.frameTimeMs(), def.interpolate()));
		}
	}

	/** Saves a timeline JSON to the config timelines folder and returns its absolute path. */
	public String exportTimeline(JsonObject timelineJson) {
		try {
			Path out = Minecraft.getInstance().gameDirectory.toPath()
				.resolve("config").resolve(Anima.MOD_ID).resolve("timelines");
			Files.createDirectories(out);
			String name = "timeline_" + System.currentTimeMillis() + ".json";
			Path file = out.resolve(name);
			Files.writeString(file, GSON.toJson(timelineJson), StandardCharsets.UTF_8);
			return file.toAbsolutePath().toString();
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to export timeline JSON: {}", e.toString());
			return L10n.tr("anima.ui.error.timeline_write_failed") + e.getMessage() + ")";
		}
	}

	/** Folder where shareable particle-group JSONs live. */
	public Path particleGroupDir() {
		Path root = Minecraft.getInstance().gameDirectory.toPath()
			.resolve("config").resolve(Anima.MOD_ID);
		return root.resolve("particle_groups");
	}

	/** Saves a particle-group JSON ({@code {name, items:[{id,count},...]}}) and returns its path. */
	public String saveParticleGroup(String name, JsonObject json) {
		try {
			Path dir = particleGroupDir();
			Files.createDirectories(dir);
			String safe = name.replaceAll("[^\\w\\u4e00-\\u9fa5-]", "_");
			if (safe.isBlank()) {
				safe = "group";
			}
			Path file = dir.resolve(safe + ".json");
			Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
			return file.toAbsolutePath().toString();
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to save particle group: {}", e.toString());
			return L10n.tr("anima.ui.error.save_failed") + e.getMessage();
		}
	}

	/** Loads all saved particle-group JSONs, sorted by name (for the particle picker). */
	public List<JsonObject> loadParticleGroups() {
		List<JsonObject> out = new ArrayList<>();
		Path dir = particleGroupDir();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (var stream = Files.list(dir)) {
			stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				try {
					String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
					out.add(GSON.fromJson(content, JsonObject.class));
				} catch (Exception e) {
					Anima.LOGGER.warn("Failed to load particle group {}: {}", p, e.toString());
				}
			});
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to list particle groups: {}", e);
		}
		out.sort((a, b) -> (a.has("name") ? a.get("name").getAsString() : "")
			.compareTo(b.has("name") ? b.get("name").getAsString() : ""));
		return out;
	}

	/** Legacy folder name used before the mod was renamed (one-time migration below). */
	private static final String LEGACY_MOD_ID = "texture-animation-library";

	private static Path defaultDir() {
		Path config = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
		Path root = config.resolve(Anima.MOD_ID);
		migrateLegacyDir(config, root);
		return root.resolve(SUBDIR);
	}

	/** Moves {@code config/texture-animation-library/} to the new mod folder the first time the
	 *  renamed mod runs, so existing animations / timelines / particle groups are not lost. */
	private static void migrateLegacyDir(Path config, Path newRoot) {
		try {
			Path old = config.resolve(LEGACY_MOD_ID);
			if (Files.isDirectory(old) && !Files.exists(newRoot)) {
				Files.move(old, newRoot);
				Anima.LOGGER.info("Migrated config folder {} → {}", old, newRoot);
			}
		} catch (IOException e) {
			Anima.LOGGER.warn("Failed to migrate the legacy config folder: {}", e.toString());
		}
	}

	private static String fileNameFor(Identifier id) {
		return id.getNamespace() + "__" + id.getPath().replace('/', '_') + ".json";
	}

	private static Identifier idFromFileName(String name) {
		if (!name.endsWith(".json")) {
			return null;
		}
		String base = name.substring(0, name.length() - ".json".length());
		int sep = base.indexOf("__");
		if (sep <= 0) {
			return null;
		}
		String ns = base.substring(0, sep);
		String path = base.substring(sep + 2).replace('_', '/');
		return Identifier.fromNamespaceAndPath(ns, path);
	}
}