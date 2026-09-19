package anima.client.world;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Public helper that projects a world position to GUI (scaled) screen coordinates.
 * <p>
 * The math matches what Minecraft mods such as Damage-Engine use for world-space floating
 * text: {@code clip = projection * invert(translate(+cameraPos) * rotate(cameraRotation))}.
 * The real world projection matrix should be captured once per frame from the world render
 * pass via {@link #captureProjection(Matrix4f)} (the library's platform entrypoints already
 * do this); when no capture is available yet, a projection is rebuilt from the FOV setting.
 * <p>
 * Returns {@code null} for points behind the camera. NDC is clamped to ±2 like Damage-Engine
 * so extreme off-screen positions stay sane.
 */
public final class WorldProjection {
	private static final Matrix4f CAPTURED = new Matrix4f();
	private static volatile boolean captured;

	private WorldProjection() {
	}

	/** Snapshots the real world projection matrix (call from the world render pass). */
	public static void captureProjection(Matrix4f projection) {
		if (projection != null) {
			CAPTURED.set(projection);
			captured = true;
		}
	}

	public static boolean hasCapturedProjection() {
		return captured;
	}

	/**
	 * Projects a world position using the main camera.
	 *
	 * @return {@code {screenX, screenY, w}} in GUI-scaled coordinates, or {@code null} when the
	 *         point is behind the camera / degenerate.
	 */
	public static float[] project(double worldX, double worldY, double worldZ) {
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		return project(camera, worldX, worldY, worldZ);
	}

	/** Projects a world position with an explicit camera. */
	public static float[] project(Camera camera, double worldX, double worldY, double worldZ) {
		Minecraft mc = Minecraft.getInstance();
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		if (camera == null || width <= 0 || height <= 0) {
			return null;
		}

		Matrix4f projection;
		if (captured) {
			projection = new Matrix4f(CAPTURED);
		} else {
			float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
			projection = new Matrix4f().perspective((float) Math.toRadians(mc.options.fov().get()), aspect, 0.05f, 1000f);
		}
		Vec3 cam = camera.getPosition();
		Matrix4f view = new Matrix4f()
			.translate((float) cam.x, (float) cam.y, (float) cam.z)
			.rotate(camera.rotation())
			.invert();
		Matrix4f viewProj = new Matrix4f(projection).mul(view);

		Vector4f p = new Vector4f((float) worldX, (float) worldY, (float) worldZ, 1f).mul(viewProj);
		if (p.w() <= 0.001f) {
			return null; // behind the camera
		}
		float ndcX = Math.max(-2f, Math.min(2f, p.x() / p.w()));
		float ndcY = Math.max(-2f, Math.min(2f, p.y() / p.w()));
		return new float[] { (ndcX * 0.5f + 0.5f) * width, (1f - (ndcY * 0.5f + 0.5f)) * height, p.w() };
	}
}
