package anima.client.world;

import java.util.Locale;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import anima.client.lang.L10n;

/**
 * Small on-screen orientation gizmo drawn in the top-right corner: a 3-axis tripod (X red,
 * Y green, Z blue) that rotates with the camera, plus yaw/pitch and the compass facing —
 * similar in spirit to the F3 debug facing readout, but always visible while configuring.
 */
public final class ViewGizmo {
	private ViewGizmo() {
	}

	/** Draws the gizmo in the top-right corner (no-op outside a world). */
	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null || mc.gameRenderer == null) {
			return;
		}
		int sw = mc.getWindow().getGuiScaledWidth();
		int cx = sw - 44;
		int cy = 44;

		// translucent backing panel
		g.fill(cx - 32, cy - 32, cx + 34, cy + 42, 0x70101216);
		g.fill(cx - 32, cy - 32, cx + 34, cy - 31, 0x80007ACC);

		// rotate world axes into camera space so the tripod follows the view
		Camera camera = mc.gameRenderer.getMainCamera();
		Quaternionf toCamera = new Quaternionf(camera.rotation()).conjugate();
		float[][] axes = { { 1f, 0f, 0f }, { 0f, 1f, 0f }, { 0f, 0f, 1f } };
		int[] cols = { 0xFFE05454, 0xFF72C96F, 0xFF58A6E8 };
		String[] names = { "X", "Y", "Z" };
		for (int i = 0; i < 3; i++) {
			Vector3f v = new Vector3f(axes[i][0], axes[i][1], axes[i][2]).rotate(toCamera);
			if (v.z() > 0.25f) {
				continue; // axis points away from the camera → hide it (prevents flicker)
			}
			float dx = v.x() * 20f;
			float dy = -v.y() * 20f; // screen Y grows downward
			int ex = cx + Math.round(dx);
			int ey = cy + Math.round(dy);
			// smooth 2px shaft: bright core plus a translucent widening pass
			line(g, cx, cy, ex, ey, cols[i]);
			line(g, cx + 1, cy, ex + 1, ey, (cols[i] & 0x00FFFFFF) | 0x70000000);
			dot(g, ex, ey, cols[i]);
			if (Math.abs(dx) > 5f || Math.abs(dy) > 5f) {
				g.drawString(mc.font, names[i], ex + (dx >= 0 ? 3 : -9), ey + (dy >= 0 ? 3 : -10), cols[i]);
			}
		}
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

		// yaw / pitch / compass facing
		String facing = compass(player.getYRot());
		g.drawString(mc.font, String.format(Locale.ROOT, "X %.0f\u00b0  Y %.0f\u00b0", player.getYRot(), player.getXRot()),
			cx - 30, cy + 24, 0xFFD4D4D4);
		g.drawString(mc.font, L10n.tr("anima.ui.label.facing") + facing, cx - 30, cy + 34, 0xFF9AE6B4);
	}

	/** 8-way compass name for a yaw angle (Minecraft: 0° = south, +90° = west). */
	private static String compass(float yaw) {
		String[] names = { L10n.tr("anima.ui.compass.s"), L10n.tr("anima.ui.compass.sw"), L10n.tr("anima.ui.compass.w"), L10n.tr("anima.ui.compass.nw"), L10n.tr("anima.ui.compass.n"), L10n.tr("anima.ui.compass.ne"), L10n.tr("anima.ui.compass.e"), L10n.tr("anima.ui.compass.se") };
		int idx = Math.floorMod(Math.round(yaw / 45f), 8);
		return names[idx];
	}

	/** Soft round marker at an axis tip (layered translucent squares ≈ antialiased dot). */
	private static void dot(GuiGraphics g, int x, int y, int col) {
		int rgb = col & 0x00FFFFFF;
		g.fill(x - 2, y - 2, x + 3, y + 3, (0x40 << 24) | rgb);
		g.fill(x - 1, y - 1, x + 2, y + 2, (0xB0 << 24) | rgb);
	}

	/** 1-pixel Bresenham line. */
	private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int col) {
		int dx = Math.abs(x1 - x0);
		int dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx - dy;
		int x = x0;
		int y = y0;
		while (true) {
			g.fill(x, y, x + 1, y + 1, col);
			if (x == x1 && y == y1) {
				break;
			}
			int e2 = 2 * err;
			if (e2 > -dy) {
				err -= dy;
				x += sx;
			}
			if (e2 < dx) {
				err += dx;
				y += sy;
			}
		}
	}
}
