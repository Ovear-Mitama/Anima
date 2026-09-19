package anima.timeline;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import anima.client.lang.L10n;

/**
 * A clip-style timeline: a total duration (ms) and a set of property tracks. Edited in the
 * visual {@link TimelineEditorScreen} and exported to JSON.
 */
public final class Timeline {
	private int durationMs = 2000;
	private final List<TimelineTrack> tracks = new ArrayList<>();

	public Timeline() {
		// default clip tracks (左列固定的属性轨道)
		tracks.add(new TimelineTrack("x", L10n.tr("anima.ui.track.pos_x")));
		tracks.add(new TimelineTrack("y", L10n.tr("anima.ui.track.pos_y")));
		tracks.add(new TimelineTrack("scale", L10n.tr("anima.ui.prop.scale")));
		tracks.add(new TimelineTrack("opacity", L10n.tr("anima.ui.prop.opacity")));
	}

	public int durationMs() {
		return durationMs;
	}

	public void setDurationMs(int durationMs) {
		this.durationMs = Math.max(1, durationMs);
	}

	public List<TimelineTrack> tracks() {
		return tracks;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("type", "timeline");
		json.addProperty("duration", durationMs);
		JsonArray arr = new JsonArray();
		for (TimelineTrack t : tracks) {
			arr.add(t.toJson());
		}
		json.add("tracks", arr);
		return json;
	}
}