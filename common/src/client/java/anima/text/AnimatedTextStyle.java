package anima.text;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

import anima.Anima;

/**
 * The marker scheme used by the tag-based text animation mode.
 * <p>
 * Because {@link net.minecraft.network.chat.Style} has no custom data field, a tag is encoded
 * in the style's {@code font} field as {@code anima:textanim/<tag>}. JSON
 * components deserialize this natively (no extra mixin), and the
 * {@code Font$PreparedTextBuilder} mixin detects the marker, restores the default font and
 * applies the animated color/alpha.
 * <p>
 * Note: the reserved {@code anima:textanim/*} namespace must not be used
 * for real fonts. A tag only supports loop-style effects when used in automatic (marker) mode.
 */
public final class AnimatedTextStyle {
	public static final String PREFIX = "textanim/";

	private static final Map<String, TextAnimationSpec> TAGS = new HashMap<>();

	private AnimatedTextStyle() {
	}

	public static FontDescription markerFor(String tag) {
		return new FontDescription.Resource(Identifier.fromNamespaceAndPath(Anima.MOD_ID, PREFIX + tag));
	}

	/** Returns the tag name if the font description is one of our markers, else {@code null}. */
	public static String tagFromMarker(FontDescription font) {
		if (!(font instanceof FontDescription.Resource resource)) {
			return null;
		}
		Identifier location = resource.id();
		if (location == null || !location.getNamespace().equals(Anima.MOD_ID)
				|| !location.getPath().startsWith(PREFIX)) {
			return null;
		}
		return location.getPath().substring(PREFIX.length());
	}

	public static void registerTag(String tag, TextAnimationSpec spec) {
		TAGS.put(tag, spec);
	}

	public static void unregisterTag(String tag) {
		TAGS.remove(tag);
	}

	public static void clearTags() {
		TAGS.clear();
	}

	public static TextAnimationSpec get(String tag) {
		return TAGS.get(tag);
	}
}
