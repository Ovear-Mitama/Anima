package anima.client.lang;

import net.minecraft.client.resources.language.I18n;

/**
 * Small wrapper around Minecraft's client language lookup. Every string the editor draws goes
 * through this, so the whole interface can be translated by a resource pack:
 * {@code assets/anima/lang/<locale>.json}. Missing keys fall back to the key itself.
 */
public final class L10n {
	private L10n() {
	}

	/** Translated text for {@code key}; {@code args} are applied with {@link String#format}. */
	public static String tr(String key, Object... args) {
		return I18n.get(key, args);
	}
}
