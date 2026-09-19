package anima.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 剪辑编辑器关闭时回传给调用方的结果。
 *
 * @param clips     时间线上的剪辑（{@code [{"effect","start","duration","speed"}]}）
 * @param textProps 文本对象属性：{@code x / y / z}（方块，相对锚点的偏移）、{@code scale}、
 *                  {@code alpha}、{@code distanceScale}、{@code durationMs}，以及关键帧
 *                  {@code keys:[{t, v:[x,y,z,scale,alpha], ease}]}。
 *                  用 {@code AnimaApi.evalTextProps(textProps, localMs)} 在运行时求值。
 */
public record ClipEditorResult(JsonArray clips, JsonObject textProps) {
}
