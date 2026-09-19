package anima.manager;

import com.google.gson.JsonObject;

/** A named effect plus its raw JSON configuration. */
public record EffectSpec(String name, JsonObject config) {
}
