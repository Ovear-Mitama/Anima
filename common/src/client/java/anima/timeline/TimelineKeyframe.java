package anima.timeline;

import com.google.gson.JsonObject;

/**
 * A single keyframe on a timeline track: a value at a point in time, interpolated toward the
 * next keyframe with an easing curve (by name).
 */
public record TimelineKeyframe(int timeMs, float value, String ease) {
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("time", timeMs);
		json.addProperty("value", value);
		json.addProperty("ease", ease == null ? "linear" : ease);
		return json;
	}
}