package anima.timeline;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import anima.client.lang.L10n;

/**
 * One channel of a timeline: a named property with an ordered list of keyframes.
 */
public final class TimelineTrack {
	private final String property;
	private final String displayName;
	private final List<TimelineKeyframe> keyframes = new ArrayList<>();

	public TimelineTrack(String property, String displayName) {
		this.property = property;
		this.displayName = displayName;
	}

	/** A preset styled track for a GUI sprite. */
	public static TimelineTrack positionX() {
		return new TimelineTrack("x", L10n.tr("anima.ui.track.pos_x"));
	}
	public static TimelineTrack positionY() {
		return new TimelineTrack("y", L10n.tr("anima.ui.track.pos_y"));
	}
	public static TimelineTrack scale() {
		return new TimelineTrack("scale", L10n.tr("anima.ui.prop.scale"));
	}
	public static TimelineTrack opacity() {
		return new TimelineTrack("opacity", L10n.tr("anima.ui.prop.opacity"));
	}

	public String property() {
		return property;
	}

	public String displayName() {
		return displayName;
	}

	public List<TimelineKeyframe> keyframes() {
		return keyframes;
	}

	public void sort() {
		keyframes.sort((a, b) -> Integer.compare(a.timeMs(), b.timeMs()));
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("property", property);
		JsonArray arr = new JsonArray();
		for (TimelineKeyframe k : keyframes) {
			arr.add(k.toJson());
		}
		json.add("keyframes", arr);
		return json;
	}
}