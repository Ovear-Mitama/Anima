package anima.client.gui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;

/**
 * 值拖拽时的「鼠标环绕」（模仿 Unity）：把数值往同一方向拖到操作系统屏幕边缘后，光标会被瞬移到
 * 对面边缘，于是可以继续朝同一方向拖，不必松手再往回拖一次。
 * <p>
 * 边界取的是<b>系统屏幕</b>的显示器工作区（已排除任务栏），不是 Minecraft 窗口 —— 所以即使游戏
 * 是窗口模式运行，也能一直拖到整个屏幕的最左 / 最右。
 */
final class DragCursor {
	private DragCursor() {
	}

	/**
	 * 若光标已经贴住系统屏幕的左 / 右边缘，就把它环绕到对面边缘。
	 * <p>
	 * 只处理水平方向：数值拖拽只用到横向位移，纵向环绕只会让光标位置变得难以预料。
	 *
	 * @return 调用方需要叠加到「拖拽起点 X」上的 GUI 像素修正量（没有环绕时返回 0）。
	 *         加上它之后 {@code 光标X - 拖拽起点X} 保持不变，因此数值不会因为瞬移而跳变。
	 */
	static double wrapAtScreenEdge() {
		Minecraft mc = Minecraft.getInstance();
		long win = mc.getWindow().handle();
		if (win == 0L) {
			return 0;
		}
		long monitor = GLFW.glfwGetPrimaryMonitor();
		if (monitor == 0L) {
			return 0;
		}
		int[] mx = new int[1];
		int[] my = new int[1];
		int[] mw = new int[1];
		int[] mh = new int[1];
		GLFW.glfwGetMonitorWorkarea(monitor, mx, my, mw, mh);
		if (mw[0] <= 4) {
			return 0;
		}
		double[] cx = new double[1];
		double[] cy = new double[1];
		GLFW.glfwGetCursorPos(win, cx, cy);
		// 光标位置是「相对窗口内容区」的，而显示器工作区是「屏幕坐标」：必须
		// 加上窗口在屏幕上的位置才能比较。否则窗口不在屏幕最左时，窗口内坐标
		// （很小的值）会立刻被判成贴住屏幕左缘而环绕到右侧，而右侧又永远够不到。
		int[] wx = new int[1];
		int[] wy = new int[1];
		GLFW.glfwGetWindowPos(win, wx, wy);
		double screenX = wx[0] + cx[0];
		double left = mx[0] + 1;
		double right = mx[0] + mw[0] - 2;
		double nx;
		if (screenX <= left) {
			nx = right;
		} else if (screenX >= right) {
			nx = left;
		} else {
			return 0;
		}
		// setCursorPos 要的同样是窗口内坐标，所以把目标屏幕 X 换算回去
		GLFW.glfwSetCursorPos(win, nx - wx[0], cy[0]);
		// 屏幕坐标 → GUI 坐标的换算，与原版 MouseHandler 一致
		double factor = mc.getWindow().getGuiScaledWidth()
			/ (double) Math.max(1, mc.getWindow().getScreenWidth());
		return (nx - screenX) * factor;
	}
}
