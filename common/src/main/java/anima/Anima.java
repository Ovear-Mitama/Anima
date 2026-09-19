package anima;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared library identity. This class is intentionally loader-agnostic so it can be
 * compiled by both the Fabric and the NeoForge project. Platform entrypoints live in
 * their own loader-specific packages and delegate here.
 */
public final class Anima {
	public static final String MOD_ID = "anima";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Anima() {
	}

	/** Creates a {@link ResourceLocation} in this library's namespace. */
	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
