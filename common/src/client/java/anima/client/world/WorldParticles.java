package anima.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Public helper for emitting <b>real 3D vanilla particles</b> in the client world by registry
 * id (e.g. {@code "minecraft:flame"}, or any particle registered by another mod). The particles
 * are simulated by the vanilla particle engine, so they have real depth and occlusion.
 * <p>
 * Only "simple" particles (those that ARE their own {@link ParticleOptions}, i.e. the vast
 * majority) are supported; parameterised ones (dust colour, block/item, …) need their own
 * options object and are skipped.
 */
public final class WorldParticles {
	private WorldParticles() {
	}

	/** Resolves a registry particle id to options, or {@code null} if unknown / not simple. */
	public static ParticleOptions optionsOf(String registryId) {
		if (registryId == null) {
			return null;
		}
		Identifier id = Identifier.tryParse(registryId);
		if (id == null) {
			return null;
		}
		// 26.1 起注册表查询返回 Optional<Reference<...>>
		ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(id)
			.map(net.minecraft.core.Holder::value).orElse(null);
		return type instanceof ParticleOptions options ? options : null;
	}

	/**
	 * Emits {@code count} particles around a world position.
	 *
	 * @param registryId particle id (namespace:path)
	 * @param x,y,z      centre of the emission
	 * @param count      how many particles to spawn this call
	 * @param spread     random position spread (blocks), same on all axes
	 * @param speed      random initial velocity scale (blocks/tick)
	 * @return the number of particles actually emitted
	 */
	public static int emit(String registryId, double x, double y, double z, int count, double spread, double speed) {
		return emit(registryId, x, y, z, count, spread, spread, spread, speed);
	}

	/**
	 * Vanilla {@code /particle} semantics: {@code deltaX/Y/Z} is the spread of the spawn positions
	 * (blocks, Gaussian — 0 keeps every particle exactly at {@code x/y/z}) and {@code speed} the
	 * multiplier of the random initial velocity (0 = no added motion).
	 */
	public static int emit(String registryId, double x, double y, double z, int count,
			double deltaX, double deltaY, double deltaZ, double speed) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || count <= 0) {
			return 0;
		}
		ParticleOptions options = optionsOf(registryId);
		if (options == null) {
			return 0;
		}
		RandomSource random = level.getRandom();
		for (int i = 0; i < count; i++) {
			double px = x + random.nextGaussian() * deltaX;
			double py = y + random.nextGaussian() * deltaY;
			double pz = z + random.nextGaussian() * deltaZ;
			double vx = random.nextGaussian() * speed;
			double vy = random.nextGaussian() * speed;
			double vz = random.nextGaussian() * speed;
			level.addParticle(options, px, py, pz, vx, vy, vz);
		}
		return count;
	}
}
