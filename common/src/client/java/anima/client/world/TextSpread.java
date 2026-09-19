package anima.client.world;

/**
 * 文本的<b>扩散 / 漂移</b>位移求值：调用方只提供随机值（方向、起始半径、速度），离开锚点的
 * 运动曲线由库决定——先按缓出曲线加速离开，到达 {@code durationMs} 后停住不再移动。
 * <p>
 * 典型用法（跳字）：命中点周围随机撒开，增强模式下再往外漂一段。
 *
 * <pre>{@code
 * TextSpread.Offset o = TextSpread.compute(dirX, dirY, radius, speed, localMs, 2300f);
 * AnimaApi.drawWorldText3D(buffers, camera, text, x, y, z, o.x(), o.y(), ...);
 * }</pre>
 */
public final class TextSpread {

	/** 以锚点为原点的像素偏移。 */
	public record Offset(float x, float y) {
	}

	private TextSpread() {
	}

	/**
	 * @param dirX/dirY 单位方向（由调用方随机给出）
	 * @param radius    起始半径（像素）：文字一出现就离锚点这么远
	 * @param drift     总共往外漂移的像素距离（0 = 只保留起始半径）
	 * @param timeMs    该实例自己的时钟（ms）
	 * @param durationMs 漂移完成所需时间（ms）
	 */
	public static Offset compute(float dirX, float dirY, float radius, float drift, float timeMs, float durationMs) {
		float t = durationMs <= 0f ? 1f : Math.max(0f, Math.min(1f, timeMs / durationMs));
		float eased = 1f - (1f - t) * (1f - t); // 缓出：开始快、越接近终点越慢
		float distance = radius + Math.max(0f, drift) * eased;
		return new Offset(dirX * distance, dirY * distance);
	}
}
