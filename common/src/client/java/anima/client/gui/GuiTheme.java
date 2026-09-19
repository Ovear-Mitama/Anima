package anima.client.gui;

/**
 * Modern dark theme in the style of Visual Studio ('VS black'), shared by the library's
 * screens. Other mods that open these screens or build their own UI can reuse the constants.
 */
public final class GuiTheme {
	public static final int BG = 0xFF282828;          // window / workbench background (#282828)
	public static final int PANEL = 0xFF2E2E2E;       // panel / side bar background
	public static final int PANEL_HOVER = 0xFF3C3C3C; // hovered / selected row
	public static final int BORDER = 0xFF3F3F3F;      // panel border
	public static final int ACCENT = 0xFF007ACC;      // VS blue accent
	public static final int ACCENT_DARK = 0xFF0E639C; // pressed accent
	public static final int WARN = 0xFFDCDCAA;        // soft yellow (warnings)
	public static final int ERROR = 0xFFF14C4C;       // error / delete
	public static final int TEXT = 0xFFD4D4D4;        // primary text
	public static final int SUBTEXT = 0xFF9A9A9A;     // secondary / hints
	/** Green from Damage-Engine (#B5F0C6) — clip grips and the timeline's right-edge handle. */
	public static final int DE_GREEN = 0xFFB5F0C6;

	private GuiTheme() {
	}
}
