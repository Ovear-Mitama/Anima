package anima.client.gui;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Creates (or reopens) the dedicated sandbox world used to preview animation effects with the
 * real Minecraft renderer: superflat with a single barrier layer at the bottom, peaceful, no
 * mob spawning / no mob griefing / no fire tick / no random ticks, and the player joins in
 * spectator mode (so blocks can never be broken or placed).
 * <p>
 * Since the vanilla renderer is used here, previews look "normal" — real 3D particles, real sky,
 * one grid cell equals one block, and no GUI-antialiasing problems.
 */
public final class ConfigWorldLauncher {
	/** Folder / display name of the sandbox world. Safe to delete from the world list later. */
	public static final String LEVEL_ID = "anima-config";

	private ConfigWorldLauncher() {
	}

	private static boolean nightListenerInstalled;
	private static boolean nightApplied;

	private static void installNightTicker() {
		if (nightListenerInstalled || anima.platform.PlatformHooks.get() == null) {
			return;
		}
		nightListenerInstalled = true;
		anima.platform.PlatformHooks.get().addClientTickListener(ConfigWorldLauncher::tickNight);
	}

	/** Daytime tick the sandbox world is locked to (15000 = after dusk, i.e. night). */
	private static final int LOCKED_TIME_TICK = 15000;

	/** Once the config world is loaded, freeze it at NIGHT (tick 15000) so the preview looks
	 *  the same every time and bright/glowing effects read clearly. */
	private static void tickNight() {
		if (nightApplied) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || !LEVEL_ID.equals(server.getWorldData().getLevelName())) {
			return;
		}
		nightApplied = true;
		mc.player.connection.sendCommand("time set " + LOCKED_TIME_TICK);
		mc.player.connection.sendCommand("gamerule doDaylightCycle false");
	}

	/** Enters the config world (creating it on first use). No-op when already inside a world. */
	public static void launch(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			return; // already playing — the in-world editor overlay is a later stage
		}
		nightApplied = false; // (re)request night for this session
		installNightTicker();
		if (mc.getLevelSource().levelExists(LEVEL_ID)) {
			mc.createWorldOpenFlows().openWorld(LEVEL_ID, () -> { });
			return;
		}
		// no structures at all (no villages/mineshafts) — the user only wants a clean floor
		WorldOptions options = WorldOptions.defaultWithRandomSeed().withStructures(false);
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBLOOT).set(false, null);
		rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, null);
		rules.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
		rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
		rules.getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(true, null);
		LevelSettings settings = new LevelSettings(LEVEL_ID, GameType.SPECTATOR, false,
			Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options, ConfigWorldLauncher::dimensions, parent);
	}

	/** Overworld = superflat with a single white-concrete floor layer and a structure-free biome. */
	private static WorldDimensions dimensions(RegistryAccess registryAccess) {
		HolderGetter<Biome> biomes = registryAccess.lookupOrThrow(Registries.BIOME);
		HolderGetter<StructureSet> structures = registryAccess.lookupOrThrow(Registries.STRUCTURE_SET);
		HolderGetter<PlacedFeature> features = registryAccess.lookupOrThrow(Registries.PLACED_FEATURE);
		FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, structures, features)
			.withBiomeAndLayers(List.of(new FlatLayerInfo(63, Blocks.DIRT), new FlatLayerInfo(1, Blocks.GRASS_BLOCK)),
				Optional.<net.minecraft.core.HolderSet<StructureSet>>empty(),
				biomes.getOrThrow(Biomes.THE_VOID)); // no structures / no features at all
		ChunkGenerator generator = new FlatLevelSource(flat);
		return WorldPresets.createNormalWorldDimensions(registryAccess).replaceOverworldGenerator(registryAccess, generator);
	}

	/** True while the client is inside the dedicated config world (used to hide demo previews). */
	public static boolean isConfigWorld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
		}
		net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
		return server != null && LEVEL_ID.equals(server.getWorldData().getLevelName());
	}
}
