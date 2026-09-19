package anima.text;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

import anima.Anima;

/**
 * The marker scheme used by the tag-based text animation mode.
 * <p>
 * Because {@link net.minecraft.network.chat.Style} has no custom data field, a tag is encoded
 * in the style's {@code font} field as {@code anima:textanim/<tag>}. JSON
 * components deserialize this natively (no extra mixin), and the
 * {@code Font$StringRenderOutput} mixin detects the marker, restores the default font and
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

	public static ResourceLocation markerFor(String tag) {
		return ResourceLocation.fromNamespaceAndPath(Anima.MOD_ID, PREFIX + tag);
	}

	/** Returns the tag name if the location is one of our markers, else {@code null}. */
	public static String tagFromMarker(ResourceLocation location) {
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
