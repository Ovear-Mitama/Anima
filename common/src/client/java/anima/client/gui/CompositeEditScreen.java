package anima.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import anima.client.world.WorldParticles;
import anima.client.world.WorldProjection;
import anima.client.world.WorldText3D;
import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;
import anima.api.IAnimationEffect;
import anima.manager.AnimationConfigStore;
import anima.platform.PlatformHooks;
import anima.client.lang.L10n;
import anima.text.CharClips;

/**
 * Animation-effect mixer (preview UI). Left palette = built-in effects (drag to add),
 * center = sample text with active effects stacked, bottom timeline = multi-lane clip rows
 * (drag a block to move it or switch rows, drag its edges to resize, wheel scrolls the rows,
 * right-click = copy/delete menu, click empty = seek, Space = play/pause).
 * Right = numeric property fields (hold arrow keys to step), hidden until a clip is selected.
 */
public class CompositeEditScreen extends Screen {
	private static final int TOP_H = 10;
	/** Minimum width of the 效果/粒子 window — the real width adapts to its longest row. */
	private static final int LIST_W_MIN = 72;
	private static final int PROP_W = 112;
	private static final int TIMELINE_H = 60;
	private static final int CTRL_W = 52;
	private static final int SAMPLE_W = 150;

	private record EffectPreset(String key, String name, int defaultDurationMs, String category) {
	}

	/** A saved particle-group JSON shown as its own palette row (drag it in like an effect). */
	private record PaletteGroup(String name, JsonObject json) {
	}

	/** Palette category of the saved particle-group JSONs (resolved on demand — see {@link #propName}). */
	private static String savedGroupCat() {
		return L10n.tr("anima.ui.picker.saved_groups");
	}

	/** The effect palette. Rebuilt on every access so its captions follow a language change. */
	private static List<EffectPreset> palette() {
		return List.of(
			new EffectPreset("fade_in", L10n.tr("anima.ui.effect.fade_in"), 1000, L10n.tr("anima.ui.cat.enter")),
			new EffectPreset("typewriter_in", L10n.tr("anima.ui.effect.typewriter_in"), 1000, L10n.tr("anima.ui.cat.enter")),
			new EffectPreset("char_fall_in", L10n.tr("anima.ui.effect.fall_in"), 1000, L10n.tr("anima.ui.cat.enter")),
			new EffectPreset("char_fall_random_in", L10n.tr("anima.ui.effect.fall_random_in"), 1000, L10n.tr("anima.ui.cat.enter")),
			new EffectPreset("char_drift_in", L10n.tr("anima.ui.effect.drift_in"), 1000, L10n.tr("anima.ui.cat.enter")),
			new EffectPreset("fade_out", L10n.tr("anima.ui.effect.fade_out"), 1000, L10n.tr("anima.ui.cat.exit")),
			new EffectPreset("typewriter_out", L10n.tr("anima.ui.effect.typewriter_out"), 1000, L10n.tr("anima.ui.cat.exit")),
			new EffectPreset("particle_add", "+", 1000, L10n.tr("anima.ui.cat.particle")));
	}

	/**
	 * Multi-lane timeline: every element is ONE lane (one row). A lane may hold several
	 * clips as long as they do not overlap in time; lanes[i] is kept sorted by startMs.
	 * The list may contain empty lanes (they are shown as empty rows).
	 */
	private final List<List<CompositeClip>> lanes = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private final Set<String> collapsedCats = new HashSet<>(); // categories hidden in the palette

	/** Clips handed in by an external caller (loaded once, see {@link #loadInitialClips}), the
	 *  text-object properties handed in with them, and the callback that receives both when the
	 *  editor closes (null = nothing handed back). */
	private final JsonArray initialClips;
	private final JsonObject initialTextProps;
	/** Caller-provided sample text for the preview (null/empty = the library's own). */
	private final String previewTextOverride;
	/** Built-in preview renderer of THIS screen (captures the sample text above). */
	private final PreviewRenderer builtinPreview;
	private final Consumer<ClipEditorResult> onSave;
	private boolean initialClipsLoaded;
	private boolean initialTextPropsLoaded;
	private boolean clipsHandedBack;

	/** Visible palette rows: group headers always shown, items only when their category is open. */
	private List<Object> rows() {
		List<Object> rows = new ArrayList<>();
		String cur = null;
		for (EffectPreset p : palette()) {
			if (!p.category().equals(cur)) {
				cur = p.category();
				rows.add(cur);
			}
			if (!collapsedCats.contains(cur)) {
				rows.add(p);
			}
		}
		// saved particle-group JSONs appear as their own category, ready to drop on the timeline
		if (!pickerGroups.isEmpty()) {
			rows.add(savedGroupCat());
			if (!collapsedCats.contains(savedGroupCat())) {
				for (JsonObject g : pickerGroups) {
					String name = g.has("name") ? g.get("name").getAsString() : L10n.tr("anima.ui.prop.particle_group");
					rows.add(new PaletteGroup(name, g));
				}
			}
		}
		return rows;
	}

	private CompositeClip selectedClip;
	private boolean playing = false;
	private float playTimeMs;
	private long lastFrameMs = -1;
	private int timelineLen = 3000; // total timeline length (ms) = the text duration
	/** Absolute bounds of the text duration / timeline length (ms). The minimum is 0.01s. */
	private static final int MIN_TIMELINE_LEN = 10;
	private static final int MAX_TIMELINE_LEN = 30000;
	/** Length used while the project is still empty (no keyframes and no manual value). */
	private static final int DEFAULT_TIMELINE_LEN = 3000;
	/** Manual text duration set in the properties panel (0 = purely content-driven). */
	private int timelineLenOverride;
	/** 距离缩放: true = real perspective (near text looks bigger, the 3D default);
	 *  false = the text keeps a constant apparent size no matter how far it is. */
	private boolean textDistanceScale = false;
	/** 显示坐标系：世界里的三轴 gizmo 是否可见（默认关，需要拖轴时再打开）。 */
	private boolean showGizmo = false;

	private double zoom = 1.0;            // timeline zoom
	private float viewOffsetMs;           // timeline horizontal pan (world ms at left edge)
	private int listScroll;
	private int laneScrollPx;             // vertical scroll of the lane rows, in PIXELS (variable heights)

	private String paletteDrag;
	private CompositeClip movingClip;     // clip currently dragged horizontally / between lanes
	private float moveGrabMs;
	private CompositeClip resizingClip;
	private boolean resizeRight;
	/** Child-row grip drag inside an expanded group (its own time window, clamped to the group). */
	private CompositeClip childResizeClip;
	private int childResizeIdx = -1;
	private boolean childResizeRight;
	/** Press on the timeline's left button column: a plain click runs a button, a drag moves the window. */
	private boolean ctrlPressed;
	private int ctrlPressIdx = -1;
	private double ctrlPressX, ctrlPressY;
	private boolean ctrlDragged;
	private int ctrlGrabX, ctrlGrabY;
	private boolean panning;
	private float panLastX;
	private boolean seekDrag;   // left-button scrub on the timeline (view follows)
	private CompositeClip ctxClip;        // clip under the right-click context menu (null = closed)
	private int ctxX, ctxY;               // where the context menu was opened
	private int ctxChild = -1;            // child particle index when a child row was right-clicked
	/** Selected child particle inside an expanded group clip (-1 = the group itself). */
	private int selectedChild = -1;

	private final Screen parent;
	private final AnimationEngine engine = AnimationEngine.get();
	private final boolean overlay;   // floating-window mode (world stays visible behind the panel)
	private int ox, oy, pw, ph;      // base layout rect (full screen); ox/oy reserved

	// world-space preview object (the sample text lives at (0,1,0) inside the config world)
	/** Fixed world ORIGIN of the scene (the gizmo never moves; the object moves around it). */
	private static final float ANCHOR_X = 0f, ANCHOR_Y = 1f, ANCHOR_Z = 0f;
	private static CompositeEditScreen activeOverlay; // the floating editor currently drawing in-world
	private static boolean previewPositioned;         // spectator was moved in front of the object
	private static boolean tickHookInstalled;         // client-tick hook for emitting world particles
	private boolean worldPreviewActive;               // true while inside the config world (this frame)
	private boolean lookDrag;                          // left-drag rotates the view inside the world
	private double lookAccumX, lookAccumY;             // pending look delta, applied once per frame
	private boolean viewPanDrag;                       // right-drag pans the view inside the world
	/** Movement keys we forwarded to the player, with the GLFW key code that pressed them. */
	private final java.util.Map<net.minecraft.client.KeyMapping, Integer> heldMoveKeys = new java.util.HashMap<>();

	// ---- preview-object properties + keyframes (position / scale / opacity) ----
	/** Number of keyframed text-object properties (0..2 = position xyz, 3 = scale, 4 = opacity). */
	private static final int PROP_COUNT = 5;

	/** Caption of text-object property row {@code i} (resolved on demand so a language change is
	 *  picked up without restarting the game — a static field would freeze it at class load). */
	private static String propName(int i) {
		return switch (i) {
			case 0 -> L10n.tr("anima.ui.prop.pos_x");
			case 1 -> L10n.tr("anima.ui.prop.pos_y");
			case 2 -> L10n.tr("anima.ui.prop.pos_z");
			case 3 -> L10n.tr("anima.ui.prop.scale");
			default -> L10n.tr("anima.ui.prop.alpha");
		};
	}

	/** Row of the 文本时长 input (the timeline length) inside the text-object property block. */
	private static final int TEXT_DUR_ROW = PROP_COUNT;
	/** Row of the 距离缩放 on/off switch. */
	private static final int DIST_ROW = PROP_COUNT + 1;
	/** Row of the 显示坐标系 on/off switch (the world-space 3-axis gizmo). */
	private static final int GIZMO_ROW = PROP_COUNT + 2;
	/** Last row of the text-object property block (used for the panel's content height). */
	private static final int LAST_PROP_ROW = GIZMO_ROW;
	/** One text-object keyframe: a snapshot of EVERY property — all fields share one key. */
	private static final class TextKey {
		int timeMs;
		String ease = "linear";
		final float[] v = new float[PROP_COUNT];
	}
	private final List<TextKey> textKeys = new ArrayList<>();
	/** Object properties are OFFSETS from the fixed origin, so the origin never moves. */
	private final float[] propDefaults = { 0f, 0f, 0f, 1f, 1f };
	/** Object world position = origin + offset (the three position properties). */
	private float objectX() { return ANCHOR_X + propValue(0, playTimeMs); }
	private float objectY() { return ANCHOR_Y + propValue(1, playTimeMs); }
	private float objectZ() { return ANCHOR_Z + propValue(2, playTimeMs); }
	private int activeProp;                                  // property whose field is being edited
	private int dragKeyIdx = -1;                             // keyframe being dragged on the text track
	private int axisDrag = -1;                               // world gizmo axis being dragged (0=X,1=Y,2=Z)
	private double axisStartMouseX, axisStartMouseY;
	private float axisStartValue;
	private double axisScreenDx, axisScreenDy;               // screen vector of one axis length at drag start
	private double pollX, pollY;                             // last polled cursor position (GUI coords)
	private boolean pollValid;
	private boolean dragEventFrame;                          // a mouse event already drove this frame's drag
	private float axisLenAtDrag = 1f;                        // world length of the axis at drag start
	private boolean lengthDrag;                              // dragging the timeline's right edge (window width)
	private int tlWidth = -1;                                // custom timeline panel width (-1 = auto fill)
	private double frozenPxPerMs = -1;                        // scale frozen when the window is resized
	private final List<DecimalField> propFields = new ArrayList<>(); // the five object property inputs
	private NumberField textDurField;                                 // 文本时长 (timeline length) input
	private boolean updatingFields;                           // guard: setValue must not write back
	/** Index range (in {@code children()}) of the widgets created for the properties panel. */
	private int propWidgetFrom, propWidgetTo;
	private int propScroll;                                   // properties panel scroll offset (px)
	private boolean particleListOpen;                        // registry particle picker expanded
	private int particleListScroll;
	private String particleSearch = "";                      // picker search filter (live)
	private SearchField searchBox;                           // picker search input (built while open)
	private boolean focusSearch;                             // focus the search box right after opening
	private List<JsonObject> pickerGroups = new ArrayList<>(); // saved particle-group JSONs shown in the picker
	private String notice = "";                              // transient status line (save group / import)
	private long noticeUntil;
	private boolean addMenuOpen;                             // "+" menu: 自定义粒子
	private int addMenuY;

	// per-window offsets (independent floating panels: 1=palette, 2=properties, 3=timeline)
	// the timeline starts slightly RAISED (-12) instead of glued to the bottom edge
	private int palDx, palDy, propDx, propDy, tlDx0;
	private int tlDy0 = -12;
	private int dragWin;             // which window is being dragged (0 = none)
	private int winGrabX, winGrabY;

	/** Characters of the sample text currently typed by a typewriter clip (-1 = full text). */
	private static int typedVisible = -1;

	/** 预览文本：调用方传入时用它的（如 DE 的示例数字），否则用库自己的示例文本。 */
	private String sampleFull() {
		return previewTextOverride != null && !previewTextOverride.isEmpty()
			? previewTextOverride
			: L10n.tr("anima.ui.preview.sample_text");
	}

	/** Sample text limited to the typewriter clip's visible prefix (full text when idle). */
	private String sampleText() {
		String full = sampleFull();
		int n = typedVisible;
		if (n < 0 || n >= full.length()) {
			return full;
		}
		return full.substring(0, Math.max(0, Math.min(full.length(), n)));
	}

	/** 外部 mod 全局覆盖的预览渲染器（{@link #setPreviewRenderer}），null = 用内建的。 */
	private static PreviewRenderer previewOverride;

	/** The preview renderer actually used: the global override, else the built-in one. */
	private PreviewRenderer previewRenderer() {
		return previewOverride != null ? previewOverride : builtinPreview;
	}

	public static void setPreviewRenderer(PreviewRenderer renderer) {
		if (renderer != null) {
			previewOverride = renderer;
		}
	}

	public CompositeEditScreen(Screen parent) {
		this(parent, false);
	}

	/** @param overlay floating-window mode: translucent panel so the world stays visible behind it */
	public CompositeEditScreen(Screen parent, boolean overlay) {
		this(parent, overlay, null, null, null, null);
	}

	/**
	 * @param overlay           floating-window mode: translucent panel so the world stays visible behind it
	 * @param initialClips      clips to load into the timeline (e.g. the damage-number clips passed by
	 *                          another mod); {@code null} = start from an empty timeline
	 * @param initialTextProps  text-object properties to load (see {@link ClipEditorResult#textProps()});
	 *                          {@code null} = defaults
	 * @param previewText       sample text drawn in the preview (e.g. the caller's own number);
	 *                          {@code null}/empty = the library's own sample text
	 * @param onSave            receives the edited clips + text properties when the editor is closed;
	 *                          {@code null} = nothing is handed back. The editor has no save button of
	 *                          its own — persisting is the caller's job (e.g. the mod's config screen).
	 */
	public CompositeEditScreen(Screen parent, boolean overlay, JsonArray initialClips, JsonObject initialTextProps,
			String previewText, Consumer<ClipEditorResult> onSave) {
		super(Component.literal(L10n.tr("anima.ui.title.preview")));
		this.parent = parent;
		this.overlay = overlay;
		this.initialClips = initialClips;
		this.initialTextProps = initialTextProps;
		this.previewTextOverride = previewText;
		this.onSave = onSave;
		this.builtinPreview = (g, font, x, y, w, h, m) -> {
			if (clamp01(m.a) <= 0.02f) {
				return; // invisible (e.g. fade-in at its start) — hide the text
			}
			String text = sampleText();
			int alpha = Math.round(clamp01(m.a) * 255f);
			int r = Math.round(clamp01(m.r) * 255f);
			int gg = Math.round(clamp01(m.g) * 255f);
			int bb = Math.round(clamp01(m.b) * 255f);
			int color = (alpha << 24) | (r << 16) | (gg << 8) | bb;
			int px = x + (w - font.width(text)) / 2 + Math.round(m.tx);
			int py = y + (h - 9) / 2 + Math.round(m.ty);
			g.drawString(font, text, px, py, color);
		};
	}

	public static Screen configScreenFactory(Screen parent) {
		return new CompositeEditScreen(parent);
	}

	@Override
	protected void init() {
		clearWidgets();
		// put the spectator in front of the preview object once, facing it — only inside the
		// dedicated config world (never hijack the player in other worlds)
		if (overlay && ConfigWorldLauncher.isConfigWorld() && !previewPositioned) {
			net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				previewPositioned = true;
				player.setPos(ANCHOR_X, ANCHOR_Y + 1.6, ANCHOR_Z + 6.0);
				player.setYRot(180.0f); // face -Z → towards the origin
				player.setXRot(15.0f); // look down a bit so the object sits near the screen centre
			}
		}
		// install the client-tick hook used to emit real 3D particles in the world
		if (overlay && !tickHookInstalled && PlatformHooks.get() != null) {
			PlatformHooks.get().addClientTickListener(CompositeEditScreen::tickOverlayStatic);
			tickHookInstalled = true;
		}
		// install the world-pass hook that draws the preview TEXT as real 3D text
		if (overlay && !worldTextHookInstalled && PlatformHooks.get() != null) {
			PlatformHooks.get().addWorldRenderListener(CompositeEditScreen::renderWorldTextStatic);
			worldTextHookInstalled = true;
		}
		// three independent draggable windows over the world; base layout is full-screen
		pw = this.width;
		ph = this.height;
		ox = 0;
		oy = 0;
		// The timeline has NO length input any more: its length follows the text/clip content
		// (see refreshTimelineLen), and the window is dragged by the left button column.
		loadInitialTextProps();
		loadInitialClips();
		refreshTimelineLen();

		// The timeline's left column IS its move handle (there is no title bar): the three
		// buttons are drawn by renderControlButtons and handled manually, so pressing one and
		// DRAGGING moves the window while a plain CLICK runs the button action.
		refreshPickerGroups();
		buildParticleSearchBox();
		propWidgetFrom = this.children().size(); // index range of the property widgets
		buildPropertyPanel();
		propWidgetTo = this.children().size();
		buildCommandEditor();
		clampPropScroll();
		clampLaneScroll(); // collapsing a group shrinks the rows — keep the scroll in range
		listScroll = Math.max(0, Math.min(Math.max(0, rows().size() - (paletteH() - 4) / 20), listScroll));
		hideScrolledPropWidgets();
	}

	/** The picker's search bar widget, built only while the picker is open. */
	private void buildParticleSearchBox() {
		if (particleListOpen && selectedClip != null && isParticleSelected()) {
			this.searchBox = new SearchField(this.font, pmListX() + propDx, pmListY() + propDy,
				pmListW(), PICKER_SEARCH_H, L10n.tr("anima.ui.picker.search"),
				s -> { if (!s.equals(particleSearch)) { particleSearch = s; particleListScroll = 0; } });
			if (!particleSearch.isEmpty()) {
				searchBox.setValue(particleSearch); // keep the filter text across screen rebuilds
			}
			addRenderableWidget(searchBox);
			if (focusSearch) {
				searchBox.setFocused(true);
				focusSearch = false;
			}
		} else {
			this.searchBox = null;
			focusSearch = false;
		}
	}

	/** Hides property widgets that scrolled outside the (capped) panel — they are drawn without the
	 *  panel pose, so this is what keeps them from spilling over the timeline AND from being drawn
	 *  over the window's title bar (the content area starts BELOW the title).
	 *  Only the widgets built by {@link #buildPropertyPanel()} are considered (recorded by index),
	 *  so the particle picker / command popup widgets are never touched by this pass. */
	private void hideScrolledPropWidgets() {
		int top = propContentTop() + propDy;
		int bottom = propPanelBottom() + propDy;
		int left = propX() + propDx - 2;
		int right = propRight() + propDx;
		List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = this.children();
		for (int i = Math.max(0, propWidgetFrom); i < Math.min(propWidgetTo, children.size()); i++) {
			if (children.get(i) instanceof net.minecraft.client.gui.components.AbstractWidget aw) {
				boolean inPanelColumn = aw.getX() >= left && aw.getX() <= right;
				if (inPanelColumn && (aw.getY() < top || aw.getY() + aw.getHeight() > bottom)) {
					aw.visible = false;
				}
			}
		}
	}

	/**
	 * The timeline length follows the <b>文本时长</b> only — the manual value from the properties
	 * panel, or the last text keyframe; with no content at all it falls back to
	 * {@link #DEFAULT_TIMELINE_LEN}. Editing a CLIP's duration/position therefore never changes the
	 * timeline length (clips are clamped INTO it instead), and exit clips (淡出 / 打字出场) are
	 * re-pinned to the end.
	 */
	private void refreshTimelineLen() {
		int end = timelineLenOverride; // 0 = not set manually
		for (TextKey k : textKeys) {
			end = Math.max(end, k.timeMs);
		}
		if (end <= 0) {
			end = DEFAULT_TIMELINE_LEN; // empty project → a sensible default ruler
		}
		timelineLen = Math.max(MIN_TIMELINE_LEN, Math.min(MAX_TIMELINE_LEN, end));
		// keep every clip INSIDE the timeline (动画时长不超过文本时长) + re-pin the exit clips
		for (int i = 0; i < lanes.size(); i++) {
			List<CompositeClip> lane = lanes.get(i);
			for (int j = 0; j < lane.size(); j++) {
				CompositeClip c = lane.get(j);
				float dur = Math.max(16f, c.durationMs());
				float start = c.startMs();
				if (isExit(c)) {
					start = Math.max(0f, timelineLen - dur); // sticky to the end
				} else if (dur > timelineLen) {
					dur = timelineLen;                       // never longer than the text duration
					start = 0f;
				} else {
					start = Math.max(0f, Math.min(start, timelineLen - dur));
				}
				if (Math.abs(start - c.startMs()) > 0.5f || Math.abs(dur - c.durationMs()) > 0.5f) {
					final CompositeClip moved = c.withStart(start).withDuration(dur);
					lane.set(j, moved);
					int[] done = emittedByClip.remove(c); // keep particle counters with the clip
					if (done != null) {
						emittedByClip.put(moved, done);
					}
					if (selectedClip == c) {
						selectedClip = moved;
					}
					if (movingClip == c) {
						movingClip = moved;
					}
					if (resizingClip == c) {
						resizingClip = moved;
					}
					if (childResizeClip == c) {
						childResizeClip = moved;
					}
					if (ctxClip == c) {
						ctxClip = moved;
					}
				}
			}
		}
		if (playTimeMs > timelineLen) {
			playTimeMs = timelineLen;
		}
		if (viewOffsetMs > timelineLen) {
			viewOffsetMs = Math.max(0f, timelineLen); // shrinking the text duration must not empty the view
		}
	}

	// ------------------------------------------------------------------ geometry (with zoom/pan)

	/** Top of the palette — same line as the properties panel's title bar, so the two top-corner
	 *  windows (palette top-left, properties top-right) line up exactly. */
	private int paletteY0() { return TOP_H + 2 + HANDLE_H; }

	/** Tallest the palette may get (when its content is longer, the wheel scrolls it). */
	private int paletteMaxH() { return Math.max(60, ph - TIMELINE_H - 30 - TOP_H - 16); }

	/** Palette height: fits the content, capped so it can never cover the timeline. */
	private int paletteH() { return Math.min(paletteMaxH(), Math.max(60, rows().size() * 20 + 8)); }

	/** Palette bullet of every effect preset — one uniform mark (same as 淡出's) for all of them. */
	private static final String PRESET_MARK = "◆ ";

	/** Palette width: as wide as the longest visible row needs (capped to a third of the window). */
	private int listW() {
		int w = this.font.width(L10n.tr("anima.ui.title.palette")) + 16;
		for (Object o : rows()) {
			String label = o instanceof EffectPreset p ? (PRESET_MARK + p.name())
				: (o instanceof PaletteGroup g ? ("≡ " + g.name()) : ("- " + o));
			w = Math.max(w, this.font.width(label) + 30);
		}
		return Math.max(LIST_W_MIN, Math.min(Math.max(120, pw / 3), w));
	}

	private int centerX() { return listW() + 6; }
	private int centerW() { return Math.max(80, pw - listW() - PROP_W - 16); }
	private int centerY() { return TOP_H + 6; }
	/** The preview canvas keeps the full height even though the palette itself now fits its rows. */
	private int centerH() { return paletteMaxH(); }
	private int timelineY() { return ph - TIMELINE_H + 2; }
	private int propX() { return pw - PROP_W - 4; }
	private int tlX() { return CTRL_W + 4; }

	/** Right margin kept free so the timeline (and its right-edge grip) never touches the screen edge. */
	private static final int TL_RIGHT_MARGIN = 18;

	private int tlW() {
		int max = Math.max(120, pw - tlX() - TL_RIGHT_MARGIN);
		return tlWidth > 0 ? Math.min(tlWidth, max) : Math.max(pw - CTRL_W - 10 - 12, 40);
	}

	/** px per ms: by default the timeline fills the window; once the user resizes the window by
	 *  hand the scale is frozen so the clips inside keep their size instead of shrinking. */
	private double pxPerMs() {
		if (tlWidth > 0 && frozenPxPerMs > 0) {
			return frozenPxPerMs * zoom;
		}
		return tlW() * zoom / timelineLen;
	}

	private int tlXFor(float ms) {
		return tlX() + (int) Math.round((ms - viewOffsetMs) * pxPerMs());
	}

	private float tlTimeForX(int x) {
		return (float) Math.max(0d, viewOffsetMs + (x - tlX()) / pxPerMs());
	}

	private int btX0(CompositeClip c) { return tlXFor(Math.max(0, c.startMs())); }

	private static final int LANE_TOP = 6;   // gap between the ruler and the first lane
	private static final int LANE_H = 15;    // row height of one lane (clip height + 1px separator)
	private static final int ROW_H = 22;      // property row pitch (compact, short inputs)
	private static final int FIELD_H = 14;    // input field height
	private static final int HANDLE_H = 12;   // window title bar height
	private static final int BLOCK_H = 14;   // visible height of one clip block inside a lane
	private static final int TEXT_ROW_H = 14; // height of the text-object track pinned at the top
	private static final int CHILD_H = 11;   // height of one child-particle row under an expanded group

	/** Y of the first lane (before scrolling). */
	private int lanesTopY() { return timelineY() + LANE_TOP + TEXT_ROW_H; }

	/** Vertical space the lane area can use. */
	private int lanesViewH() { return Math.max(LANE_H, TIMELINE_H - LANE_TOP - TEXT_ROW_H); }

	/** True for a particle-group clip. */
	private static boolean isGroup(CompositeClip c) {
		return c != null && "particle_group".equals(c.effect());
	}

	/** True when a group clip is expanded on the timeline (its children get their own rows). */
	private static boolean isGroupExpanded(CompositeClip c) {
		if (!isGroup(c)) {
			return false;
		}
		com.google.gson.JsonElement e = clipParams(c).get("expanded");
		return e != null && e.getAsBoolean();
	}

	/** Toggles a group's expanded state (stored in the clip's params so it survives edits). */
	private void toggleGroupExpanded(CompositeClip c) {
		if (!isGroup(c)) {
			return;
		}
		com.google.gson.JsonObject o = clipParams(c);
		o.addProperty("expanded", !isGroupExpanded(c));
		CompositeClip updated = c.withParams(o.toString());
		replaceClip(c, updated);
		if (selectedClip == c) {
			selectedClip = updated;
		}
		init();
	}

	/** Pixel height added to a lane by its expanded group children. */
	private int laneExtraH(int laneIdx) {
		if (laneIdx < 0 || laneIdx >= lanes.size()) {
			return 0;
		}
		int h = 0;
		for (CompositeClip c : lanes.get(laneIdx)) {
			if (isGroupExpanded(c)) {
				h += Math.max(1, groupItems(c).size()) * CHILD_H;
			}
		}
		return h;
	}

	/** Total pixel height of one lane row (clips row + expanded children). */
	private int laneTotalH(int laneIdx) {
		return LANE_H + laneExtraH(laneIdx);
	}

	/** Pixel height of all lane rows (content height, used to clamp the scroll). */
	private int lanesContentH() {
		int h = 0;
		for (int i = 0; i < lanes.size(); i++) {
			h += laneTotalH(i);
		}
		return h;
	}

	/** Screen Y of the top of a lane row (scrolled). */
	private int laneTopOf(int laneIdx) {
		int y = lanesTopY() - laneScrollPx;
		for (int i = 0; i < laneIdx && i < lanes.size(); i++) {
			y += laneTotalH(i);
		}
		return y;
	}

	/** Lane index whose row (clips row or its child rows) contains {@code y}; {@code lanes.size()}
	 *  when the point is below every lane (used to grow a new lane on drop). */
	private int laneForY(int y) {
		int yy = lanesTopY() - laneScrollPx;
		for (int i = 0; i < lanes.size(); i++) {
			int h = laneTotalH(i);
			if (y >= yy && y < yy + h) {
				return i;
			}
			yy += h;
		}
		return lanes.size();
	}

	/** Clamp the vertical scroll so the bottom lanes stay reachable. */
	private void clampLaneScroll() {
		int max = Math.max(0, lanesContentH() - lanesViewH());
		laneScrollPx = Math.max(0, Math.min(max, laneScrollPx));
	}

	/** Width of the -/+ expand toggle drawn at the left of a group's timeline block. */
	private static final int GROUP_TOGGLE_W = 11;
	/** The toggle starts after the 4px left resize grip, so the grip stays usable on a group. */
	private static final int GROUP_TOGGLE_X = 5;

	/** True when the point is on a group clip's -/+ toggle (timeline-window coordinates). */
	private boolean inGroupToggle(CompositeClip c, int x, int y) {
		if (!isGroup(c)) {
			return false;
		}
		int lane = laneOf(c);
		if (lane < 0) {
			return false;
		}
		int raw0 = btX0(c);
		int x0 = Math.max(tlX(), raw0);
		int x1 = Math.min(tlX() + tlW(), raw0 + (int) (Math.max(16, c.durationMs()) * pxPerMs()));
		if (x1 - x0 <= GROUP_TOGGLE_X + GROUP_TOGGLE_W + 4) {
			return false; // too narrow to show the toggle
		}
		int y0 = laneTopOf(lane);
		return x >= x0 + GROUP_TOGGLE_X && x <= x0 + GROUP_TOGGLE_X + GROUP_TOGGLE_W
			&& y >= y0 && y <= y0 + BLOCK_H;
	}

	/** A child-particle row hit: which expanded group owns it, which item, and which part.
	 *  {@code edge}: -1 = body, 0 = left grip (moves the child), 1 = right grip (its length). */
	private record GroupChildHit(CompositeClip group, int index, int edge) {
	}

	/** Child bar rect in timeline-window coordinates ({@code [0]} = x0, {@code [1]} = x1). */
	private int[] childBarX(CompositeClip group, int idx) {
		float gx = group.startMs();
		float dur = Math.max(16f, group.durationMs());
		float s = (float) Math.min(groupItemStart(group, idx), Math.max(0f, dur - 16f));
		float d = (float) Math.min(groupItemDur(group, idx), Math.max(16f, dur - s));
		int x0 = tlXFor(gx + s);
		int x1 = tlXFor(gx + s + d);
		return new int[] { Math.max(tlX(), x0), Math.min(tlX() + tlW(), x1) };
	}

	/** Hit test for the child rows drawn under a lane's expanded group clips, or {@code null}. */
	private GroupChildHit hitGroupChild(int lane, int x, int y) {
		if (lane < 0 || lane >= lanes.size()) {
			return null;
		}
		int childTop = laneTopOf(lane) + LANE_H;
		if (y < childTop) {
			return null; // still inside the lane's own clip row
		}
		int rel = y - childTop;
		int acc = 0;
		for (CompositeClip c : lanes.get(lane)) {
			if (!isGroupExpanded(c)) {
				continue;
			}
			int h = Math.max(1, groupItems(c).size()) * CHILD_H;
			if (rel < acc + h) {
				int idx = (rel - acc) / CHILD_H;
				int[] bx = childBarX(c, idx);
				if (x < bx[0] || x > bx[1]) {
					return null;
				}
				int edge = -1;
				if (Math.abs(x - bx[0]) <= 4) {
					edge = 0;
				} else if (Math.abs(x - bx[1]) <= 4) {
					edge = 1;
				}
				return new GroupChildHit(c, idx, edge);
			}
			acc += h;
		}
		return null;
	}

	private void sortLane(int lane) {
		if (lane >= 0 && lane < lanes.size()) {
			lanes.get(lane).sort((a, b) -> Float.compare(a.startMs(), b.startMs()));
		}
	}

	/** All clips across every lane (in lane order). */
	private List<CompositeClip> allClips() {
		List<CompositeClip> all = new ArrayList<>();
		for (List<CompositeClip> lane : lanes) {
			all.addAll(lane);
		}
		return all;
	}

	/** Loads the externally supplied clips (once) — each one into its own lane. */
	private void loadInitialClips() {
		if (initialClipsLoaded || initialClips == null) {
			return;
		}
		initialClipsLoaded = true;
		for (JsonElement el : initialClips) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			if (!o.has("effect")) {
				continue;
			}
			String effect = o.get("effect").getAsString();
			float start = o.has("start") ? o.get("start").getAsFloat() : 0f;
			float dur = o.has("duration") ? o.get("duration").getAsFloat() : 1000f;
			float speed = o.has("speed") ? o.get("speed").getAsFloat() : 1f;
			List<CompositeClip> lane = new ArrayList<>();
			lane.add(new CompositeClip(effect, paletteName(effect), "", speed, dur, start));
			lanes.add(lane);
		}
	}

	/** 文本对象属性的字段顺序：位置 X/Y/Z、缩放、透明度（与属性面板的行一致）。 */
	private static final String[] TEXT_PROP_KEYS = { "x", "y", "z", "scale", "alpha" };

	/** Applies the text-object properties handed in by the caller (see {@link #dumpTextProps}). */
	private void loadInitialTextProps() {
		if (initialTextPropsLoaded || initialTextProps == null) {
			return;
		}
		initialTextPropsLoaded = true;
		for (int p = 0; p < PROP_COUNT; p++) {
			if (initialTextProps.has(TEXT_PROP_KEYS[p])) {
				propDefaults[p] = initialTextProps.get(TEXT_PROP_KEYS[p]).getAsFloat();
			}
		}
		if (initialTextProps.has("distanceScale")) {
			textDistanceScale = initialTextProps.get("distanceScale").getAsBoolean();
		}
		if (initialTextProps.has("durationMs")) {
			timelineLenOverride = Math.max(MIN_TIMELINE_LEN,
				Math.min(MAX_TIMELINE_LEN, initialTextProps.get("durationMs").getAsInt()));
		}
		if (initialTextProps.has("keys") && initialTextProps.get("keys").isJsonArray()) {
			for (JsonElement el : initialTextProps.getAsJsonArray("keys")) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				if (!o.has("v") || !o.get("v").isJsonArray() || o.getAsJsonArray("v").size() < PROP_COUNT) {
					continue;
				}
				TextKey k = new TextKey();
				k.timeMs = o.has("t") ? o.get("t").getAsInt() : 0;
				k.ease = o.has("ease") ? o.get("ease").getAsString() : "linear";
				JsonArray v = o.getAsJsonArray("v");
				for (int p = 0; p < PROP_COUNT; p++) {
					k.v[p] = v.get(p).getAsFloat();
				}
				textKeys.add(k);
			}
			sortTextKeys(textKeys);
		}
	}

	/** Serializes the text-object properties (位置 / 缩放 / 透明度 / 时长 / 距离缩放 + 共享关键帧). */
	private JsonObject dumpTextProps() {
		JsonObject o = new JsonObject();
		for (int p = 0; p < PROP_COUNT; p++) {
			o.addProperty(TEXT_PROP_KEYS[p], propDefaults[p]);
		}
		o.addProperty("distanceScale", textDistanceScale);
		o.addProperty("durationMs", timelineLen);
		if (!textKeys.isEmpty()) {
			JsonArray arr = new JsonArray();
			for (TextKey k : textKeys) {
				JsonObject ko = new JsonObject();
				ko.addProperty("t", k.timeMs);
				ko.addProperty("ease", k.ease);
				JsonArray v = new JsonArray();
				for (int p = 0; p < PROP_COUNT; p++) {
					v.add(k.v[p]);
				}
				ko.add("v", v);
				arr.add(ko);
			}
			o.add("keys", arr);
		}
		return o;
	}

	/** Serializes the timeline's clips back for the external caller (see {@link #onSave}). */
	private JsonArray dumpClips() {
		JsonArray arr = new JsonArray();
		for (CompositeClip c : allClips()) {
			JsonObject o = new JsonObject();
			o.addProperty("effect", c.effect());
			o.addProperty("start", c.startMs());
			o.addProperty("duration", c.durationMs());
			o.addProperty("speed", c.speed());
			arr.add(o);
		}
		return arr;
	}

	/** Display name of a palette entry (falls back to the raw key). */
	private static String paletteName(String key) {
		for (EffectPreset p : palette()) {
			if (p.key().equals(key)) {
				return p.name();
			}
		}
		return key;
	}

	private int laneOf(CompositeClip c) {
		for (int i = 0; i < lanes.size(); i++) {
			if (lanes.get(i).contains(c)) {
				return i;
			}
		}
		return -1;
	}

	/** Replace {@code old} with {@code updated} inside its lane (identity match). */
	private void replaceClip(CompositeClip old, CompositeClip updated) {
		for (int i = 0; i < lanes.size(); i++) {
			List<CompositeClip> lane = lanes.get(i);
			for (int j = 0; j < lane.size(); j++) {
				if (lane.get(j) == old) {
					lane.set(j, updated);
					sortLane(i);
					// an edit swaps the clip INSTANCE: carry the particle emission counters over,
					// otherwise the new instance would consider itself unspawned and re-emit
					// (that is why a 1-particle clip "kept summoning" while editing it)
					int[] done = emittedByClip.remove(old);
					if (done != null) {
						emittedByClip.put(updated, done);
					}
					return;
				}
			}
		}
	}

	private boolean overlapsIn(List<CompositeClip> lane, CompositeClip self, CompositeClip probe) {
		float s0 = probe.startMs();
		float e0 = s0 + Math.max(16, probe.durationMs());
		for (CompositeClip o : lane) {
			if (o == self) {
				continue;
			}
			float s1 = o.startMs();
			float e1 = s1 + Math.max(16, o.durationMs());
			if (s0 < e1 && s1 < e0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Closest legal start for a clip of {@code dur} inside {@code lane} that does not overlap
	 * the other clips. Falls back to {@code want} (unchanged) when every free window is too
	 * small, leaving a temporary overlap that later edits resolve.
	 */
	private float snapStart(List<CompositeClip> lane, CompositeClip self, float want, float dur) {
		float lo = 0f, hi = Math.max(0f, timelineLen - dur);
		float best = -1f;
		float bestDist = Float.MAX_VALUE;
		CompositeClip probe = new CompositeClip("", "", "", 1f, dur, want);
		if (!overlapsIn(lane, self, probe)) {
			return Math.max(lo, Math.min(hi, want));
		}
		// candidate gaps: from 0 and just after each other clip
		List<Float> cuts = new ArrayList<>();
		cuts.add(0f);
		for (CompositeClip o : lane) {
			if (o == self) {
				continue;
			}
			cuts.add(o.startMs() + Math.max(16, o.durationMs()));
			cuts.add(o.startMs() - dur);
		}
		for (float start : cuts) {
			start = Math.max(lo, Math.min(hi, start));
			CompositeClip p = new CompositeClip("", "", "", 1f, dur, start);
			if (!overlapsIn(lane, self, p)) {
				float d = Math.abs(start - want);
				if (d < bestDist) {
					bestDist = d;
					best = start;
				}
			}
		}
		return best < 0 ? Math.max(lo, Math.min(hi, want)) : best;
	}

	/** Clip in {@code lane} whose block starts right after {@code endMs} (may be null). */
	private static float nextStartAfter(List<CompositeClip> lane, float endMs) {
		float best = Float.MAX_VALUE;
		for (CompositeClip o : lane) {
			if (o.startMs() > endMs + 0.5f && o.startMs() < best) {
				best = o.startMs();
			}
		}
		return best;
	}

	/**
	 * Add a new clip to a lane, auto-creating lanes below it while the target spot is taken
	 * (fades that are pinned to an edge land on their own free lane this way).
	 * Returns the instance actually stored (its start may have snapped to a free spot).
	 */
	private CompositeClip addToLanes(CompositeClip clip, int preferredLane) {
		// find the first lane whose time window is free, starting near the preferred row and
		// wrapping to the top; only if every existing lane is taken do we grow a new lane.
		float dur = Math.max(16, clip.durationMs());
		int n = lanes.size();
		int best = -1;
		if (n > 0) {
			int from = Math.max(0, Math.min(preferredLane, n - 1));
			for (int l = from; l < n; l++) {
				if (!overlapsIn(lanes.get(l), null, clip)) {
					best = l;
					break;
				}
			}
			if (best < 0) {
				for (int l = 0; l < from; l++) {
					if (!overlapsIn(lanes.get(l), null, clip)) {
						best = l;
						break;
					}
				}
			}
		}
		if (best < 0) {
			lanes.add(new ArrayList<>()); // grow only when truly needed (no empty placeholder rows)
			best = lanes.size() - 1;
		}
		List<CompositeClip> list = lanes.get(best);
		float start = snapStart(list, null, clip.startMs(), dur);
		CompositeClip placed = clip.withStart(start);
		list.add(placed);
		sortLane(best);
		return placed;
	}

	/** Remove lanes that no longer hold any clip (rows are automatic: needed → grow, unused → gone). */
	private void removeEmptyLanes() {
		lanes.removeIf(List::isEmpty);
		clampLaneScroll();
	}

	/** The clip in {@code lane} whose visible block contains x (top-most/hit), else null. */
	private CompositeClip hitClip(List<CompositeClip> lane, int x) {
		for (CompositeClip c : lane) {
			int raw0 = btX0(c);
			int wPx = (int) (Math.max(16, c.durationMs()) * pxPerMs());
			int x0 = Math.max(tlX(), raw0);
			int x1 = Math.min(tlX() + tlW(), raw0 + wPx);
			if (x1 > x0 && x >= x0 && x <= x1) {
				return c;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ property panel (numeric fields)

	private boolean isParticleSelected() {
		return selectedClip != null && selectedClip.effect().startsWith("particle_");
	}

	private boolean isParticle3D() {
		return selectedClip != null && "particle_3d".equals(selectedClip.effect());
	}

	private boolean isParticleGroup() {
		return selectedClip != null && "particle_group".equals(selectedClip.effect());
	}

	// vertical layout (Y) of each property row for the currently selected clip (26px pitch;
	// labels are drawn 11px above each field so they no longer touch the box)
	/** Name row (only while a clip is selected) — the clip's editable timeline name. */
	private int nameY() { return TOP_H + 32 - propScroll; }
	/** Speed row: one pitch lower when the name row is present (a selected group CHILD has no
	 *  name/speed rows — the panel then shows that child's own properties instead). */
	private int speedY() { return nameY() + (selectedClip != null && !hasGroupChild() ? ROW_H : 0); }
	private int particleY() { return hasGroupChild() ? nameY() : speedY() + ROW_H; }
	/** Row Y of a particle param field (0 = the first row below the particle/registry row). */
	private int p3dParamY(int k) { return particleY() + ROW_H + k * ROW_H; }
	/** Entry/exit clips have no speed row — only an animation duration. */
	private boolean hideSpeed() { return selectedClip != null && isPinned(selectedClip); }
	/** True when a child particle of the selected group is selected (its rows are shown). */
	private boolean hasGroupChild() {
		return isParticleGroup() && selectedChild >= 0 && selectedChild < groupCount();
	}

	// ---- row indices -----------------------------------------------------------------------
	// group (nothing selected inside it): 添加粒子 / 导入指令 / 保存组 JSON
	// group CHILD (selected on the timeline): 粒子 / 数量 / 散布X/Y/Z / 速度 / 起始 / 时长 / 移除
	private static final int GROUP_ROWS = 3;
	private static final int CHILD_REMOVE_ROW = 7; // 子粒子(0行) + 数量…速度(5) + 起始 + 时长
	/** Emission rows of a single particle: 数量 / 散布X / 散布Y / 散布Z / 速度 / 导入指令. */
	private static final int SINGLE_EMIT_ROWS = 6;

	/** 起始/时长 rows of the selected group child (its own window inside the group). */
	private int childStartY() { return p3dParamY(5); }
	private int childDurY() { return p3dParamY(6); }

	private int durY() {
		if (hasGroupChild()) {
			// child mode has no 动画时长 row: the last row is its own 移除该粒子, so the object
			// property block (which the child does not use) starts right below it
			return childRemoveY();
		}
		if (!isParticleSelected()) {
			return hideSpeed() ? speedY() : speedY() + ROW_H;
		}
		if (isParticleGroup()) {
			return p3dParamY(GROUP_ROWS);
		}
		return p3dParamY(SINGLE_EMIT_ROWS);
	}
	/** 移除该条 sits at the very bottom, just below whichever property rows are shown. */
	private int removeY() { return propRowBaseY() + propShownRows() * ROW_H; }
	/** The child's own 移除该粒子 button (child mode only). */
	private int childRemoveY() { return p3dParamY(CHILD_REMOVE_ROW); }

	/** Every particle registry id available on the client (vanilla + other mods), sorted. */
	private static List<String> registryIds;
	private static List<String> registryParticleIds() {
		if (registryIds == null) {
			List<String> ids = new ArrayList<>();
			for (ResourceLocation rl : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
				ids.add(rl.toString());
			}
			ids.sort(Comparator.naturalOrder());
			registryIds = ids;
		}
		return registryIds;
	}

	private void buildPropertyPanel() {
		int wx = propX() + PROP_INSET + propDx; // widget x (baked window offset; widgets draw outside the pose)
		// right edge = propX()+PROP_INSET+pw, the panel ends at propX()+PROP_W-6 → keep a 2px gutter
		// so buttons/fields can never stick out of the panel
		int pw = PROP_W - PROP_INSET - 8;
		int wy = propDy;
		if (selectedClip != null && !hasGroupChild()) {
			// editable timeline name (the label shown on the clip's block). Only a REAL change is
			// applied: init() re-fills this box, and a programmatic setValue must not replace the
			// clip instance (that used to break an in-progress timeline drag).
			SearchField nameField = new SearchField(this.font, wx, wy + nameY(), pw, FIELD_H, L10n.tr("anima.ui.prop.clip_name"), s -> {
				if (selectedClip != null && !s.isBlank() && !s.equals(selectedClip.displayName())) {
					setClip(selectedClip, selectedClip.withName(s));
				}
			});
			nameField.setValue(selectedClip.displayName());
			addRenderableWidget(nameField);
		if (!hideSpeed()) {
			addRenderableWidget(new NumberField(this.font, wx, wy + speedY(), pw, FIELD_H, 0, 100, 1, L10n.tr("anima.ui.prop.speed_mul"), Math.round(selectedClip.speed()),
				v -> { if (selectedClip != null) setClip(selectedClip, selectedClip.withSpeed(v)); }));
		}
		if (isParticleSelected() && isParticle3D()) {
			// vanilla /particle emission params of this one particle: count + delta (spread) + speed
			addRenderableWidget(new NumberField(this.font, wx, wy + p3dParamY(0), pw, FIELD_H, 0, 500, 1,
				L10n.tr("anima.ui.prop.count"), p3dParam(selectedClip, "count", 1), v -> setParam("count", v)));
			addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(1), pw, FIELD_H, 0, 10, 0.05,
				L10n.tr("anima.ui.prop.spread_x"), param(selectedClip, "dx", 0), v -> setParam("dx", v)));
			addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(2), pw, FIELD_H, 0, 10, 0.05,
				L10n.tr("anima.ui.prop.spread_y"), param(selectedClip, "dy", 0), v -> setParam("dy", v)));
			addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(3), pw, FIELD_H, 0, 10, 0.05,
				L10n.tr("anima.ui.prop.spread_z"), param(selectedClip, "dz", 0), v -> setParam("dz", v)));
			addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(4), pw, FIELD_H, 0, 10, 0.01,
				L10n.tr("anima.ui.prop.speed"), param(selectedClip, "speed", 0), v -> setParam("speed", v)));
			addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(5), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.button.import_command_short")), b -> importParticleCommand()));
		}
		if (isParticleGroup()) {
			if (hasGroupChild()) {
				// A CHILD selected on the timeline: the panel edits THAT child, not the group
				// (the group's own behaviour is edited by clicking the group block instead).
				final int idx = selectedChild;
				// the child's particle registry id is editable like the clip name (⌨ type a
				// vanilla / mod id), and the small ▾ still opens the registry picker
				SearchField childId = new SearchField(this.font, wx, wy + particleY(), pw - 18, FIELD_H,
					L10n.tr("anima.ui.prop.particle_id"), s -> {
						if (selectedClip != null && !s.isBlank() && !s.equals(groupItemId(selectedClip, idx))) {
							setGroupItemId(idx, s);
						}
					});
				childId.setValue(groupItemId(selectedClip, idx));
				addRenderableWidget(childId);
				addRenderableWidget(ThemeButton.of(wx + pw - 16, wy + particleY(), 16, FIELD_H,
					Component.literal("▾"), b -> openParticlePicker()));
				addRenderableWidget(new NumberField(this.font, wx, wy + p3dParamY(0), pw, FIELD_H, 0, 500, 1,
					L10n.tr("anima.ui.prop.count"), (int) emissionValue("count", 1), v -> setEmission("count", v)));
				addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(1), pw, FIELD_H, 0, 10, 0.05,
					L10n.tr("anima.ui.prop.spread_x"), emissionValue("dx", 0), v -> setEmission("dx", v)));
				addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(2), pw, FIELD_H, 0, 10, 0.05,
					L10n.tr("anima.ui.prop.spread_y"), emissionValue("dy", 0), v -> setEmission("dy", v)));
				addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(3), pw, FIELD_H, 0, 10, 0.05,
					L10n.tr("anima.ui.prop.spread_z"), emissionValue("dz", 0), v -> setEmission("dz", v)));
				addRenderableWidget(new DecimalField(this.font, wx, wy + p3dParamY(4), pw, FIELD_H, 0, 10, 0.01,
					L10n.tr("anima.ui.prop.speed"), emissionValue("speed", 0), v -> setEmission("speed", v)));
				// its own window inside the group (never longer than the group itself)
				int gDur = Math.round(Math.max(16f, selectedClip.durationMs()));
				addRenderableWidget(new NumberField(this.font, wx, wy + childStartY(), pw, FIELD_H, 0, gDur, 10,
					L10n.tr("anima.ui.prop.start"), (int) groupItemStart(selectedClip, idx), v -> setGroupItemParam(idx, "s", clampChildStart(idx, v))));
				addRenderableWidget(new NumberField(this.font, wx, wy + childDurY(), pw, FIELD_H, 16, gDur, 10,
					L10n.tr("anima.ui.prop.child_duration"), (int) groupItemDur(selectedClip, idx), v -> setGroupItemParam(idx, "d", clampChildDur(idx, v))));
				addRenderableWidget(ThemeButton.of(wx, wy + childRemoveY(), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.button.remove_particle")), b -> {
					if (selectedClip != null) {
						CompositeClip u = selectedClip.withParams(withGroupItemRemoved(selectedClip, idx));
						replaceClip(selectedClip, u);
						selectedClip = u;
						selectedChild = -1;
						init();
					}
				}));
			} else {
				// The group's particles live ON THE TIMELINE (they get their own child rows when the
				// group is expanded); this panel only manages them as a whole. Expanding is done by
				// the -/+ on the group block — no button for it here.
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(0), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.particle.add")), b -> {
					selectedChild = -1;
					openParticlePicker();
				}));
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(1), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.button.import_command_short")), b -> importParticleCommand()));
				// save the group as a shareable JSON (it then appears in the picker's saved-group list)
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(2), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.particle.save_group")), b -> {
					if (selectedClip != null) {
						saveGroupJson(selectedClip);
					}
				}));
			}
		}
		if (!hasGroupChild()) {
			addRenderableWidget(new NumberField(this.font, wx, wy + durY(), pw, FIELD_H, 16, Math.max(16, timelineLen), 50, L10n.tr("anima.ui.prop.duration"), Math.round(selectedClip.durationMs()),
				v -> { if (selectedClip != null) setClip(selectedClip, selectedClip.withDuration(v)); }));
			addRenderableWidget(ThemeButton.of(wx, wy + removeY(), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.button.remove_clip")), b -> {
				if (selectedClip != null) {
					removeClip(selectedClip);
				}
				init();
			}));
		}
		}
		buildPropFields(wx, wy);
	}

	/** The child's 起始 must leave room for its current length inside the group. */
	private double clampChildStart(int idx, double v) {
		if (selectedClip == null) {
			return v;
		}
		double gDur = Math.max(16, selectedClip.durationMs());
		double d = Math.min(groupItemDur(selectedClip, idx), gDur);
		return Math.max(0, Math.min(Math.max(0, gDur - d), v));
	}

	/** The child's 时长 can never run past the group's end. */
	private double clampChildDur(int idx, double v) {
		if (selectedClip == null) {
			return v;
		}
		double gDur = Math.max(16, selectedClip.durationMs());
		double s = groupItemStart(selectedClip, idx);
		return Math.max(16, Math.min(Math.max(16, gDur - s), v));
	}


	/** Replace a clip's data inside its lane; entry/exit clips stay glued to the timeline edges.
	 *  A field edit while the user is dragging (name box, count, …) swaps the clip INSTANCE —
	 *  the drag pointers follow it, otherwise the drag would silently stop working. */
	private void setClip(CompositeClip old, CompositeClip updated) {
		if (laneOf(old) >= 0) {
			CompositeClip anchored = pinAnchor(updated);
			replaceClip(old, anchored);
			if (selectedClip == old) {
				selectedClip = anchored;
			}
			if (movingClip == old) {
				movingClip = anchored;
			}
			if (resizingClip == old) {
				resizingClip = anchored;
			}
			if (ctxClip == old) {
				ctxClip = anchored;
			}
		}
	}

	/** Entry clips (淡入 / 打字入场 / 各类逐字入场) pin to the timeline start. */
	private static boolean isEntry(CompositeClip c) {
		return c != null && ("fade_in".equals(c.effect()) || isCharEntry(c.effect()));
	}

	/** Exit clips (淡出 / 打字出场) pin to the timeline end. */
	private static boolean isExit(CompositeClip c) {
		return c != null && ("fade_out".equals(c.effect()) || "typewriter_out".equals(c.effect()));
	}

	/** True for the character-level ENTRY animations (they always start at 0 ms). */
	private static boolean isCharEntry(String effect) {
		return switch (effect) {
			case "typewriter_in", "char_fall_in", "char_fall_random_in", "char_drift_in" -> true;
			default -> false;
		};
	}

	/** True for clips whose position is locked to a timeline edge. */
	private static boolean isPinned(CompositeClip c) {
		return isEntry(c) || isExit(c);
	}

	/** Entry clips stick to 0 ms, exit clips stick to the timeline length. */
	private CompositeClip pinAnchor(CompositeClip c) {
		if (isEntry(c)) {
			return c.withStart(0f);
		}
		if (isExit(c)) {
			return c.withStart(Math.max(0f, timelineLen - Math.max(16, c.durationMs())));
		}
		return c;
	}

	/** Int value of one key of a clip's JSON params (default when missing or unparsable). */
	private static int p3dParam(CompositeClip c, String key, int dflt) {
		return (int) Math.round(param(c, key, dflt));
	}

	/** Numeric value of one key of a clip's JSON params (default when missing or unparsable). */
	private static double param(CompositeClip c, String key, double dflt) {
		if (c.params() == null || c.params().isBlank()) {
			return dflt;
		}
		try {
			JsonObject o = JsonParser.parseString(c.params()).getAsJsonObject();
			if (o.has(key)) {
				return o.get(key).getAsDouble();
			}
		} catch (RuntimeException ignored) {
		}
		return dflt;
	}

	/** Numeric value of one emission key of the clip's own particle (single-particle clips). */
	private static double emitParam(CompositeClip c, String key, double dflt) {
		if ("count".equals(key)) {
			return p3dParam(c, "count", (int) dflt);
		}
		return param(c, key, dflt);
	}

	/** Numeric value of one emission key of a group child entry. */
	private static double groupItemParam(CompositeClip c, int i, String key, double dflt) {
		com.google.gson.JsonArray a = groupItems(c);
		if (i < 0 || i >= a.size() || !a.get(i).isJsonObject()) {
			return dflt;
		}
		com.google.gson.JsonElement e = a.get(i).getAsJsonObject().get(key);
		return e == null ? dflt : e.getAsDouble();
	}

	/** Delay (ms) of one child particle inside its group's time range. */
	private static double groupItemStart(CompositeClip c, int i) {
		return Math.max(0, groupItemParam(c, i, "s", 0));
	}

	/** Length (ms) of one child particle — defaults to the whole group length. */
	private static double groupItemDur(CompositeClip c, int i) {
		double d = groupItemParam(c, i, "d", 0);
		return d <= 0 ? Math.max(16, c.durationMs()) : d;
	}

	/** Writes one numeric key of a group child (used by the panel and by the timeline drags). */
	private void setGroupItemParam(int idx, String key, double value) {
		if (selectedClip == null || !isParticleGroup()) {
			return;
		}
		CompositeClip old = selectedClip;
		CompositeClip updated = old.withParams(withGroupItemNumber(old, idx, key, value));
		replaceClip(old, updated);
		if (selectedClip == old) {
			selectedClip = updated;
		}
		if (childResizeClip == old) {
			childResizeClip = updated;
		}
		if (movingClip == old) {
			movingClip = updated;
		}
		if (resizingClip == old) {
			resizingClip = updated;
		}
		if (ctxClip == old) {
			ctxClip = updated;
		}
	}

	/** Overwrites one numeric key of a group child entry (creates it when absent). */
	private static String withGroupItemNumber(CompositeClip c, int idx, String key, double value) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		if (idx >= 0 && idx < a.size() && a.get(idx).isJsonObject()) {
			a.get(idx).getAsJsonObject().addProperty(key, value);
			o.add("items", a);
		}
		return o.toString();
	}

	/** Renames a group child — its particle registry id (typed straight into the panel). */
	private void setGroupItemId(int idx, String id) {
		if (selectedClip == null || !isParticleGroup()) {
			return;
		}
		CompositeClip old = selectedClip;
		com.google.gson.JsonObject o = clipParams(old);
		com.google.gson.JsonArray a = groupItems(old);
		if (idx < 0 || idx >= a.size() || !a.get(idx).isJsonObject()) {
			return;
		}
		a.get(idx).getAsJsonObject().addProperty("id", id);
		o.add("items", a);
		CompositeClip updated = old.withParams(o.toString());
		replaceClip(old, updated);
		if (selectedClip == old) {
			selectedClip = updated;
		}
		if (childResizeClip == old) {
			childResizeClip = updated;
		}
		if (movingClip == old) {
			movingClip = updated;
		}
		if (resizingClip == old) {
			resizingClip = updated;
		}
		if (ctxClip == old) {
			ctxClip = updated;
		}
	}

	/** Update one key of the selected clip's JSON params, keeping the other keys. */
	private void setParam(String key, double value) {
		if (selectedClip == null || !isParticleSelected()) {
			return;
		}
		CompositeClip old = selectedClip;
		JsonObject o;
		try {
			o = (old.params() != null && !old.params().isBlank())
				? JsonParser.parseString(old.params()).getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			o = new JsonObject();
		}
		o.addProperty(key, value);
		CompositeClip updated = old.withParams(o.toString());
		replaceClip(old, updated);
		if (selectedClip == old) {
			selectedClip = updated;
		}
	}

	/** Applies an emission-key edit to the single particle, or to the selected group child. */
	private void setEmission(String key, double value) {
		if (selectedClip == null) {
			return;
		}
		if (isParticleGroup()) {
			if (!hasGroupChild()) {
				return;
			}
			final int idx = selectedChild;
			CompositeClip updated = selectedClip.withParams(withGroupItemNumber(selectedClip, idx, key, value));
			replaceClip(selectedClip, updated);
			selectedClip = updated;
		} else if (isParticle3D()) {
			setParam(key, value);
		}
	}

	/** Emission-key value shown for the selection (single particle, or the selected group child). */
	private double emissionValue(String key, double dflt) {
		if (selectedClip == null) {
			return dflt;
		}
		if (isParticleGroup()) {
			return hasGroupChild() ? groupItemParam(selectedClip, selectedChild, key, dflt) : dflt;
		}
		return emitParam(selectedClip, key, dflt);
	}

	private void removeClip(CompositeClip c) {
		int lane = laneOf(c);
		if (lane >= 0) {
			lanes.get(lane).remove(c);
			removeEmptyLanes(); // rows are automatic — drop the lane once it has no clips
		}
		if (selectedClip == c) {
			selectedClip = null;
		}
		if (ctxClip == c) {
			ctxClip = null;
		}
	}

	/** Copy a clip slightly after the original on the same lane (or the next free lane). Fades
	 *  are re-pinned to their timeline edge so the copy never drifts into the middle. */
	private void duplicateClip(CompositeClip c) {
		int lane = Math.max(0, laneOf(c));
		CompositeClip copy;
		if (isPinned(c)) {
			copy = pinAnchor(c);
		} else {
			float maxStart = Math.max(0f, timelineLen - Math.max(16, c.durationMs()));
			copy = c.withStart(Math.min(maxStart, c.startMs() + 60));
		}
		selectedClip = addToLanes(copy, lane);
		ctxClip = null;
	}

	/** The particle id to display/use for a clip: explicit override, else a sensible default. */
	private static String resolveParticle(CompositeClip c) {
		if (c.particle() != null && !c.particle().isBlank()) {
			return c.particle();
		}
		if ("particle_3d".equals(c.effect())) {
			return "minecraft:flame";
		}
		if ("particle_group".equals(c.effect())) {
			com.google.gson.JsonArray a = groupItems(c);
			if (a.size() > 0 && a.get(0).isJsonObject()) {
				com.google.gson.JsonElement id = a.get(0).getAsJsonObject().get("id");
				if (id != null) {
					return id.getAsString();
				}
			}
			return "minecraft:poof";
		}
		return particleKind(c.effect());
	}

	// ------------------------------------------------------------------ particle groups (粒子团)

	private static com.google.gson.JsonObject clipParams(CompositeClip c) {
		try {
			return (c.params() != null && !c.params().isBlank())
				? com.google.gson.JsonParser.parseString(c.params()).getAsJsonObject()
				: new com.google.gson.JsonObject();
		} catch (RuntimeException e) {
			return new com.google.gson.JsonObject();
		}
	}

	/** Particle entries of a group clip: [{id, count, dx, dy, dz}, ...]. */
	private static com.google.gson.JsonArray groupItems(CompositeClip c) {
		com.google.gson.JsonElement e = clipParams(c).get("items");
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new com.google.gson.JsonArray();
	}

	private int groupCount() {
		return selectedClip == null ? 0 : groupItems(selectedClip).size();
	}

	private static String groupItemId(CompositeClip c, int i) {
		com.google.gson.JsonArray a = groupItems(c);
		if (i < 0 || i >= a.size() || !a.get(i).isJsonObject()) {
			return "?";
		}
		com.google.gson.JsonElement id = a.get(i).getAsJsonObject().get("id");
		return id == null ? "?" : id.getAsString();
	}

	private static int groupItemCount(CompositeClip c, int i) {
		com.google.gson.JsonArray a = groupItems(c);
		if (i < 0 || i >= a.size() || !a.get(i).isJsonObject()) {
			return 1;
		}
		com.google.gson.JsonElement n = a.get(i).getAsJsonObject().get("count");
		return n == null ? 1 : n.getAsInt();
	}

	/** Adds one registry particle to a group clip; returns the new params JSON. A group that
	 *  receives its first particle expands on the timeline, so the new row is immediately visible. */
	private static String withGroupItemAdded(CompositeClip c, String id) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		com.google.gson.JsonObject item = new com.google.gson.JsonObject();
		item.addProperty("id", id);
		item.addProperty("count", 1);
		a.add(item);
		o.add("items", a);
		if (a.size() == 1) {
			o.addProperty("expanded", true);
		}
		return o.toString();
	}

	/** Overwrites the registry particle of one child entry (used when a child row is selected). */
	private static String withGroupItemId(CompositeClip c, int idx, String id) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		if (idx >= 0 && idx < a.size() && a.get(idx).isJsonObject()) {
			a.get(idx).getAsJsonObject().addProperty("id", id);
			o.add("items", a);
		}
		return o.toString();
	}

	/** Imports a vanilla {@code /particle …} command into the selection:
	 *  {@code particle <id> <pos> <deltaX deltaY deltaZ> <speed> <count>}. Positions are ignored
	 *  (the clip's own offset/gizmo is what places the emitter). */
	private void importParticleCommand() {
		if (selectedClip == null || !isParticleSelected()) {
			return;
		}
		// the clipboard is only a convenience pre-fill — the text is edited in our own popup
		String seed = Minecraft.getInstance().keyboardHandler.getClipboard();
		cmdEditorOpen = true;
		cmdSeed = seed == null ? "" : seed.trim();
		init();
	}

	/** Turns the typed command into this selection (single particle / selected child / new child). */
	private void applyCommandText(String raw) {
		if (selectedClip == null || !isParticleSelected()) {
			return;
		}
		if (raw == null || raw.isBlank()) {
			showNotice(L10n.tr("anima.ui.cmd.placeholder"));
			return;
		}
		String[] t = raw.trim().replace('/', ' ').trim().split("\\s+");
		int i = 0;
		if (i < t.length && "particle".equalsIgnoreCase(t[i])) {
			i++;
		}
		if (i >= t.length) {
			showNotice(L10n.tr("anima.ui.error.cmd_missing_name"));
			return;
		}
		String id = t[i++];
		if (i + 7 > t.length) {
			showNotice(L10n.tr("anima.ui.error.cmd_missing_fields"));
			return;
		}
		i += 3; // x y z (ignored — this clip's own offset places the emitter)
		double dx = num(t[i++]);
		double dy = num(t[i++]);
		double dz = num(t[i++]);
		double speed = num(t[i++]);
		int count = Math.max(0, (int) Math.round(num(t[i])));
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		if (WorldParticles.optionsOf(id) == null) {
			showNotice(L10n.tr("anima.ui.error.unknown_particle") + id + L10n.tr("anima.ui.suffix.written_by_name"));
		}
		if (isParticleGroup()) {
			if (hasGroupChild()) {
				final int idx = selectedChild;
				setGroupItemParam(idx, "dx", dx);
				setGroupItemParam(idx, "dy", dy);
				setGroupItemParam(idx, "dz", dz);
				setGroupItemParam(idx, "speed", speed);
				setGroupItemParam(idx, "count", count);
				if (selectedClip != null) {
					CompositeClip u = selectedClip.withParams(withGroupItemId(selectedClip, idx, id));
					replaceClip(selectedClip, u);
					selectedClip = u;
				}
			} else {
				com.google.gson.JsonObject item = new com.google.gson.JsonObject();
				item.addProperty("id", id);
				item.addProperty("count", count);
				item.addProperty("dx", dx);
				item.addProperty("dy", dy);
				item.addProperty("dz", dz);
				item.addProperty("speed", speed);
				CompositeClip u = selectedClip.withParams(withGroupItems(selectedClip, appendItem(selectedClip, item)));
				replaceClip(selectedClip, u);
				selectedClip = u;
				selectedChild = groupCount() - 1;
			}
		} else {
			setParam("dx", dx);
			setParam("dy", dy);
			setParam("dz", dz);
			setParam("speed", speed);
			setParam("count", count);
			if (selectedClip != null) {
				CompositeClip u = selectedClip.withParticle(id);
				replaceClip(selectedClip, u);
				selectedClip = u;
			}
		}
		showNotice(L10n.tr("anima.ui.notice.imported_particle") + id + " ×" + count);
	}

	/** Appends one item object to a group clip's items array. */
	private static com.google.gson.JsonArray appendItem(CompositeClip c, com.google.gson.JsonObject item) {
		com.google.gson.JsonArray a = groupItems(c).deepCopy();
		a.add(item);
		return a;
	}

	/** Parses one command token: {@code ~}/{@code ~x} → 0, else the number. */
	private static double num(String s) {
		if (s == null || s.isEmpty() || s.startsWith("~") || s.startsWith("^")) {
			return 0;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String withGroupItemRemoved(CompositeClip c, int idx) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		if (idx >= 0 && idx < a.size()) {
			a.remove(idx);
		}
		o.add("items", a);
		return o.toString();
	}

	/** Sets a group clip's items to the given array (replaces the old list). */
	private static String withGroupItems(CompositeClip c, com.google.gson.JsonArray items) {
		com.google.gson.JsonObject o = clipParams(c);
		o.add("items", items);
		return o.toString();
	}

	/** Converts a single particle clip into a group clip holding the given items (expanded so the
	 *  children show up on the timeline right away). */
	private static CompositeClip toGroupClip(CompositeClip c, com.google.gson.JsonArray items) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.add("items", items);
		o.addProperty("expanded", true);
		return CompositeClip.of("particle_group", L10n.tr("anima.ui.prop.particle_group"))
			.withDuration(c.durationMs()).withStart(c.startMs()).withParams(o.toString());
	}

	/** Applies a picker row: a saved group loads its items, a registry particle is set / appended. */
	private void applyParticlePick(PickerEntry e) {
		CompositeClip old = selectedClip;
		if (old == null) {
			return;
		}
		if (e.isGroup()) {
			com.google.gson.JsonArray items = new com.google.gson.JsonArray();
			com.google.gson.JsonElement arr = e.groupJson() == null ? null : e.groupJson().get("items");
			if (arr != null && arr.isJsonArray()) {
				items = arr.getAsJsonArray();
			}
			CompositeClip updated = "particle_group".equals(old.effect())
				? old.withParams(withGroupItems(old, items))
				: toGroupClip(old, items);
			replaceClip(old, updated);
			if (selectedClip == old) {
				selectedClip = updated;
				selectedChild = -1;
			}
			showNotice(L10n.tr("anima.ui.notice.imported_group") + e.label());
		} else {
			CompositeClip updated;
			if ("particle_group".equals(old.effect())) {
				// a selected child row is re-pointed; otherwise the particle is appended
				updated = (selectedChild >= 0 && selectedChild < groupCount())
					? old.withParams(withGroupItemId(old, selectedChild, e.id()))
					: old.withParams(withGroupItemAdded(old, e.id()));
			} else {
				updated = old.withParticle(e.id());
			}
			replaceClip(old, updated);
			if (selectedClip == old) {
				selectedClip = updated;
			}
		}
		init();
	}

	/** Saves the selected group clip as a shareable JSON (config/anima/
	 *  particle_groups/) and copies it to the clipboard; it then shows up in the picker. */
	private void saveGroupJson(CompositeClip c) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		String name = c.displayName() == null || c.displayName().isBlank() ? L10n.tr("anima.ui.prop.particle_group") : c.displayName();
		o.addProperty("name", name);
		o.add("items", groupItems(c));
		String path = AnimationConfigStore.get().saveParticleGroup(name, o);
		Minecraft.getInstance().keyboardHandler.setClipboard(o.toString());
		showNotice(L10n.tr("anima.ui.notice.saved_group") + path);
		refreshPickerGroups();
	}

	/** Adds a clip from a saved particle-group JSON (palette row) at the playhead. */
	private void addSavedGroupClip(PaletteGroup pg) {
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		com.google.gson.JsonElement arr = pg.json() == null ? null : pg.json().get("items");
		if (arr != null && arr.isJsonArray()) {
			items = arr.getAsJsonArray();
		}
		int dur = 1000;
		float start = Math.max(0f, Math.min(Math.max(0, timelineLen - dur), playTimeMs));
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.add("items", items);
		o.addProperty("expanded", true);
		CompositeClip clip = CompositeClip.of("particle_group", pg.name())
			.withDuration(dur).withStart(start).withParams(o.toString());
		selectedClip = addToLanes(clip, Math.max(0, laneForY(lanesTopY())));
		selectedChild = -1;
		clampLaneScroll();
		showNotice(L10n.tr("anima.ui.notice.group_added") + pg.name());
		init();
	}

	/** Creates a fresh particle clip (single particle or group) at the playhead and selects it. */
	private void addParticleClip(String effect, String name, String params) {
		int dur = 1000;
		float start = Math.max(0f, Math.min(Math.max(0, timelineLen - dur), playTimeMs));
		CompositeClip clip = CompositeClip.of(effect, name).withDuration(dur).withStart(start);
		if (params != null) {
			clip = clip.withParams(params);
		}
		if ("particle_3d".equals(effect)) {
			clip = clip.withParticle("minecraft:flame"); // default registry particle
		}
		selectedClip = addToLanes(clip, Math.max(0, laneForY(lanesTopY())));
		clampLaneScroll();
		init();
	}


	// ------------------------------------------------------------------ right-click context menu (竖排下拉)

	private static final int CTX_W = 62;
	private static final int CTX_ROW_H = 13;
	private static final int CTX_PAD_TOP = 2; // keeps the accent line clear of the first row's highlight

	/** Menu row under a timeline-window point, or -1 (shared by drawing and clicking). */
	private int ctxRowAt(int x, int y) {
		if (x < ctxX || x > ctxX + CTX_W) {
			return -1;
		}
		int rel = y - ctxY - CTX_PAD_TOP;
		if (rel < 0) {
			return -1;
		}
		int row = rel / CTX_ROW_H;
		return row < ctxRows() ? row : -1;
	}

	/** Menu rows: a right-clicked CHILD row offers just 删除该粒子; a single particle also gets 创建组. */
	private int ctxRows() {
		if (ctxClip == null) {
			return 0;
		}
		if (ctxChild >= 0) {
			return 1;
		}
		return "particle_3d".equals(ctxClip.effect()) ? 3 : 2;
	}

	private int ctxMenuH() {
		return ctxRows() * CTX_ROW_H + CTX_PAD_TOP + 2;
	}
	private String ctxRowLabel(int row) {
		if (ctxChild >= 0) {
			return L10n.tr("anima.ui.button.delete_particle");
		}
		if (row == 0) {
			return L10n.tr("anima.ui.menu.copy");
		}
		if (row == 1 && "particle_3d".equals(ctxClip != null ? ctxClip.effect() : "")) {
			return L10n.tr("anima.ui.menu.create_group");
		}
		return L10n.tr("anima.ui.menu.delete");
	}

	/** Runs the context-menu action for {@code row}; the menu closes afterwards. */
	private void ctxAction(int row) {
		if (ctxClip == null || laneOf(ctxClip) < 0) {
			ctxClip = null;
			ctxChild = -1;
			return;
		}
		if (ctxChild >= 0) {
			// remove this child particle from the group (children are managed on the timeline)
			final int idx = ctxChild;
			CompositeClip updated = ctxClip.withParams(withGroupItemRemoved(ctxClip, idx));
			replaceClip(ctxClip, updated);
			if (selectedClip == ctxClip) {
				selectedClip = updated;
				selectedChild = -1;
			}
		} else if (row == 0) {
			duplicateClip(ctxClip);
		} else if (row == 1 && "particle_3d".equals(ctxClip.effect())) {
			createGroupFrom(ctxClip);
		} else {
			removeClip(ctxClip);
		}
		ctxClip = null;
		ctxChild = -1;
		init();
	}

	/** Wraps a single particle clip into a particle group holding that particle (same time range). */
	private void createGroupFrom(CompositeClip c) {
		if (c == null || !"particle_3d".equals(c.effect())) {
			return;
		}
		int lane = laneOf(c);
		if (lane < 0) {
			return;
		}
		com.google.gson.JsonObject item = new com.google.gson.JsonObject();
		item.addProperty("id", resolveParticle(c));
		item.addProperty("count", p3dParam(c, "count", 1));
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		items.add(item);
		com.google.gson.JsonObject params = new com.google.gson.JsonObject();
		params.add("items", items);
		CompositeClip group = CompositeClip.of("particle_group", L10n.tr("anima.ui.prop.particle_group"))
			.withDuration(c.durationMs()).withStart(c.startMs()).withParams(params.toString());
		List<CompositeClip> list = lanes.get(lane);
		list.set(list.indexOf(c), group);
		sortLane(lane);
		selectedClip = group;
		showNotice(L10n.tr("anima.ui.notice.group_created"));
	}

	/** Draws the open context menu near the clicked clip (timeline-window coordinates). */
	private void renderContextMenu(GuiGraphics g, int mouseX, int mouseY) {
		if (ctxClip == null || laneOf(ctxClip) < 0) {
			return;
		}
		int rows = ctxRows();
		int mx = ctxX + tlDx0;
		int my = ctxY + tlDy0;
		// convert the raw (screen) mouse position into timeline-window coordinates, so the hover
		// highlight lines up exactly with the row that a click would run
		int hovRow = ctxRowAt((int) (mouseX - tlDx0), (int) (mouseY - tlDy0));
		g.fill(mx, my, mx + CTX_W, my + ctxMenuH(), 0xE6121418);
		g.fill(mx, my, mx + CTX_W, my + 1, GuiTheme.ACCENT);
		for (int i = 0; i < rows; i++) {
			int ry = my + CTX_PAD_TOP + i * CTX_ROW_H;
			if (i == hovRow) {
				g.fill(mx + 1, ry, mx + CTX_W - 1, ry + CTX_ROW_H, GuiTheme.PANEL_HOVER);
			}
			// compact (0.75×) caption so the whole menu stays small
			drawSmallLabel(g, ctxRowLabel(i), mx + 6, ry + (CTX_ROW_H - 6) / 2,
				i == hovRow ? GuiTheme.WARN : GuiTheme.TEXT);
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// widgets (buttons / fields / the timeline length box) always get the click first
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		// the /particle popup is modal: swallow every click that misses it
		if (cmdEditorOpen) {
			return true;
		}
		// window drag handles (screen space): dragging a window's title bar moves that window
		if (button == 0) {
			for (int region = 1; region <= 2; region++) {
				if (hitWindowHandle(mouseX, mouseY, region)) {
					dragWin = region;
					winGrabX = (int) mouseX - (region == 1 ? palDx : propDx);
					winGrabY = (int) mouseY - (region == 1 ? palDy : propDy);
					return true;
				}
			}
			// timeline: its left button column is the move handle — remember the press and decide
			// on release whether it was a click (run the button) or a drag (move the window)
			if (inCtrlColumn(mouseX, mouseY)) {
				ctrlPressed = true;
				ctrlPressIdx = ctrlButtonAt(mouseX, mouseY);
				ctrlPressX = mouseX;
				ctrlPressY = mouseY;
				ctrlDragged = false;
				ctrlGrabX = (int) mouseX - tlDx0;
				ctrlGrabY = (int) mouseY - tlDy0;
				return true;
			}
		}
		mouseX -= ox;
		mouseY -= oy;
		double rawX = mouseX;
		double rawY = mouseY;
		// palette window space
		mouseX = rawX - palDx;
		mouseY = rawY - palDy;
		int x = (int) mouseX;
		int y = (int) mouseY;

		// "+" menu hit test (palette-local coordinates): 自定义粒子 only — particle groups are now
		// created on the timeline (right-click a particle → 创建组)
		if (addMenuOpen) {
			int mx = listW() + 6;
			int my = addMenuY;
			if (x >= mx && x <= mx + 92 && y >= my && y <= my + 20) {
				addParticleClip("particle_3d", L10n.tr("anima.ui.effect.custom_particle"), null);
				addMenuOpen = false;
				return true;
			}
			addMenuOpen = false;
		}

		// left palette: header click toggles the category, effect click starts a drag
		if (button == 0 && x >= 6 && x <= listW() && y >= paletteY0() && y <= paletteY0() + paletteH()) {
			int row = (y - paletteY0() - 4) / 20 + listScroll;
			List<Object> rows = rows();
			if (row >= 0 && row < rows.size()) {
				Object o = rows.get(row);
				if (o instanceof EffectPreset p) {
					if ("particle_add".equals(p.key())) {
						// "+" opens the particle menu instead of starting a drag
						addMenuOpen = true;
						addMenuY = Math.min(paletteY0() + paletteH() - 20, y - 2);
						return true;
					}
					paletteDrag = p.key();
					return true;
				} else if (o instanceof PaletteGroup pg) {
					// a saved group JSON: drop it straight onto the timeline at the playhead
					addSavedGroupClip(pg);
					return true;
				} else if ((y - paletteY0() - 4) % 20 < 18) {
					String cat = (String) o;
					if (collapsedCats.contains(cat)) {
						collapsedCats.remove(cat);
					} else {
						collapsedCats.add(cat);
					}
					return true;
				}
			}
		}

		// properties window space (only clicks that really land on the panel count — otherwise a
		// click anywhere on the screen at the same height would open the particle list)
		mouseX = rawX - propDx;
		mouseY = rawY - propDy;
		x = (int) mouseX;
		y = (int) mouseY;
		boolean inPropPanel = x >= propX() - 2 && x <= propRight() && y >= TOP_H + 2 && y <= propPanelBottom();
		if (particleListOpen) {
			if (inParticlePicker(x, y) && y >= pmRowsY()) { // below the search bar → pick a row
				int idx = particleListScroll + (y - pmRowsY()) / pmRowH();
				List<PickerEntry> entries = pickerEntries();
				if (idx >= 0 && idx < entries.size() && selectedClip != null) {
					applyParticlePick(entries.get(idx)); // sets a particle, appends, or loads a group
				}
				return true;
			}
			closeParticlePicker(); // a click anywhere else closes the list (and its search bar)
		}
		if (button == 0 && inPropPanel && selectedClip != null && !hasGroupChild()
				&& y >= particleY() && y <= particleY() + 18) {
			if (isParticleGroup()) {
				toggleGroupExpanded(selectedClip); // the summary row folds the timeline child rows
			} else if (isParticleSelected()) {
				openParticlePicker();
			}
			return true;
		}

		// timeline window space
		mouseX = rawX - tlDx0;
		mouseY = rawY - tlDy0;
		x = (int) mouseX;
		y = (int) mouseY;
		// right-click dropdown: a click on a row runs it, any other click closes the menu
		if (ctxClip != null && laneOf(ctxClip) >= 0) {
			int row = ctxRowAt(x, y);
			if (row >= 0) {
				ctxAction(row);
				return true;
			}
			ctxClip = null;
			ctxChild = -1;
		}
		if (y >= timelineY() && y <= timelineY() + TIMELINE_H && x >= tlX() && x <= tlX() + tlW()) {
			if (button == 2) { // middle button → pan the timeline view
				panning = true;
				panLastX = x;
				return true;
			}
			int ttyHit = timelineY() + LANE_TOP;
			if (y >= ttyHit && y <= ttyHit + 12) {
				// text-object track: the SHARED keyframes (one key holds every property)
				for (int i = 0; i < textKeys.size(); i++) {
					if (Math.abs(x - tlXFor(textKeys.get(i).timeMs)) <= 5) {
						if (button == 1) {
							textKeys.remove(i);
							dragKeyIdx = -1;
						} else {
							dragKeyIdx = i;
						}
						return true;
					}
				}
				// empty spot on the text track: select the text OBJECT (and move the playhead)
				selectedClip = null;
				ctxClip = null;
				playTimeMs = Math.max(0, Math.min(timelineLen, tlTimeForX(x)));
				init();
				return true;
			}
			// right-edge handle: drag it to change the timeline WINDOW width (not the zoom)
			int hookX = tlX() + tlW();
			if (Math.abs(x - hookX) <= 4) {
				if (frozenPxPerMs <= 0) {
					// remember the current scale so resizing the window never shrinks the clips
					frozenPxPerMs = tlW() / (double) Math.max(1, timelineLen);
				}
				lengthDrag = true;
				return true;
			}
			int lane = laneForY(y);
			// expanded group children have their own rows UNDER the lane row
			GroupChildHit childHit = hitGroupChild(lane, x, y);
			if (childHit != null) {
				if (button == 1) { // right-click a child row → 删除该粒子
					ctxClip = childHit.group();
					ctxChild = childHit.index();
					ctxX = x;
					ctxY = y;
					init();
					return true;
				}
				ctxChild = -1;
				selectedClip = childHit.group();
				selectedChild = childHit.index();
				ctxClip = null;
				if (childHit.edge() >= 0) {
					// grip drag: the child's own start / length (never past the group's end)
					childResizeClip = childHit.group();
					childResizeIdx = childHit.index();
					childResizeRight = childHit.edge() == 1;
					init();
					return true;
				}
				init();
				return true;
			}
			CompositeClip hit = (lane >= 0 && lane < lanes.size() && y < laneTopOf(lane) + LANE_H)
				? hitClip(lanes.get(lane), x) : null;
			if (button == 1) { // right-click: context menu (复制/创建组/删除) for the clip under the cursor
				ctxClip = hit;
				ctxChild = -1;
				ctxX = x;
				ctxY = y;
				selectedClip = hit;
				selectedChild = -1;
				init();
				return true;
			}
			if (hit != null) {
				selectedClip = hit;
				selectedChild = -1;
				ctxClip = null;
				if (button == 0 && inGroupToggle(hit, x, y)) {
					toggleGroupExpanded(hit); // the -/+ on the group block folds the child rows
					return true;
				}
				boolean entry = isEntry(hit);
				boolean exit = isExit(hit);
				int bx0 = btX0(hit);
				int bx1 = bx0 + (int) (Math.max(16, hit.durationMs()) * pxPerMs());
				// entry clips: left edge glued to 0 → no left grip. exit clips: right edge glued
				// to the end → no right grip. Those edges only select the clip.
				if (Math.abs(x - bx0) <= 4 && (exit || !entry)) {
					if (!entry) {
						resizingClip = hit;
						resizeRight = false;
						ctxClip = null;
						return true;
					}
					selectedClip = hit;
					selectedChild = -1;
					ctxClip = null;
					init();
					return true;
				}
				if (Math.abs(x - bx1) <= 4 && (entry || !exit)) {
					if (!exit) {
						resizingClip = hit;
						resizeRight = true;
						ctxClip = null;
						return true;
					}
					selectedClip = hit;
					selectedChild = -1;
					ctxClip = null;
					init();
					return true;
				}
				if (x >= Math.min(bx0, bx1) && x <= Math.max(bx0, bx1)) {
					// entry/exit clips cannot be dragged along the timeline — they stay pinned
					selectedClip = hit;
					selectedChild = -1;
					ctxClip = null;
					if (!isPinned(hit)) {
						movingClip = hit;
						moveGrabMs = tlTimeForX(x) - hit.startMs();
					}
					init();
					return true;
				}
			}
			// empty lane space: left-drag seeks the playhead (timeline follows the mouse)
			ctxClip = null;
			playTimeMs = Math.max(0, Math.min(timelineLen, tlTimeForX(x)));
			seekDrag = true;
			return true;
		}
		// inside the world: left-drag (outside all UI) rotates the view, right-drag pans it.
		// Widgets and the editor panels already got the click above (see the top of this method).
		if (worldPreviewActive && (button == 0 || button == 1) && !insideAnyWindow(rawX, rawY)) {
			if (button == 0) {
				int axis = gizmoVisible() ? hitGizmoAxis(rawX, rawY) : -1;
				if (axis >= 0) {
					// start dragging that axis (Unity-style translate)
					axisDrag = axis;
					axisStartMouseX = rawX;
					axisStartMouseY = rawY;
					axisStartValue = gizmoValue(axis);
					float[] o = gizmoOrigin();
					float[] e = axisTip(axis);
					axisLenAtDrag = gizmoAxisLen();
					if (o != null && e != null) {
						axisScreenDx = e[0] - o[0]; // screen vector of one axis length (axisLenAtDrag blocks)
						axisScreenDy = e[1] - o[1];
					} else {
						axisScreenDx = 0;
						axisScreenDy = 0;
					}
					return true;
				}
				lookDrag = true;
			} else {
				viewPanDrag = true;
			}
			return true;
		}
		return false;
	}

	/** True when the screen point lies on one of the three editor windows. */
	private boolean insideAnyWindow(double sx, double sy) {
		// palette window (drawn with the palDx/palDy offset)
		if (sx >= 4 + palDx && sx <= listW() + palDx
				&& sy >= paletteY0() + palDy && sy <= paletteY0() + paletteH() + palDy) {
			return true;
		}
		// properties window (BOTH edges must carry the same drag offset — omitting propDx on the
		// right edge made the panel look screen-wide once it was dragged to the left, which
		// swallowed every world click on the gizmo axes)
		int px = propX() + propDx;
		int bottom = propPanelBottom() + propDy;
		if (sx >= px - 2 && sx <= propRight() + propDx && sy >= TOP_H + 2 + propDy && sy <= bottom) {
			return true;
		}
		// timeline window (its width is adjustable by dragging the right edge)
		if (sx >= 6 + tlDx0 && sx <= tlX() + tlW() + 6 + tlDx0
				&& sy >= timelineY() + tlDy0 && sy <= timelineY() + TIMELINE_H + tlDy0) {
			return true;
		}
		// expanded particle picker
		return particleListOpen && inParticlePicker((int) sx - propDx, (int) sy - propDy);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (axisDrag >= 0) {
			axisDrag = -1;
			init(); // refresh the property fields with the dragged value
			return true;
		}
		if (dragKeyIdx >= 0) {
			dragKeyIdx = -1;
			return true;
		}
		if (lengthDrag) {
			lengthDrag = false;
			return true;
		}
		if (lookDrag) {
			lookDrag = false;
			return true;
		}
		if (viewPanDrag) {
			viewPanDrag = false;
			return true;
		}
		if (dragWin != 0) {
			dragWin = 0;
			init(); // re-position widgets for the new window offsets
			return true;
		}
		if (ctrlPressed) { // timeline left column: a click runs the button, a drag moved the window
			boolean dragged = ctrlDragged;
			int idx = ctrlPressIdx;
			ctrlPressed = false;
			ctrlDragged = false;
			ctrlPressIdx = -1;
			if (dragged) {
				init();
			} else if (idx >= 0) {
				runControlAction(idx);
			}
			return true;
		}
		mouseX -= ox;
		mouseY -= oy;
		if (paletteDrag != null) {
			mouseX -= tlDx0;
			mouseY -= tlDy0;
			if (mouseX >= tlX() && mouseX <= tlX() + tlW() && mouseY >= timelineY() && mouseY <= timelineY() + TIMELINE_H) {
				EffectPreset preset = palette().stream().filter(p -> p.key().equals(paletteDrag)).findFirst().orElse(null);
				if (preset != null) {
					CompositeClip clip = CompositeClip.of(preset.key(), preset.name())
						.withDuration(preset.defaultDurationMs())
						.withStart(tlTimeForX((int) mouseX));
					if ("particle_3d".equals(clip.effect())) {
						clip = clip.withParticle("minecraft:flame"); // default registry particle
					}
					clip = pinAnchor(clip); // entry/exit glue to the timeline edges
					int lane = laneForY((int) mouseY);
					selectedClip = addToLanes(clip, lane);
					selectedChild = -1;
					clampLaneScroll();
					init();
				}
			}
			paletteDrag = null;
			return true;
		}
		// releasing a manual timeline drag (move / resize / scrub) pauses playback
		boolean wasScrubbing = seekDrag || movingClip != null || resizingClip != null || childResizeClip != null;
		movingClip = null;
		resizingClip = null;
		childResizeClip = null;
		childResizeIdx = -1;
		panning = false;
		seekDrag = false;
		if (wasScrubbing) {
			playing = false;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	/** Drag a clip horizontally and/or into another lane; horizontal position snaps so clips on
	 *  the same lane never overlap (they land on the nearest free spot instead). */
	private void dragMove(CompositeClip cur, int x, int y) {
		int src = laneOf(cur);
		if (src < 0) {
			return;
		}
		float dur = Math.max(16, cur.durationMs());
		float want = Math.max(0f, Math.min(Math.max(0f, timelineLen - dur), tlTimeForX(x) - moveGrabMs));
		int rawLane = laneForY(y);
		int target;
		int maxAuto = laneScrollPx / LANE_H + lanesViewH() / LANE_H + 1; // don't grow rows off-screen
		if (rawLane >= lanes.size() && lanes.size() < maxAuto) {
			// dragged below the last row → grow ONE new lane (it is filled right below)
			lanes.add(new ArrayList<>());
			target = lanes.size() - 1;
		} else {
			target = Math.max(0, Math.min(Math.max(0, lanes.size() - 1), rawLane));
		}
		boolean movedLane = false;
		if (target != src && !overlapsIn(lanes.get(target), null, cur.withStart(want))) {
			lanes.get(src).remove(cur);
			if (lanes.get(src).isEmpty() && target > src) {
				// an emptied row is removed on the spot — rows only exist while used
				lanes.remove(src);
				target--;
			}
			if (target >= lanes.size()) {
				lanes.add(new ArrayList<>()); // safety: keep the destination row
			}
			float ns = snapStart(lanes.get(target), null, want, dur);
			CompositeClip moved = cur.withStart(ns);
			lanes.get(target).add(moved);
			sortLane(target);
			movingClip = moved;
			if (selectedClip == cur) {
				selectedClip = moved;
			}
			movedLane = true;
		}
		if (!movedLane) {
			float ns = snapStart(lanes.get(src), cur, want, dur);
			if (ns != cur.startMs()) {
				CompositeClip moved = cur.withStart(ns);
				replaceClip(cur, moved);
				movingClip = moved;
				if (selectedClip == cur) {
					selectedClip = moved;
				}
			}
		}
	}

	/** End of the previous clip on {@code lane} that ends before {@code endRef} (0 if none). */
	private static float prevEndBefore(List<CompositeClip> lane, CompositeClip self, float endRef) {
		float best = 0f;
		for (CompositeClip o : lane) {
			if (o == self) {
				continue;
			}
			float e = o.startMs() + Math.max(16, o.durationMs());
			if (e <= endRef + 0.5f && e > best) {
				best = e;
			}
		}
		return best;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		// sync the poll position so the first polled delta is zero, then apply the event delta
		pollX = mouseX;
		pollY = mouseY;
		pollValid = true;
		dragEventFrame = true; // pollDrag runs later this frame and must not fight this event
		return applyDrag(mouseX, mouseY, dragX, dragY, button);
	}

	/** True while any drag/scrub gesture is in progress. */
	private boolean anyDragActive() {
		return lookDrag || viewPanDrag || axisDrag >= 0 || dragWin != 0 || movingClip != null
			|| resizingClip != null || childResizeClip != null || paletteDrag != null || seekDrag
			|| panning || dragKeyIdx >= 0 || lengthDrag || ctrlDragged;
	}

	/** Drives the active drag from the CURRENT cursor position (GLFW). Called once per frame so a
	 *  drag keeps working even when the mouse leaves the window or a move/release event is missed. */
	private void pollDrag() {
		Minecraft mc = Minecraft.getInstance();
		long win = mc.getWindow().getWindow();
		if (win == 0L) {
			return;
		}
		double[] mx = new double[1];
		double[] my = new double[1];
		GLFW.glfwGetCursorPos(win, mx, my);
		// GLFW reports the cursor in SCREEN coordinates, so the divisor must be the window's SCREEN
		// size — exactly what vanilla's MouseHandler uses. Dividing by the framebuffer size
		// (getWidth) silently rescales the position whenever Windows/GPU scaling is not 100%,
		// and since this poll runs every frame it would overwrite the (correct) position coming
		// from the mouse event — the drag then looks like it "only moves once".
		double gw = Math.max(1, mc.getWindow().getScreenWidth());
		double gh = Math.max(1, mc.getWindow().getScreenHeight());
		double guiX = mx[0] * this.width / gw;
		double guiY = my[0] * this.height / gh;
		double dx = pollValid ? guiX - pollX : 0;
		double dy = pollValid ? guiY - pollY : 0;
		pollX = guiX;
		pollY = guiY;
		pollValid = true;
		if (dragEventFrame) {
			return; // a mouse event already drove this frame's drag with the authoritative position
		}
		applyDrag(guiX, guiY, dx, dy, 0);
	}

	private boolean applyDrag(double mouseX, double mouseY, double dragX, double dragY, int button) {
		if (axisDrag >= 0) { // drag the selected object along the grabbed world axis
			double len2 = axisScreenDx * axisScreenDx + axisScreenDy * axisScreenDy;
			if (len2 > 1.0) {
				double dx = mouseX - axisStartMouseX;
				double dy = mouseY - axisStartMouseY;
				double units = (dx * axisScreenDx + dy * axisScreenDy) / len2 * axisLenAtDrag;
				double nv = Math.max(gizmoMin(axisDrag), Math.min(gizmoMax(axisDrag), axisStartValue + units));
				setGizmoValue(axisDrag, (float) Math.round(nv * 1000.0) / 1000f);
			}
			return true;
		}
		if (lookDrag) { // accumulate MC's real deltas; comparing against rounded coords drifted every frame
			if (Math.abs(dragX) > 0.01 || Math.abs(dragY) > 0.01) {
				lookAccumX += dragX;
				lookAccumY += dragY;
			}
			return true;
		}
		if (viewPanDrag) { // right-drag: pan the camera along its own plane
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.player.LocalPlayer player = mc.player;
			if (player != null) {
				net.minecraft.world.phys.Vec3 look = player.getViewVector(1f);
				net.minecraft.world.phys.Vec3 up = new net.minecraft.world.phys.Vec3(0, 1, 0);
				net.minecraft.world.phys.Vec3 right = look.cross(up).normalize();
				net.minecraft.world.phys.Vec3 camUp = right.cross(look).normalize();
				double k = mc.getWindow().getGuiScale() * 0.03;
				net.minecraft.world.phys.Vec3 d = right.scale(-dragX * k).add(camUp.scale(dragY * k));
				player.setPos(player.getX() + d.x, player.getY() + d.y, player.getZ() + d.z);
			}
			return true;
		}
		if (dragWin != 0) { // move the dragged window
			int nx = (int) mouseX - winGrabX;
			int ny = (int) mouseY - winGrabY;
			if (dragWin == 1) {
				palDx = nx;
				palDy = ny;
			} else if (dragWin == 2) {
				propDx = nx;
				propDy = ny;
			} else {
				tlDx0 = nx;
				tlDy0 = ny;
			}
			init(); // keep the window's buttons/fields glued to it while dragging
			return true;
		}
		if (ctrlPressed) { // timeline: pressed on its left button column
			if (!ctrlDragged && (Math.abs(mouseX - ctrlPressX) > 3 || Math.abs(mouseY - ctrlPressY) > 3)) {
				ctrlDragged = true; // moved → it is a window drag, not a button click
			}
			if (ctrlDragged) {
				tlDx0 = (int) mouseX - ctrlGrabX;
				tlDy0 = (int) mouseY - ctrlGrabY;
				init();
			}
			return true;
		}
		mouseX -= ox;
		mouseY -= oy;
		mouseX -= tlDx0; // the remaining drags all act on the timeline window
		mouseY -= tlDy0;
		int x = (int) mouseX;
		if (dragKeyIdx >= 0 && dragKeyIdx < textKeys.size()) { // drag a shared text keyframe
			TextKey k = textKeys.get(dragKeyIdx);
			k.timeMs = Math.max(0, Math.min(timelineLen, Math.round(tlTimeForX(x))));
			sortTextKeys(textKeys);
			dragKeyIdx = textKeys.indexOf(k);
			return true;
		}
		if (lengthDrag) { // drag the right edge to resize the timeline window itself
			tlWidth = Math.max(120, Math.min(Math.max(120, pw - tlX() - 6), Math.round((float) x - tlX())));
			return true;
		}
		if (seekDrag) { // scrub the playhead and keep it in view (timeline follows)
			playTimeMs = Math.max(0, Math.min(timelineLen, tlTimeForX(x)));
			double vis = tlW() / pxPerMs();
			if (playTimeMs < viewOffsetMs) {
				viewOffsetMs = (float) Math.max(0, playTimeMs);
			} else if (playTimeMs > viewOffsetMs + vis) {
				viewOffsetMs = (float) Math.max(0, playTimeMs - vis);
			}
			return true;
		}
		if (childResizeClip != null && childResizeIdx >= 0) {
			// dragging a child bar's grip: moves its start / changes its length, but the window
			// always stays INSIDE the group's own time range
			CompositeClip c = childResizeClip;
			int idx = childResizeIdx;
			float gx = c.startMs();
			float gDur = Math.max(16f, c.durationMs());
			float s = (float) groupItemStart(c, idx);
			float d = (float) groupItemDur(c, idx);
			float ms = tlTimeForX(x);
			if (childResizeRight) {
				float nd = Math.max(16f, Math.min(gDur - s, ms - gx - s));
				setGroupItemParam(idx, "d", Math.round(nd));
			} else {
				float ns = Math.max(0f, Math.min(gx + gDur - d, ms) - gx);
				setGroupItemParam(idx, "s", Math.round(ns));
			}
			return true;
		}
		if (movingClip != null) {
			if (laneOf(movingClip) < 0) {
				movingClip = null; // its instance was replaced elsewhere — stop instead of freezing
			} else {
				// all clips (fades included) drag freely: horizontal move + lane switch
				dragMove(movingClip, x, (int) mouseY);
			}
			return true;
		}
		if (resizingClip != null) {
			CompositeClip c = resizingClip;
			int src = laneOf(c);
			if (src >= 0) {
				List<CompositeClip> lane = lanes.get(src);
				float ms = tlTimeForX(x);
				float dur = Math.max(16, c.durationMs());
				CompositeClip candidate = null;
				if (resizeRight) {
					float next = nextStartAfter(lane, c.startMs() + 0.5f);
					float maxEnd = next == Float.MAX_VALUE ? timelineLen : next;
					if (maxEnd - c.startMs() >= 16) {
						candidate = c.withDuration(Math.max(16, Math.min(maxEnd - c.startMs(), ms - c.startMs())));
					}
				} else { // keep the right edge fixed
					float end = c.startMs() + dur;
					float minStart = prevEndBefore(lane, c, end - 0.5f);
					float ns = Math.max(minStart, Math.min(end - 16, ms));
					candidate = c.withStart(ns).withDuration(end - ns);
				}
				if (candidate != null) {
					setClip(c, candidate);
					resizingClip = candidate; // setClip replaced the old instance — keep dragging the new one
				}
			}
			return true;
		}
		if (panning) { // middle-button: move the timeline VIEW
			double dms = (x - panLastX) / pxPerMs();
			viewOffsetMs = (float) Math.max(0, viewOffsetMs - dms);
			panLastX = x;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		mouseX -= ox;
		mouseY -= oy;
		double rawX = mouseX;
		double rawY = mouseY;
		// particle picker: wheel scrolls the filtered list
		if (particleListOpen) {
			int px = (int) (rawX - propDx);
			int py = (int) (rawY - propDy);
			if (inParticlePicker(px, py)) {
				int max = Math.max(0, pickerEntries().size() - pmVisibleRows());
				particleListScroll = Math.max(0, Math.min(max,
					particleListScroll - (int) Math.signum(verticalAmount)));
				return true;
			}
		}
		// properties window: wheel scrolls the panel content when it overflows
		if (propMaxScroll() > 0) {
			int px0 = (int) (rawX - propDx);
			int py0 = (int) (rawY - propDy);
			if (px0 >= propX() - 2 && px0 <= propRight() && py0 >= TOP_H + 2 && py0 <= propPanelBottom()) {
				propScroll = Math.max(0, Math.min(propMaxScroll(),
					propScroll - (int) Math.signum(verticalAmount) * 12));
				init();
				return true;
			}
		}
		// timeline window space
		mouseX = rawX - tlDx0;
		mouseY = rawY - tlDy0;
		// Ctrl + wheel over the timeline → zoom
		if (hasControlDown() && mouseX >= tlX() && mouseX <= tlX() + tlW() && mouseY >= timelineY() && mouseY <= timelineY() + TIMELINE_H) {
			double factor = Math.signum(verticalAmount) > 0 ? 1.15 : 1 / 1.15;
			// zoom around cursor: keep the world time under the cursor fixed (clamped so the
			// left edge self-aligns to 0 instead of overflowing/springing back).
			// 0.15 lets you zoom out far enough to see a long timeline at once.
			float worldAtCursor = tlTimeForX((int) mouseX);
			zoom = Math.max(0.15, Math.min(6.0, zoom * factor));
			viewOffsetMs = Math.max(0f, (float) (worldAtCursor - (mouseX - tlX()) / pxPerMs()));
			return true;
		}
		// plain wheel over the timeline → scroll the lane rows vertically (see lower clips)
		if (mouseX >= tlX() && mouseX <= tlX() + tlW() && mouseY >= timelineY() && mouseY <= timelineY() + TIMELINE_H) {
			laneScrollPx = laneScrollPx - (int) Math.signum(verticalAmount) * LANE_H;
			clampLaneScroll();
			return true;
		}
		// palette window space: plain wheel scrolls the list
		mouseX = rawX - palDx;
		mouseY = rawY - palDy;
		if (mouseX >= 6 && mouseX <= listW()) {
			int max = Math.max(0, rows().size() - (paletteH() - 4) / 20);
			listScroll = Math.max(0, Math.min(max, listScroll - (int) Math.signum(verticalAmount)));
			return true;
		}
		return super.mouseScrolled(rawX, rawY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (cmdEditorOpen) {
			// modal popup: Enter confirms, ESC cancels (it must NOT close the whole editor)
			if (keyCode == 257 || keyCode == 335) {
				confirmCommandEditor();
				return true;
			}
			if (keyCode == 256) {
				closeCommandEditor();
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (worldPreviewActive && handleMovementKey(keyCode, true)) {
			return true; // WASD / jump / sneak drive the spectator while editing inside the world
		}
		if (keyCode == 32 && !worldPreviewActive && !isTextFieldFocused()) { // Space → play/pause (never while typing)
			playing = !playing;
			return true;
		}
		if (keyCode == 261 && selectedClip != null && !isTextFieldFocused()) { // Delete → remove the selected clip
			removeClip(selectedClip);
			init();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/** True while one of our text inputs owns the keyboard — shortcuts must not steal its keys.
	 *  (259 is Backspace, not Delete: treating it as "remove clip" deleted the clip mid-rename.) */
	private boolean isTextFieldFocused() {
		return getFocused() instanceof net.minecraft.client.gui.components.EditBox;
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (worldPreviewActive && handleMovementKey(keyCode, false)) {
			return true;
		}
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	/** Forwards the vanilla movement keys (WASD + jump/sneak) so the spectator can move.
	 *  Jump/sneak are what lift/lower the spectator, so they must stay forwarded — the old
	 *  drift was actually caused by rounded mouse deltas, not by these keys. */
	private boolean handleMovementKey(int keyCode, boolean down) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.KeyMapping[] keys = {
			mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
			mc.options.keyJump, mc.options.keyShift
		};
		for (net.minecraft.client.KeyMapping km : keys) {
			if (km.matches(keyCode, 0)) {
				km.setDown(down);
				if (down) {
					heldMoveKeys.put(km, keyCode);
				} else {
					heldMoveKeys.remove(km);
				}
				return true;
			}
		}
		return false;
	}

	/** Releases every movement key we forwarded (used when the screen closes). */
	private void releaseMovementKeys() {
		for (net.minecraft.client.KeyMapping km : heldMoveKeys.keySet()) {
			km.setDown(false);
		}
		heldMoveKeys.clear();
	}

	/** Applies the accumulated right-drag look delta once per frame, matching vanilla mouse feel. */
	private void applyLookDelta() {		if (lookAccumX == 0 && lookAccumY == 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.player.LocalPlayer player = mc.player;
		if (player == null) {
			lookAccumX = 0;
			lookAccumY = 0;
			return;
		}
		// Screen mouse coords are GUI-scaled, so multiply back to real pixels (vanilla uses pixels)
		double scale = mc.getWindow().getGuiScale();
		double s = mc.options.sensitivity().get() * 0.6 + 0.2;
		double factor = s * s * s * 8.0 * 0.15 * scale;
		float yaw = (float) (player.getYRot() + lookAccumX * factor); // drag right → look right
		// pitch: drag up → look up. Screen Y grows downward, so an upward drag gives a NEGATIVE
		// delta; adding it to the pitch (which grows downward) keeps the motion in the same
		// direction as the mouse.
		float pitch = (float) Math.max(-90.0, Math.min(90.0, player.getXRot() + lookAccumY * factor));
		player.setYRot(yaw);
		player.setXRot(pitch);
		// keep the previous-frame angles in sync so render interpolation cannot drift
		player.yRotO = yaw;
		player.xRotO = pitch;
		lookAccumX = 0;
		lookAccumY = 0;
	}

	/**
	 * Clears drag/key state whose physical button or key is no longer held. GUI release events
	 * can be missed (window focus changes, mouse leaving the window), which otherwise leaves
	 * stale state — e.g. a stuck movement key slowly lifting the spectator, or a stale resize
	 * drag that swallows every later timeline drag.
	 */
	private void sanitizeInputState() {
		Minecraft mc = Minecraft.getInstance();
		long win = mc.getWindow().getWindow();
		if (win == 0L) {
			return;
		}
		boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean right = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
		boolean middle = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
		if (lookDrag && !left) {
			lookDrag = false;
		}
		if (viewPanDrag && !right) {
			viewPanDrag = false;
		}
		if (dragWin != 0 && !left) {
			dragWin = 0;
			init(); // re-position widgets for the offset already applied
		}
		if (ctrlPressed && !left) {
			ctrlPressed = false;
			ctrlDragged = false;
			ctrlPressIdx = -1;
		}
		if (movingClip != null && !left) {
			movingClip = null;
		}
		if (resizingClip != null && !left) {
			resizingClip = null;
		}
		if (childResizeClip != null && !left) {
			childResizeClip = null;
			childResizeIdx = -1;
		}
		if (paletteDrag != null && !left) {
			paletteDrag = null;
		}
		if (seekDrag && !left) {
			seekDrag = false;
		}
		if (panning && !middle) {
			panning = false;
		}
		if (dragKeyIdx >= 0 && !left) {
			dragKeyIdx = -1;
		}
		if (axisDrag >= 0 && !left) {
			axisDrag = -1;
			init();
		}
		if (lengthDrag && !left) {
			lengthDrag = false;
		}
		// release any movement key that is no longer physically held
		for (java.util.Iterator<java.util.Map.Entry<net.minecraft.client.KeyMapping, Integer>> it = heldMoveKeys.entrySet().iterator(); it.hasNext();) {
			java.util.Map.Entry<net.minecraft.client.KeyMapping, Integer> e = it.next();
			if (GLFW.glfwGetKey(win, e.getValue()) != GLFW.GLFW_PRESS) {
				e.getKey().setDown(false);
				it.remove();
			}
		}
		// never let a movement key stay down unless WE pressed it (fixes the slow "rising" drift)
		net.minecraft.client.KeyMapping[] guard = { mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
			mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint };
		for (net.minecraft.client.KeyMapping km : guard) {
			if (km.isDown() && !heldMoveKeys.containsKey(km)) {
				km.setDown(false);
			}
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// blank: no vanilla blur layer
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = System.currentTimeMillis();
		if (lastFrameMs < 0) {
			lastFrameMs = now;
		}
		if (playing) {
			playTimeMs = Math.min(timelineLen, playTimeMs + (now - lastFrameMs));
		}
		lastFrameMs = now;
		if (overlay) {
			activeOverlay = this; // the world pass draws our preview object
		}
		applyLookDelta(); // apply accumulated right-drag look once per frame (smooth, no jitter)
		sanitizeInputState(); // clear drag/key state whose physical button/key is no longer held
		if (!anyDragActive()) {
			refreshTimelineLen(); // content-driven length, only when idle so the scale stays stable
		}
		if (anyDragActive()) {
			pollDrag(); // keep dragging even if the mouse left the window / events were dropped
		} else {
			pollValid = false;
		}
		dragEventFrame = false; // consumed by pollDrag above
		refreshPropFields();  // object fields follow the gizmo drag / playback in real time

		int lmx = mouseX - ox;
		int lmy = mouseY - oy;
		if (!overlay) {
			g.fill(0, 0, pw, ph, GuiTheme.BG);
		}
		// world-space preview: inside the config world we project the object ourselves, no matter
		// how the editor was opened (floating window or full screen). Drawn again on TOP at the
		// end of render() so the floating windows can never hide it.
		worldPreviewActive = ConfigWorldLauncher.isConfigWorld();
		renderCenter(g);

		// three independent floating windows, each translated by its own drag offset
		g.pose().pushPose();
		g.pose().translate(palDx, palDy, 0);
		renderPalette(g, lmx - palDx, lmy - palDy);
		drawWindowHandle(g, 1, 4, paletteY0() - HANDLE_H, listW() - 4, L10n.tr("anima.ui.title.palette"), 4);
		g.pose().popPose();

		g.pose().pushPose();
		g.pose().translate(propDx, propDy, 0);
		renderProperty(g);
		// the title bar spans the panel's full width (incl. its 2px side gutters), so the top
		// edge no longer looks shorter than the panel behind it; its text keeps the same inset
		// as the palette window's caption
		drawWindowHandle(g, 2, propX() - 2, TOP_H + 2, PROP_PANEL_W, propLabel(), PROP_INSET);
		g.pose().popPose();

		g.pose().pushPose();
		g.pose().translate(tlDx0, tlDy0, 0);
		renderTimeline(g, lmx, lmy);
		g.pose().popPose();

		// the /particle popup background goes UNDER the widgets (its field/buttons are widgets,
		// so they must be drawn after this), and its frame is redrawn on top below
		renderCommandEditorBackground(g);

		// widgets are positioned in init() with the same per-window offsets, so they line up
		super.render(g, lmx, lmy, partialTick);

		// right-click dropdown drawn on top of the windows (like a native context menu)
		renderContextMenu(g, mouseX, mouseY);
		// popup frame/title on top of everything
		renderCommandEditor(g);

		// transient status line (保存/导入粒子组)
		if (!notice.isEmpty() && now < noticeUntil) {
			g.drawString(this.font, notice, 6, 6, GuiTheme.DE_GREEN);
		}

		// the projected preview object goes on the very top so no window can cover it
		if (worldPreviewActive) {
			drawGizmo(g); // origin marker + draggable axes for the selected object
		}
	}

	/** True while the floating editor is open (used to avoid double-drawing the gizmo). */
	public static boolean isOverlayActive() {
		return activeOverlay != null;
	}

	/** Draws the little drag bar of one floating window (region: 1 palette, 2 properties, 3 timeline). */
	/** Window title bar: one size bigger text than the small field captions, hugging the text. */
	private void drawWindowHandle(GuiGraphics g, int region, int x, int y, int w, String label, int labelInset) {
		g.fill(x, y, x + w, y + HANDLE_H, handleBg());
		g.fill(x, y, x + w, y + 1, GuiTheme.ACCENT);
		if (!label.isEmpty()) {
			g.drawString(this.font, label, x + labelInset, y + 2, GuiTheme.SUBTEXT, false);
		}
	}

	/** Drag grip of the timeline window — the left button column itself is the handle, so no
	 *  extra grip is drawn (the buttons are just buttons when clicked without moving). */

	// ---- left control column (播放 / 重头 / 清空) — drawn manually so a DRAG moves the window ----

	private static final int CTRL_BTN_H = 16;
	private static final int CTRL_ROW_H = 18;

	private int ctrlW() { return Math.min(CTRL_W - 8, 36); }
	private int ctrlBtnY(int idx) { return timelineY() + 4 + idx * CTRL_ROW_H; }

	private static String ctrlLabel(int idx, boolean playing) {
		return switch (idx) {
			case 0 -> playing ? L10n.tr("anima.ui.button.pause") : L10n.tr("anima.ui.button.play");
			case 1 -> L10n.tr("anima.ui.button.restart");
			default -> L10n.tr("anima.ui.button.clear");
		};
	}

	/** Number of left control buttons: play / restart / clear. Saving is the caller's job. */
	private int ctrlCount() {
		return 3;
	}

	/** Index of the control button under a SCREEN point, or -1. */
	private int ctrlButtonAt(double screenX, double screenY) {
		double lx = screenX - tlDx0;
		double ly = screenY - tlDy0;
		if (lx < 6 || lx > 6 + ctrlW()) {
			return -1;
		}
		for (int i = 0; i < ctrlCount(); i++) {
			int by = ctrlBtnY(i);
			if (ly >= by && ly <= by + CTRL_BTN_H) {
				return i;
			}
		}
		return -1;
	}

	/** True when a SCREEN point is anywhere on the timeline's left column (its move handle). */
	private boolean inCtrlColumn(double screenX, double screenY) {
		double lx = screenX - tlDx0;
		double ly = screenY - tlDy0;
		return lx >= 4 && lx <= tlX() - 2 && ly >= timelineY() && ly <= timelineY() + TIMELINE_H;
	}

	private void drawControlButtons(GuiGraphics g, int mouseX, int mouseY) {
		int w = ctrlW();
		int lmx = mouseX - tlDx0;
		int lmy = mouseY - tlDy0;
		for (int i = 0; i < ctrlCount(); i++) {
			int by = ctrlBtnY(i);
			boolean over = lmx >= 6 && lmx <= 6 + w && lmy >= by && lmy <= by + CTRL_BTN_H;
			int border = over ? GuiTheme.ACCENT : GuiTheme.BORDER;
			int bg = over ? GuiTheme.PANEL_HOVER : GuiTheme.PANEL;
			g.fill(6, by, 6 + w, by + CTRL_BTN_H, border);
			g.fill(7, by + 1, 5 + w, by + CTRL_BTN_H - 1, bg);
			String label = ctrlLabel(i, playing);
			g.drawString(this.font, label, 6 + (w - this.font.width(label)) / 2, by + 4,
				over ? GuiTheme.TEXT : GuiTheme.SUBTEXT);
		}
	}

	/** Runs one of the left control buttons. */
	private void runControlAction(int idx) {
		switch (idx) {
			case 0 -> playing = !playing;
			case 1 -> playTimeMs = 0;
			default -> {
				lanes.clear();
				selectedClip = null;
				selectedChild = -1;
				ctxClip = null;
				laneScrollPx = 0;
			}
		}
		init();
	}

	/** Title of the properties window (shows what the panel is currently editing). */
	private String propLabel() {
		if (selectedClip == null) {
			return L10n.tr("anima.ui.title.text_object");
		}
		if (hasGroupChild()) {
			return L10n.tr("anima.ui.prop.child_particle_n") + (selectedChild + 1) + " · " + displayNameShort(groupItemId(selectedClip, selectedChild));
		}
		return L10n.tr("anima.ui.title.property") + selectedClip.displayName();
	}

	private String displayNameShort(String s) {
		return s == null ? "" : this.font.plainSubstrByWidth(s, 44);
	}

	// ------------------------------------------------------------------ preview-object properties + keyframes

	/** First row of the text-object property fields (位置/缩放/透明度) — merged into the clip
	 *  list right after 动画时长, so they no longer sit detached below the 移除 button. */
	private int propRowBaseY() { return selectedClip == null ? nameY() : durY() + ROW_H; }
	private int propRowY(int i) { return propRowBaseY() + i * ROW_H; }

	/** How many of these rows the panel shows for the current selection. The TEXT OBJECT owns all
	 *  of them (位置X/Y/Z + 缩放 + 透明度 + 文本时长 + 距离缩放); a selected particle / group keeps
	 *  only its own 偏移X/Y/Z; a group CHILD and a plain animation have none. */
	private int propShownRows() {
		if (selectedClip == null) {
			return LAST_PROP_ROW + 1;
		}
		return isParticleSelected() && !hasGroupChild() ? 3 : 0;
	}

	// ---- panel height (content-driven, capped) + scrolling -------------------------------------------------

	/** Left inset of the properties panel's text/inputs (matches the palette's own padding). */
	private static final int PROP_INSET = 4;
	/** Total width of the properties window (incl. both 2px side gutters). */
	private static final int PROP_PANEL_W = PROP_W;
	/** Right edge of the properties panel — symmetric with its left edge (PROP_INSET + 2px gutter). */
	private int propRight() { return propX() + PROP_PANEL_W - 2; }
	/** Y where the SCROLLING content starts: below the window's title bar (it must never overlap). */
	private int propContentTop() { return TOP_H + 2 + HANDLE_H; }

	/** Height available to the properties panel before it would reach the timeline (kept short so
	 *  it never crowds the timeline — the panel scrolls when its content is taller). */
	private int propMaxHeight() { return Math.max(60, timelineY() - 22 - (TOP_H + 2)); }

	/** Content height at scroll 0, measured from the content top (below the title bar). */
	private int propContentHeight() {
		int save = propScroll;
		propScroll = 0;
		int bottom = propRowBaseY() + propShownRows() * ROW_H + 20;
		if (selectedClip != null) {
			bottom = Math.max(bottom, removeY() + ROW_H);
		}
		propScroll = save;
		return bottom - propContentTop();
	}

	/** Visible content area (the panel minus its title bar). */
	private int propViewH() { return Math.max(20, propMaxHeight() - HANDLE_H); }

	private int propMaxScroll() { return Math.max(0, propContentHeight() - propViewH()); }

	/** Bottom edge of the panel: content height, capped so it never covers the timeline. */
	private int propPanelBottom() {
		return TOP_H + 2 + Math.min(propContentHeight() + HANDLE_H, propMaxHeight());
	}

	private void clampPropScroll() { propScroll = Math.max(0, Math.min(propMaxScroll(), propScroll)); }

	/** Range of a text-object property (0..2 = position xyz, 3 = scale, 4 = opacity). */
	private double propMin(int p) { return p == 3 ? 0.01 : (p == 4 ? 0 : -64); }
	private double propMax(int p) { return p == 3 ? 5 : (p == 4 ? 1 : 64); }
	private double propStep(int p) { return 0.01; }

	/** Value shown in a property field at the current playhead time. */
	private double propDisplay(int p) {
		return propValue(p, playTimeMs);
	}

	// ---- the first three rows mean different things depending on the selection --------------
	// Text object selected: the keyframed 位置X/Y/Z.  A particle / group selected: that clip's
	// OWN offset (ox/oy/oz), so editing or dragging these fields never moves the text.

	/** True when property row {@code i} (0..2) edits the selected particle's own offset. */
	private boolean particleOffsetRow(int i) {
		return i <= 2 && isParticleSelected();
	}

	/** JSON key of the particle offset edited by row {@code i}. */
	private static String offsetKey(int i) {
		return i == 0 ? "ox" : (i == 1 ? "oy" : "oz");
	}

	/** Caption of property row {@code i}. */
	private String propRowLabel(int i) {
		if (particleOffsetRow(i)) {
			return switch (i) {
				case 0 -> L10n.tr("anima.ui.prop.offset_x");
				case 1 -> L10n.tr("anima.ui.prop.offset_y");
				default -> L10n.tr("anima.ui.prop.offset_z");
			};
		}
		return propName(i);
	}

	/** Value shown in property row {@code i} for the current selection. */
	private double propRowDisplay(int i) {
		return particleOffsetRow(i) ? param(selectedClip, offsetKey(i), 0) : propDisplay(i);
	}

	private double propRowMin(int i) {
		return particleOffsetRow(i) ? -32 : propMin(i);
	}

	private double propRowMax(int i) {
		return particleOffsetRow(i) ? 32 : propMax(i);
	}

	/** Writes property row {@code i} to whatever it currently describes. */
	private void setPropRowValue(int i, double v) {
		if (particleOffsetRow(i)) {
			setParam(offsetKey(i), v); // the particle moves, the text stays where it is
		} else {
			setPropFromInput(i, v);
		}
	}

	/** Value of a property at a time (shared keyframes: every field interpolates on the same keys). */
	private float propValue(int p, float timeMs) {
		if (textKeys.isEmpty()) {
			return propDefaults[p];
		}
		TextKey prev = textKeys.get(0);
		if (timeMs <= prev.timeMs) {
			return prev.v[p];
		}
		for (int i = 1; i < textKeys.size(); i++) {
			TextKey cur = textKeys.get(i);
			if (timeMs <= cur.timeMs) {
				float span = Math.max(1f, cur.timeMs - prev.timeMs);
				float t = (timeMs - prev.timeMs) / span;
				anima.api.Interpolator ease =
					anima.api.Easing.ALL.getOrDefault(prev.ease,
						anima.api.Easing.LINEAR);
				float f = ease.applyClamped(t);
				return prev.v[p] + (cur.v[p] - prev.v[p]) * f;
			}
			prev = cur;
		}
		return prev.v[p];
	}

	/** Snapshot of every property at the playhead — used when a new shared key is created. */
	private float[] currentPropSnapshot() {
		float[] snap = new float[PROP_COUNT];
		for (int p = 0; p < PROP_COUNT; p++) {
			snap[p] = propValue(p, playTimeMs);
		}
		return snap;
	}

	/** The key at the playhead (±1ms), or -1. */
	private int keyAtPlayhead() {
		for (int i = 0; i < textKeys.size(); i++) {
			if (Math.abs(textKeys.get(i).timeMs - playTimeMs) < 1f) {
				return i;
			}
		}
		return -1;
	}

	private static void sortTextKeys(List<TextKey> keys) {
		keys.sort(Comparator.comparingInt(k -> k.timeMs));
	}

	/** Adds a key at the playhead holding the CURRENT value of every property (shared keyframe). */
	private void addKeyAtPlayhead() {
		int t = Math.round(Math.max(0f, playTimeMs));
		int idx = keyAtPlayhead();
		if (idx >= 0) {
			return; // a key already exists here — nothing to add
		}
		TextKey k = new TextKey();
		k.timeMs = t;
		System.arraycopy(currentPropSnapshot(), 0, k.v, 0, PROP_COUNT);
		textKeys.add(k);
		sortTextKeys(textKeys);
	}

	/** Field edit: no keys → edit the default; keys exist → update/create the shared key here. */
	private void setPropFromInput(int p, double value) {
		if (updatingFields) {
			return; // programmatic refresh, not a user edit
		}
		activeProp = p;
		float v = (float) value;
		if (textKeys.isEmpty()) {
			propDefaults[p] = v;
			return;
		}
		int idx = keyAtPlayhead();
		if (idx >= 0) {
			textKeys.get(idx).v[p] = v;
		} else {
			TextKey k = new TextKey();
			k.timeMs = Math.round(Math.max(0f, playTimeMs));
			System.arraycopy(currentPropSnapshot(), 0, k.v, 0, PROP_COUNT);
			k.v[p] = v;
			textKeys.add(k);
			sortTextKeys(textKeys);
		}
	}

	/** Writes a property value at the playhead (gizmo dragging) — same rules as field editing. */
	private void setPropAtPlayhead(int p, float v) {
		setPropFromInput(p, v);
	}

	/** Builds the property rows the current selection actually owns (see {@link #propShownRows()}):
	 *  the text object's 位置X/Y/Z + 缩放 + 透明度 (+ 文本时长 / 距离缩放), or — with a PARTICLE
	 *  selected — only that particle's own 偏移X/Y/Z. Offset rows are not keyframed, so they get
	 *  no ◆ button. */
	private void buildPropFields(int wx, int wy) {
		int rows = propShownRows();
		propFields.clear();
		for (int i = 0; i < PROP_COUNT && i < rows; i++) {
			final int p = i;
			boolean offsetRow = particleOffsetRow(p);
			// rows without a ◆ button use the same full width as every other control in the
			// panel, so a particle's 偏移X/Y/Z line up with 数量 / 散布 / 速度 above them
			int fieldW = offsetRow ? PROP_W - PROP_INSET - 8 : PROP_W - 8 - 20;
			DecimalField field = new DecimalField(this.font, wx, wy + propRowY(i), fieldW, FIELD_H,
				propRowMin(i), propRowMax(i), propStep(i), propRowLabel(i), propRowDisplay(i),
				v -> setPropRowValue(p, v));
			propFields.add(field);
			addRenderableWidget(field);
			if (!offsetRow) {
				addRenderableWidget(ThemeButton.of(wx + fieldW + 2, wy + propRowY(i), 16, FIELD_H,
					Component.literal("◆"), b -> addKeyAtPlayhead()));
			}
		}
		textDurField = null;
		if (selectedClip != null) {
			return; // 文本时长 / 距离缩放 describe the TEXT OBJECT, not a clip
		}
		// 文本时长 == the timeline length (the timeline's own length input was removed)
		textDurField = new NumberField(this.font, wx, wy + propRowY(TEXT_DUR_ROW), PROP_W - PROP_INSET - 8,
			FIELD_H, MIN_TIMELINE_LEN, MAX_TIMELINE_LEN, 10, L10n.tr("anima.ui.prop.text_duration"), timelineLen, v -> {
				// only a REAL edit pins the length (the field is also refreshed programmatically)
				if (v != timelineLen) {
					timelineLenOverride = v;
					refreshTimelineLen();
				}
			});
		addRenderableWidget(textDurField);
		// distance scaling: ON = real perspective (近大远小); OFF = constant apparent size
		addRenderableWidget(ThemeButton.of(wx, wy + propRowY(DIST_ROW), PROP_W - PROP_INSET - 8, FIELD_H,
			Component.literal(textDistanceScale ? L10n.tr("anima.ui.prop.distance_scale_on") : L10n.tr("anima.ui.prop.distance_scale_off")), b -> {
				textDistanceScale = !textDistanceScale;
				init();
			}));
		// 显示坐标系（世界里的三轴 gizmo）：默认关闭，需要拖轴调整位置时再打开
		addRenderableWidget(ThemeButton.of(wx, wy + propRowY(GIZMO_ROW), PROP_W - PROP_INSET - 8, FIELD_H,
			Component.literal(showGizmo ? L10n.tr("anima.ui.prop.gizmo_on") : L10n.tr("anima.ui.prop.gizmo_off")), b -> {
				showGizmo = !showGizmo;
				init();
			}));
	}

	/** Keeps the property fields in sync with the value (gizmo drag / playback) in real time. */
	private void refreshPropFields() {
		if (textDurField != null && !textDurField.isFocused()) {
			String want = String.valueOf(timelineLen);
			if (!want.equals(textDurField.getValue())) {
				updatingFields = true;
				textDurField.setValue(want);
				updatingFields = false;
			}
		}
		for (int i = 0; i < propFields.size(); i++) {
			DecimalField f = propFields.get(i);
			if (f.isFocused()) {
				continue; // never fight the user while they type
			}
			String want = DecimalField.format(propRowDisplay(i));
			if (!want.equals(f.getValue())) {
				updatingFields = true;
				f.setValue(want);
				updatingFields = false;
			}
		}
	}

	/** Small (0.75×) label used for every field caption, so captions hug their inputs. */
	private void drawSmallLabel(GuiGraphics g, String text, int x, int y, int color) {
		g.pose().pushPose();
		g.pose().translate(x, y, 0f);
		g.pose().scale(0.75f, 0.75f, 1f);
		g.drawString(this.font, text, 0, 0, color, false);
		g.pose().popPose();
	}

	/** Labels of the property rows the current selection owns (small, above the input, flush left).
	 *  With a particle selected the shown rows are that particle's own 偏移X/Y/Z. */
	private void renderPropLabels(GuiGraphics g, int px) {
		int rows = propShownRows();
		for (int i = 0; i < PROP_COUNT && i < rows; i++) {
			drawSmallLabel(g, propRowLabel(i), px + PROP_INSET, propRowY(i) - 6,
				i == activeProp && !particleOffsetRow(i) ? GuiTheme.ACCENT : GuiTheme.SUBTEXT);
		}
		if (selectedClip == null) {
			drawSmallLabel(g, L10n.tr("anima.ui.prop.text_duration"), px + PROP_INSET, propRowY(TEXT_DUR_ROW) - 6, GuiTheme.SUBTEXT);
			drawSmallLabel(g, L10n.tr("anima.ui.prop.distance_scale"), px + PROP_INSET, propRowY(DIST_ROW) - 6, GuiTheme.SUBTEXT);
		}
	}

	// ------------------------------------------------------------------ particle picker (registry list + saved groups)

	private static final int PICKER_SEARCH_H = 13;

	/** One row in the particle picker: a registry particle, or a saved particle-group JSON. */
	private record PickerEntry(String label, boolean isGroup, String id, JsonObject groupJson) {
	}

	/** All rows shown in the picker: saved groups first, then registry particles, filtered by search. */
	private List<PickerEntry> pickerEntries() {
		List<PickerEntry> list = new ArrayList<>();
		String q = particleSearch == null ? "" : particleSearch.trim().toLowerCase(java.util.Locale.ROOT);
		for (JsonObject g : pickerGroups) {
			String name = g != null && g.has("name") ? g.get("name").getAsString() : L10n.tr("anima.ui.prop.particle_group");
			if (q.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(q)) {
				list.add(new PickerEntry(name, true, null, g));
			}
		}
		for (String id : registryParticleIds()) {
			if (q.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(q)) {
				list.add(new PickerEntry(id, false, id, null));
			}
		}
		return list;
	}

	/** Reloads the saved particle-group JSONs shown at the top of the picker. */
	private void refreshPickerGroups() {
		pickerGroups = AnimationConfigStore.get().loadParticleGroups();
	}

	/** Closes the picker without rebuilding the screen — also hides its search-bar widget. */
	private void closeParticlePicker() {
		particleListOpen = false;
		if (searchBox != null) {
			searchBox.visible = false;
		}
	}

	/** Opens the picker: resets the search, scrolls to the current particle, focuses the search bar. */
	private void openParticlePicker() {
		particleListOpen = true;
		particleSearch = "";
		refreshPickerGroups();
		List<PickerEntry> entries = pickerEntries();
		int cur = -1;
		for (int i = 0; i < entries.size(); i++) {
			if (!entries.get(i).isGroup() && entries.get(i).id().equals(resolveParticle(selectedClip))) {
				cur = i;
				break;
			}
		}
		particleListScroll = Math.max(0, cur);
		focusSearch = true;
		init();
	}

	private int pmListX() { return propX() - 154; }
	private int pmListY() { return particleY(); }
	private int pmListW() { return 148; }
	private int pmListH() { return 170; }
	private int pmRowH() { return 12; }
	/** Rows visible below the search bar. */
	private int pmVisibleRows() { return Math.max(1, (pmListH() - PICKER_SEARCH_H - 6) / pmRowH()); }
	/** Window-local Y of the first list row (below the search bar). */
	private int pmRowsY() { return pmListY() + PICKER_SEARCH_H + 3; }

	/** Draws the expanded picker: search bar on top, saved groups then registry particles. */
	private void renderParticlePicker(GuiGraphics g) {
		if (!particleListOpen || selectedClip == null || !isParticleSelected()) {
			return;
		}
		int lx = pmListX();
		int ly = pmListY();
		int lw = pmListW();
		int lh = pmListH();
		g.fill(lx, ly, lx + lw, ly + lh, 0xE6121418);
		g.fill(lx, ly, lx + lw, ly + 1, GuiTheme.ACCENT);
		List<PickerEntry> entries = pickerEntries();
		String cur = resolveParticle(selectedClip);
		int rows = pmVisibleRows();
		int ry0 = pmRowsY();
		for (int i = 0; i < rows; i++) {
			int idx = particleListScroll + i;
			if (idx >= entries.size()) {
				break;
			}
			PickerEntry e = entries.get(idx);
			int ry = ry0 + i * pmRowH();
			boolean sel = !e.isGroup() && e.id().equals(cur);
			g.fill(lx + 1, ry, lx + lw - 1, ry + pmRowH() - 1, sel ? GuiTheme.ACCENT_DARK : GuiTheme.PANEL);
			String label = e.isGroup() ? L10n.tr("anima.ui.picker.group_prefix") + e.label() : e.label();
			g.drawString(this.font, font.plainSubstrByWidth(label, lw - 8), lx + 4, ry + 1,
				e.isGroup() ? GuiTheme.DE_GREEN : (sel ? 0xFFFFFFFF : GuiTheme.TEXT));
		}
	}

	/** True when the given window-local point is inside the particle picker rect. */
	private boolean inParticlePicker(int x, int y) {
		return particleListOpen && selectedClip != null && isParticleSelected()
			&& x >= pmListX() && x <= pmListX() + pmListW() && y >= pmListY() && y <= pmListY() + pmListH();
	}

	/** Shows a transient status line (e.g. "已保存粒子组"). */
	private void showNotice(String s) {
		notice = s;
		noticeUntil = System.currentTimeMillis() + 3000L;
	}

	// ------------------------------------------------------------------ /particle command popup

	/** Popup for typing a vanilla /particle command (确认 / 取消 at its bottom-right). */
	private static final int CMD_W_MAX = 420;
	private static final int CMD_H = 88;

	private boolean cmdEditorOpen;
	private EditBox cmdBox;
	private String cmdSeed = "";

	private int cmdW() { return Math.min(CMD_W_MAX, Math.max(220, pw - 40)); }
	private int cmdX() { return Math.max(6, (pw - cmdW()) / 2); }
	private int cmdY() { return Math.max(TOP_H + 34, (ph - CMD_H) / 2); }

	/** True when the point is inside the command popup (used to keep it modal). */
	private boolean inCommandEditor(double x, double y) {
		return cmdEditorOpen && x >= cmdX() - 4 && x <= cmdX() + cmdW() + 4
			&& y >= cmdY() - 4 && y <= cmdY() + CMD_H + 4;
	}

	private void closeCommandEditor() {
		cmdEditorOpen = false;
		cmdSeed = "";
		init();
	}

	private void confirmCommandEditor() {
		String text = cmdBox == null ? "" : cmdBox.getValue();
		cmdEditorOpen = false;
		cmdSeed = "";
		applyCommandText(text);
		init();
	}

	/** Builds the popup's widgets (only while it is open). */
	private void buildCommandEditor() {
		if (!cmdEditorOpen) {
			cmdBox = null;
			return;
		}
		cmdBox = new EditBox(this.font, cmdX() + 8, cmdY() + 24, cmdW() - 16, FIELD_H,
			Component.literal(L10n.tr("anima.ui.cmd.particle")));
		cmdBox.setMaxLength(512);
		cmdBox.setValue(cmdSeed);
		addRenderableWidget(cmdBox);
		cmdBox.setFocused(true);
		int bw = 58;
		int by = cmdY() + CMD_H - 24;
		addRenderableWidget(ThemeButton.of(cmdX() + cmdW() - 8 - bw, by, bw, 16, Component.literal(L10n.tr("anima.ui.button.confirm")),
			b -> confirmCommandEditor()));
		addRenderableWidget(ThemeButton.of(cmdX() + cmdW() - 8 - bw * 2 - 6, by, bw, 16, Component.literal(L10n.tr("anima.ui.button.cancel")),
			b -> closeCommandEditor()));
	}

	/** Popup background (drawn BEFORE the widgets so the field/buttons sit on top of it). */
	private void renderCommandEditorBackground(GuiGraphics g) {
		if (!cmdEditorOpen) {
			return;
		}
		g.fill(cmdX() - 4, cmdY() - 4, cmdX() + cmdW() + 4, cmdY() + CMD_H + 4, 0xFF14171C);
	}

	private void renderCommandEditor(GuiGraphics g) {
		if (!cmdEditorOpen) {
			return;
		}
		int x = cmdX();
		int y = cmdY();
		int w = cmdW();
		g.fill(x - 4, y - 4, x + w + 4, y - 3, GuiTheme.ACCENT);
		g.drawString(this.font, L10n.tr("anima.ui.button.import_command"), x + 8, y + 8, GuiTheme.ACCENT);
		drawSmallLabel(g, L10n.tr("anima.ui.cmd.example"), x + 8, y + 42,
			GuiTheme.SUBTEXT);
	}

	/** Panel background: nearly transparent over the world, solid in full-screen mode. */
	private int panelBg() {
		return overlay ? 0x44282828 : GuiTheme.PANEL;
	}

	private int handleBg() {
		return overlay ? 0x802E2E2E : 0xE02E2E2E;
	}

	/** Screen-space rect of a window handle (used for hit testing). */
	private boolean hitWindowHandle(double mx, double my, int region) {
		if (region == 1) {
			return mx >= palDx + 4 && mx <= palDx + 4 + (listW() - 4) && my >= palDy + paletteY0() - HANDLE_H && my <= palDy + paletteY0();
		}
		if (region == 2) {
			return mx >= propDx + propX() - 2 && mx <= propDx + propRight() && my >= propDy + TOP_H + 2 && my <= propDy + TOP_H + 2 + HANDLE_H;
		}
		// the timeline has no title bar: it is moved by its left button column (handled in
		// mouseClicked, so the buttons can distinguish a click from a drag)
		return false;
	}

	/** Keeps the world ticking/rendering while the floating editor is open. */
	@Override
	public boolean isPauseScreen() {
		return !overlay;
	}

	@Override
	public void removed() {
		releaseMovementKeys(); // never leave a forwarded movement key stuck down
		// hand the edited clips + text-object properties back to the caller once (the editor has no
		// save button; the caller decides when/whether to persist them, e.g. its config screen's save)
		if (!clipsHandedBack && onSave != null) {
			clipsHandedBack = true;
			onSave.accept(new ClipEditorResult(dumpClips(), dumpTextProps()));
		}
		if (activeOverlay == this) {
			activeOverlay = null;
			previewPositioned = false; // re-centre the view next time the editor opens
		}
		super.removed();
	}

	// ------------------------------------------------------------------ world-space preview object (3D text)

	private static boolean worldTextHookInstalled;    // the world render hook is registered once
	private static boolean worldText3DEnabled = true; // false = fall back to the HUD projection

	/** Draws the preview text with REAL 3D text (world space, perspective, depth occlusion). */
	private void renderWorldText3D(MultiBufferSource buffers, Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		if (!worldText3DEnabled || !worldPreviewActive || mc.level == null) {
			return;
		}
		String full = sampleFull();
		boolean charAnim = hasActiveCharAnim();
		int n = charAnim ? -1 : typewriterVisibleChars();
		String text = (n < 0 || n >= full.length()) ? full : full.substring(0, Math.max(0, n));
		RenderModifier m = currentModifier();
		float oop = propValue(4, playTimeMs);
		float osc = propValue(3, playTimeMs);
		if (text.isEmpty() || clamp01(m.a) * oop <= 0.02f) {
			return;
		}
		int a = Math.round(clamp01(m.a) * oop * 255f);
		int color = (a << 24) | (modColor(m) & 0x00FFFFFF);
		WorldText3D.Glyph[] glyphs = charAnim ? charTransforms(full) : null;
		float scale = TEXT_WORLD_SCALE * Math.max(0.01f, osc);
		if (!textDistanceScale) {
			// 距离缩放关闭：按距离补偿世界尺寸，使屏幕上的大小保持不变
			double dist = camera.getPosition().distanceTo(new net.minecraft.world.phys.Vec3(objectX(), objectY(), objectZ()));
			scale *= (float) Math.max(0.05, dist / DIST_SCALE_REF) * DIST_SCALE_OFF_BOOST;
		}
		WorldText3D.draw(buffers, camera, text, objectX(), objectY(), objectZ(), color,
			scale, m.tx, m.ty, m.sx, glyphs, 1f);
	}

	/** 1 GUI pixel = this many blocks for the preview text at 缩放 = 1. */
	private static final float TEXT_WORLD_SCALE = 0.018f;

	/** Distance (blocks) the base text scale is calibrated to; used when 距离缩放 is OFF. */
	private static final double DIST_SCALE_REF = 6.0;

	/**
	 * 距离缩放关闭时的额外放大：关闭后恒定大小是按 {@link #DIST_SCALE_REF} 校准的，在更近的
	 * 距离下会明显小于开启状态（近大远小），这里补一点尺寸，让两种状态的观感差距更小。
	 */
	private static final float DIST_SCALE_OFF_BOOST = 1.4f;

	/** World-pass entry: draws the 3D preview text of the active floating editor. */
	private static void renderWorldTextStatic(PoseStack pose, MultiBufferSource buffers,
			Camera camera, float partialTick) {
		CompositeEditScreen screen = activeOverlay;
		if (screen != null) {
			screen.renderWorldText3D(buffers, camera);
		}
	}

	// ------------------------------------------------------------------ world gizmo (origin + 3 axes)

	private static final int[] AXIS_COLORS = { 0xFFFF5A5A, 0xFF6BE86B, 0xFF5AA9FF }; // X red, Y green, Z blue
	private static final String[] AXIS_NAMES = { "X", "Y", "Z" };

	/** True when the gizmo has something to edit: the text object, or a selected particle clip. */
	private boolean gizmoVisible() {
		return worldPreviewActive && showGizmo && (selectedClip == null || isParticleSelected());
	}

	/** World position of the gizmo's attachment point. The TEXT object uses the keyframed 位置;
	 *  a particle / particle group has its OWN offset (ox/oy/oz), so moving a particle never
	 *  drags the sample text along. */
	private float[] gizmoPos() {
		float x = objectX();
		float y = objectY();
		float z = objectZ();
		if (selectedClip != null && isParticleSelected()) {
			x += (float) param(selectedClip, "ox", 0);
			y += (float) param(selectedClip, "oy", 0);
			z += (float) param(selectedClip, "oz", 0);
		}
		return new float[] { x, y, z };
	}

	/** Value the gizmo shows/edits on one axis: the text object's keyframed 位置, or the
	 *  selected particle / group's own offset relative to the object. */
	private float gizmoValue(int axis) {
		if (selectedClip == null) {
			return propValue(axis, playTimeMs);
		}
		String key = axis == 0 ? "ox" : (axis == 1 ? "oy" : "oz");
		return (float) param(selectedClip, key, 0);
	}

	/** Writes a gizmo drag back: keyframed object props for the text, own offset for particles. */
	private void setGizmoValue(int axis, float v) {
		if (selectedClip == null) {
			setPropAtPlayhead(axis, v);
			return;
		}
		String key = axis == 0 ? "ox" : (axis == 1 ? "oy" : "oz");
		setParam(key, Math.round(v * 1000.0) / 1000.0);
	}

	private float gizmoMin(int axis) {
		return selectedClip == null ? (float) propMin(axis) : -32f;
	}

	private float gizmoMax(int axis) {
		return selectedClip == null ? (float) propMax(axis) : 32f;
	}

	/** Screen position of the gizmo attachment point (the selected object). */
	private float[] gizmoOrigin() {
		float[] p = gizmoPos();
		return WorldProjection.project(p[0], p[1], p[2]);
	}

	/** Screen position of the FIXED world origin (the anchor the offsets are relative to). */
	private float[] anchorScreen() {
		return WorldProjection.project(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
	}

	/** Desired on-screen length of the gizmo's least foreshortened axis, in GUI px. Each axis is
	 *  drawn at this size scaled by how much the perspective foreshortens it, so the gizmo still
	 *  never grows/shrinks with the distance, but an axis that turns end-on shrinks towards the
	 *  origin instead of being drawn at full length in a direction that is pure projection noise. */
	private static final float GIZMO_LEN_PX = 52f;

	/** An axis whose drawn length would fall below this is hidden: it points (almost) straight at
	 *  the camera, so its screen direction is meaningless — exactly what 3D editors do with the
	 *  end-on axis. Kept above the grab radius so a hidden axis can never be picked. */
	private static final float GIZMO_MIN_AXIS_PX = 9f;

	/** Screen vector of ONE world block along {@code axis}, as {@code {dx, dy, len}} (len is the
	 *  foreshortened pixel length, 0 when the axis is end-on), or {@code null} when the attachment
	 *  point / the tip cannot be projected (behind the camera). */
	private float[] axisScreen(int axis) {
		float[] o = gizmoOrigin();
		float[] p = gizmoPos();
		if (o == null) {
			return null;
		}
		float[] e = WorldProjection.project(
			p[0] + (axis == 0 ? 1f : 0f), p[1] + (axis == 1 ? 1f : 0f), p[2] + (axis == 2 ? 1f : 0f));
		if (e == null) {
			return null;
		}
		double dx = e[0] - o[0];
		double dy = e[1] - o[1];
		double len = Math.hypot(dx, dy);
		if (!Double.isFinite(len)) {
			return null;
		}
		return new float[] { (float) dx, (float) dy, (float) len };
	}

	/** Pixels per world block of the least foreshortened axis — the reference that keeps the whole
	 *  gizmo at a constant on-screen size regardless of the camera angle and the distance. */
	private float gizmoRefPx() {
		float ref = 0f;
		for (int a = 0; a < 3; a++) {
			float[] s = axisScreen(a);
			if (s != null) {
				ref = Math.max(ref, s[2]);
			}
		}
		return ref < 0.05f ? 1f : ref; // every axis end-on (degenerate) → sane fallback
	}

	/** World length the drawn axes represent. Because each axis is drawn at
	 *  {@code GIZMO_LEN_PX * itsOwnForeshortening / ref}, all three represent this same length,
	 *  which is what a mouse drag along an axis is converted back with. */
	private float gizmoAxisLen() {
		return (float) Math.max(0.001f, Math.min(64.0f, GIZMO_LEN_PX / gizmoRefPx()));
	}

	/** Screen position of an axis tip: {@link #GIZMO_LEN_PX} reduced by the axis' own
	 *  foreshortening, so an end-on axis collapses into the origin. {@code null} when it points
	 *  away far enough that it must not be drawn at all. */
	private float[] axisTip(int axis) {
		float[] o = gizmoOrigin();
		float[] s = axisScreen(axis);
		if (o == null || s == null || s[2] < 1e-3f) {
			return null;
		}
		float len = GIZMO_LEN_PX * s[2] / gizmoRefPx();
		if (len < GIZMO_MIN_AXIS_PX) {
			return null;
		}
		float k = len / s[2]; // scale the unit-block screen vector up to the drawn length
		return new float[] { o[0] + s[0] * k, o[1] + s[1] * k, o[2] };
	}

	/** Draws the origin marker and the three draggable axes (Unity-style translate gizmo).
	 *  Everything is rasterised in PHYSICAL pixels (the pose is divided by the GUI scale), so the
	 *  coverage anti-aliasing produces smooth edges even with guiScale 3-4. */
	private void drawGizmo(GuiGraphics g) {
		if (!gizmoVisible()) {
			return;
		}
		float[] o = gizmoOrigin();
		if (o == null || o[2] <= 0.001f) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int scale = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
		g.pose().pushPose();
		g.pose().scale(1f / scale, 1f / scale, 1f); // 1 unit == 1 physical pixel from here on
		float ox = o[0] * scale;
		float oy = o[1] * scale;
		// the fixed world origin (offsets are relative to it) — a soft dot that never moves
		float[] a0 = anchorScreen();
		if (a0 != null && a0[2] > 0.001f) {
			drawDiscAA(g, a0[0] * scale, a0[1] * scale, 2.4f * scale, withAlpha(0xFFFFFFFF, 0.45f));
		}
		for (int a = 0; a < 3; a++) {
			float[] e = axisTip(a);
			if (e == null || e[2] <= 0.001f) {
				continue;
			}
			float ex = e[0] * scale;
			float ey = e[1] * scale;
			boolean hot = axisDrag == a;
			float core = (hot ? 3.0f : 2.0f) * scale;
			int col = AXIS_COLORS[a];
			// soft outer glow, then the solid core → edges fade instead of stepping
			drawLineAA(g, ox, oy, ex, ey, withAlpha(col, 0.22f), core + 2.0f * scale);
			drawLineAA(g, ox, oy, ex, ey, col, core);
			drawDiscAA(g, ex, ey, (hot ? 4.6f : 4.0f) * scale, withAlpha(col, 0.22f));
			drawDiscAA(g, ex, ey, (hot ? 3.2f : 2.7f) * scale, col);
		}
		// CENTRE marker: the emitter point of the particle / the centre of a group's particles.
		// (No spread ring here on purpose — it can be read as a stray circle on screen.)
		drawDiscAA(g, ox, oy, 3.6f * scale, withAlpha(0xFFFFFFFF, 0.16f));
		drawDiscAA(g, ox, oy, 1.6f * scale, 0xFFFFFFFF);
		g.pose().popPose();

		// axis letters stay in GUI space so they keep their normal size
		for (int a = 0; a < 3; a++) {
			float[] e = axisTip(a);
			if (e == null || e[2] <= 0.001f) {
				continue;
			}
			drawSmallLabel(g, AXIS_NAMES[a], Math.round(e[0]) + 4, Math.round(e[1]) - 10, AXIS_COLORS[a]);
		}
	}
	/** Anti-aliased line via scanline coverage of the line's rectangle — no per-pixel loop, so it
	 *  stays cheap even at guiScale 4 (a handful of quads per scanline). */
	private static void drawLineAA(GuiGraphics g, float x0, float y0, float x1, float y1, int color, float width) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		float half = Math.max(0.5f, width / 2f);
		if (len < 0.001f) {
			drawDiscAA(g, x0, y0, half, color);
			return;
		}
		float nx = -dy / len * half;
		float ny = dx / len * half;
		float ax = x0 + nx, ay = y0 + ny;
		float bx = x1 + nx, by = y1 + ny;
		float cx = x1 - nx, cy = y1 - ny;
		float dx2 = x0 - nx, dy2 = y0 - ny;
		int yTop = (int) Math.floor(Math.min(Math.min(ay, by), Math.min(cy, dy2)));
		int yBot = (int) Math.ceil(Math.max(Math.max(ay, by), Math.max(cy, dy2)));
		// safety valve: extremely long lines are drawn with a coarser band so the fill count stays low
		int band = (yBot - yTop) > 700 ? 2 : 1;
		int rgb = color & 0x00FFFFFF;
		int baseA = (color >>> 24) & 0xFF;
		for (int py = yTop; py <= yBot; py += band) {
			int n0 = quadCrossings(ax, ay, bx, by, cx, cy, dx2, dy2, py);
			float lo0 = Float.MAX_VALUE;
			float hi0 = -Float.MAX_VALUE;
			for (int i = 0; i < n0; i++) {
				lo0 = Math.min(lo0, SPAN_X[i]);
				hi0 = Math.max(hi0, SPAN_X[i]);
			}
			int n1 = quadCrossings(ax, ay, bx, by, cx, cy, dx2, dy2, py + band);
			float lo1 = Float.MAX_VALUE;
			float hi1 = -Float.MAX_VALUE;
			for (int i = 0; i < n1; i++) {
				lo1 = Math.min(lo1, SPAN_X[i]);
				hi1 = Math.max(hi1, SPAN_X[i]);
			}
			float w0 = n0 >= 2 ? hi0 - lo0 : 0f;
			float w1 = n1 >= 2 ? hi1 - lo1 : 0f;
			float cov = (w0 + w1) * 0.5f; // average coverage of this band
			if (cov <= 0.01f) {
				continue;
			}
			float xa = Math.min(n0 >= 2 ? lo0 : Float.MAX_VALUE, n1 >= 2 ? lo1 : Float.MAX_VALUE);
			float xb = Math.max(n0 >= 2 ? hi0 : -Float.MAX_VALUE, n1 >= 2 ? hi1 : -Float.MAX_VALUE);
			if (xb <= xa) {
				continue;
			}
			int a = (int) Math.round(Math.min(1f, cov) * baseA);
			g.fill((int) Math.floor(xa), py, (int) Math.ceil(xb), py + band, (a << 24) | rgb);
		}
	}

	private static final float[] SPAN_X = new float[4];

	/** Collects the scanline crossings of a convex quad into {@link #SPAN_X}; returns the count. */
	private static int quadCrossings(float x1, float y1, float x2, float y2, float x3, float y3,
			float x4, float y4, float y) {
		int n = 0;
		float v = crossX(x1, y1, x2, y2, y);
		if (!Float.isNaN(v)) {
			SPAN_X[n++] = v;
		}
		v = crossX(x2, y2, x3, y3, y);
		if (!Float.isNaN(v)) {
			SPAN_X[n++] = v;
		}
		v = crossX(x3, y3, x4, y4, y);
		if (!Float.isNaN(v)) {
			SPAN_X[n++] = v;
		}
		v = crossX(x4, y4, x1, y1, y);
		if (!Float.isNaN(v)) {
			SPAN_X[n++] = v;
		}
		return n;
	}

	private static float crossX(float x1, float y1, float x2, float y2, float y) {
		if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
			return x1 + (y - y1) / (y2 - y1) * (x2 - x1);
		}
		return Float.NaN;
	}

	/** Anti-aliased filled disc, also scanline based (one quad per row). */
	private static void drawDiscAA(GuiGraphics g, float cx, float cy, float r, int color) {
		if (r <= 0.2f) {
			return;
		}
		int yTop = (int) Math.floor(cy - r);
		int yBot = (int) Math.ceil(cy + r);
		int rgb = color & 0x00FFFFFF;
		int baseA = (color >>> 24) & 0xFF;
		for (int py = yTop; py <= yBot; py++) {
			float w0 = chord(r, cy, py);
			float w1 = chord(r, cy, py + 1);
			float cov = Math.min(1f, (w0 + w1) * 0.5f);
			if (cov <= 0.01f) {
				continue;
			}
			float halfW = Math.max(w0, w1) * 0.5f;
			int a = (int) Math.round(cov * baseA);
			g.fill(Math.round(cx - halfW), py, Math.round(cx + halfW), py + 1, (a << 24) | rgb);
		}
	}

	/** Chord length of a circle at scanline y (0 outside the circle). */
	private static float chord(float r, float cy, float y) {
		float dy = y - cy;
		float t = r * r - dy * dy;
		return t <= 0f ? 0f : 2f * (float) Math.sqrt(t);
	}

	/** Which axis (0=X,1=Y,2=Z) is under the cursor, or -1. */
	private int hitGizmoAxis(double mx, double my) {
		float[] o = gizmoOrigin();
		if (o == null || o[2] <= 0.001f) {
			return -1;
		}
		for (int a = 0; a < 3; a++) {
			float[] e = axisTip(a);
			if (e == null || e[2] <= 0.001f) {
				continue;
			}
			if (distToSegment(mx, my, o[0], o[1], e[0], e[1]) <= 7.0) {
				return a;
			}
		}
		return -1;
	}

	private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int thick) {
		drawLineAA(g, x0, y0, x1, y1, color, thick);
	}

	/** Scales the alpha channel of an ARGB colour. */
	private static int withAlpha(int argb, float f) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	private static double distToSegment(double px, double py, double x0, double y0, double x1, double y1) {		double dx = x1 - x0;
		double dy = y1 - y0;
		double len2 = dx * dx + dy * dy;
		if (len2 <= 1e-6) {
			return Math.hypot(px - x0, py - y0);
		}
		double t = Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / len2));
		return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
	}

	// ------------------------------------------------------------------ per-character text animations

	/** Character-level entry/exit animations: they move/scale EACH CHARACTER on its own clock
	 *  (打字入场/出场 plus the falling / drifting variants), unlike the whole-string effects. */
	private static boolean isCharAnim(String key) {
		return CharClips.isCharClip(key);
	}

	/** The character clips currently on the timeline (see {@link CharClips}). */
	private List<CharClips.Clip> charClips() {
		List<CharClips.Clip> out = new ArrayList<>();
		for (CompositeClip c : allClips()) {
			if (CharClips.isCharClip(c.effect())) {
				out.add(new CharClips.Clip(c.effect(), c.startMs(), c.durationMs(), c.speed()));
			}
		}
		return out;
	}

	/** True while at least one character animation clip is running (the preview then draws the
	 *  text character by character instead of as one string). */
	private boolean hasActiveCharAnim() {
		return CharClips.isActive(charClips(), playTimeMs);
	}

	/** Combined per-character transforms of every active character animation (null = none). */
	private WorldText3D.Glyph[] charTransforms(String text) {
		return CharClips.compute(text, charClips(), playTimeMs);
	}

	/** Draws text character by character, each with its own offset/scale/rotation/alpha. */
	private void drawChars(GuiGraphics g, Font font, String text, float centreX, float y, int color,
			WorldText3D.Glyph[] ts, float scale) {
		float totalW = font.width(text) * scale;
		float x = centreX - totalW / 2f;
		int baseA = (color >>> 24) & 0xFF;
		int rgb = color & 0x00FFFFFF;
		for (int i = 0; i < text.length(); i++) {
			WorldText3D.Glyph t = ts[i];
			String ch = String.valueOf(text.charAt(i));
			float cw = font.width(ch) * scale;
			if (t.alpha > 0.02f) {
				float s = scale * t.scale;
				int a = Math.round(baseA * clamp01(t.alpha));
				g.pose().pushPose();
				// (x + cw/2, y + 4) is the glyph's centre → scale and rotation happen around it
				g.pose().translate(x + cw / 2f + t.dx, y + 4f + t.dy, 0f);
				g.pose().scale(s, s, 1f);
				if (t.rot != 0f) {
					g.pose().mulPose(new org.joml.Quaternionf().rotateZ(t.rot));
				}
				g.drawString(font, ch, -font.width(ch) / 2, -4, (a << 24) | rgb, false);
				g.pose().popPose();
			}
			x += cw;
		}
	}

	private RenderModifier currentModifier() {
		RenderModifier.Builder b = RenderModifier.builder();
		for (CompositeClip c : allClips()) {
			float localMs = (playTimeMs - c.startMs()) * c.speed();
			if (localMs < 0 || localMs > c.durationMs() || c.effect().startsWith("particle_")) {
				continue;
			}
			IAnimationEffect ef = effectFor(c.effect());
			if (ef != null) {
				ef.apply(Math.max(0, localMs) / 1000f, Math.max(1, c.durationMs()) / 1000f, random, b);
			}
		}
		return b.build();
	}

	// ------------------------------------------------------------------ real 3D particles in the world

	private static void tickOverlayStatic() {
		CompositeEditScreen screen = activeOverlay;
		if (screen != null) {
			screen.emitWorldParticles();
		}
	}

	/**
	 * Emits real 3D vanilla particles at the world-space preview position while a
	 * 自定义粒子 clip is playing. The particles live in the client level, so they have
	 * real depth/occlusion and are simulated by the vanilla particle engine.
	 */
	/** Total number of particles a clip should have spawned so far, and how many we already did.
	 *  数量 now means the TOTAL for the whole clip (1 = exactly one particle), not per tick. */
	private final java.util.Map<CompositeClip, int[]> emittedByClip = new java.util.IdentityHashMap<>();
	private float lastEmitPlayhead = -1f;

	private void emitWorldParticles() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		if (playTimeMs < lastEmitPlayhead) {
			emittedByClip.clear(); // the playhead jumped backwards → replay from the start
		}
		lastEmitPlayhead = playTimeMs;
		for (CompositeClip c : allClips()) {
			if (!c.effect().startsWith("particle_")) {
				continue;
			}
			float local = (playTimeMs - c.startMs()) * c.speed();
			float dur = Math.max(1f, c.durationMs());
			if (local < 0 || local > dur) {
				emittedByClip.remove(c);
				continue;
			}
			float progress = local / dur;
			// the clip's own offset relative to the object (ox/oy/oz) — particles move on their
			// own, independently of the text object's keyframed position
			double ox = param(c, "ox", 0);
			double oy = param(c, "oy", 0);
			double oz = param(c, "oz", 0);
			if ("particle_group".equals(c.effect())) {
				// a group emits every entry it holds, each with its own count / spread / speed
				com.google.gson.JsonArray items = groupItems(c);
				int[] done = emittedByClip.computeIfAbsent(c, k -> new int[Math.max(1, items.size())]);
				for (int i = 0; i < items.size(); i++) {
					if (!items.get(i).isJsonObject()) {
						continue;
					}
					com.google.gson.JsonObject it = items.get(i).getAsJsonObject();
					com.google.gson.JsonElement idEl = it.get("id");
					if (idEl == null || i >= done.length) {
						continue;
					}
					// every child has its OWN window inside the group: it only emits while the
					// playhead is inside [s, s+d] and uses that window for its progress
					float cs = (float) groupItemStart(c, i);
					float cd = (float) Math.max(16f, Math.min(groupItemDur(c, i), Math.max(16f, dur - cs)));
					float localItem = local - cs;
					if (localItem < 0 || localItem > cd) {
						done[i] = 0; // outside its window → it will replay when re-entered
						continue;
					}
					float itemProgress = Math.max(0f, Math.min(1f, localItem / cd));
					int n = emitProgress(idEl.getAsString(),
						objectX() + ox, objectY() + oy, objectZ() + oz,
						groupItemCount(c, i),
						groupItemParam(c, i, "dx", 0), groupItemParam(c, i, "dy", 0), groupItemParam(c, i, "dz", 0),
						groupItemParam(c, i, "speed", 0), itemProgress, done, i);
					if (n > 0) {
						done[i] += n;
					}
				}
				continue;
			}
			if (!"particle_3d".equals(c.effect())) {
				continue;
			}
			int[] done = emittedByClip.computeIfAbsent(c, k -> new int[1]);
			// own offset relative to the object + vanilla spread/speed, so the particle stays
			// where it is put instead of drifting with the text
			int n = emitProgress(resolveParticle(c), objectX() + ox, objectY() + oy, objectZ() + oz,
				p3dParam(c, "count", 1),
				param(c, "dx", 0), param(c, "dy", 0), param(c, "dz", 0), param(c, "speed", 0),
				progress, done, 0);
			if (n > 0) {
				done[0] += n;
			}
		}
	}

	/** Emits only the particles that are "due" at {@code progress} of the clip (total = count).
	 *  Total 1 emits its single particle right at the start (floor would delay it past the clip
	 *  end — that is why "1 particle" used to show nothing). {@code deltaX/Y/Z} and {@code speed}
	 *  follow vanilla {@code /particle} semantics: 0/0/0 + 0 keeps every particle exactly fixed. */
	private int emitProgress(String id, double x, double y, double z, int total,
			double deltaX, double deltaY, double deltaZ, double speed, float progress, int[] done, int idx) {
		if (total <= 0 || progress <= 0f) {
			return 0;
		}
		int want = Math.max(1, (int) Math.floor(total * progress));
		int missing = Math.max(0, want - done[idx]);
		if (missing <= 0) {
			return 0;
		}
		int n = Math.min(missing, 8); // a few per frame at most, so bursts stay smooth
		WorldParticles.emit(id, x, y, z, n, deltaX, deltaY, deltaZ, speed);
		return n;
	}

	/** One-line operation hints drawn directly on the background, flush to the far left (no box). */
	private void renderGuide(GuiGraphics g) {
		int gy = (int) (timelineY() - 14);
		g.drawString(this.font, L10n.tr("anima.ui.guide.timeline"),
			4, gy, GuiTheme.SUBTEXT);
	}

	private void renderPalette(GuiGraphics g, int mouseX, int mouseY) {
		int y0 = paletteY0();
		int h = paletteH();
		// the window background starts at the SAME top line as the properties window (its title bar
		// sits inside that background), so the two top-corner windows line up exactly
		g.fill(4, TOP_H + 2, listW(), y0 + h, panelBg());
		int rowH = 20;
		int visible = (h - 4) / rowH;
		List<Object> rows = rows();
		// which row the pointer is over (for hover feedback / collapse affordance)
		int hoverRow = (mouseX >= 6 && mouseX <= listW() && mouseY >= y0 && mouseY <= y0 + h)
			? (mouseY - y0 - 4) / rowH + listScroll : -1;
		for (int i = 0; i < visible; i++) {
			int idx = listScroll + i;
			if (idx >= rows.size()) {
				break;
			}
			Object o = rows.get(idx);
			int y = y0 + 4 + i * rowH;
			boolean over = idx == hoverRow;
			if (o instanceof EffectPreset p) {
				// hover highlight only — the vertical bar was removed; the block is centred on
				// the 20px row so it never looks shifted up/down relative to the text
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.drawString(this.font, PRESET_MARK + p.name(), 8, y + 5, over ? GuiTheme.WARN : GuiTheme.TEXT);
			} else if (o instanceof PaletteGroup pg) {
				// saved particle-group JSON → click to add it to the timeline
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.drawString(this.font, font.plainSubstrByWidth("≡ " + pg.name(), listW() - 12), 8, y + 5,
					over ? GuiTheme.WARN : GuiTheme.DE_GREEN);
			} else { // group header → collapse toggle: "- " when open / "+ " when closed
				String cat = (String) o;
				boolean open = !collapsedCats.contains(cat);
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.drawString(this.font, (open ? "- " : "+ ") + cat, 8, y + 5, GuiTheme.ACCENT);
			}
		}
		// drag ghost follows the cursor
		if (paletteDrag != null) {
			EffectPreset p = palette().stream().filter(e -> e.key().equals(paletteDrag)).findFirst().orElse(null);
			if (p != null) {
				int gx = mouseX + 8, gy = mouseY + 2;
				g.fill(gx - 2, gy - 2, gx + this.font.width(p.name()) + 6, gy + 10, GuiTheme.PANEL);
				g.drawString(this.font, p.name(), gx, gy, GuiTheme.WARN);
			}
		}

		// "+" menu: 自定义粒子 (opened from the palette; groups are created on the timeline)
		if (addMenuOpen) {
			int mx = listW() + 6;
			int my = addMenuY;
			g.fill(mx, my, mx + 92, my + 20, 0xE6121418);
			g.fill(mx, my, mx + 92, my + 1, GuiTheme.ACCENT);
			g.drawString(this.font, L10n.tr("anima.ui.effect.custom_particle"), mx + 6, my + 6, GuiTheme.TEXT);
		}
	}

	private void renderCenter(GuiGraphics g) {
		int cx = centerX();
		int cy = centerY();
		int cw = centerW();
		int ch = centerH();
		if (!overlay) {
			// full-screen editor: opaque preview canvas. In the floating window the middle is
			// left empty on purpose — the real world behind it IS the preview.
			g.fill(cx - 2, cy - 2, cx + cw + 2, cy + ch + 2, GuiTheme.BORDER);
			g.fill(cx, cy, cx + cw, cy + ch, 0xFF000000);
		}

		boolean anyParticle = false;
		RenderModifier.Builder b = RenderModifier.builder();
		for (CompositeClip c : allClips()) {
			float localMs = (playTimeMs - c.startMs()) * c.speed();
			if (localMs < 0 || localMs > c.durationMs()) {
				continue;
			}
			if (c.effect().startsWith("particle_")) {
				anyParticle = true;
				continue; // particles are rendered separately below
			}
			IAnimationEffect ef = effectFor(c.effect());
			if (ef != null) {
				ef.apply(Math.max(0, localMs) / 1000f, Math.max(1, c.durationMs()) / 1000f, random, b);
			}
		}
		RenderModifier m = b.build();

		// typewriter / falling / drifting clips animate EACH character (see drawChars); the plain
		// typewriter prefix is only used by the whole-string path
		boolean charAnim = hasActiveCharAnim();
		typedVisible = charAnim ? -1 : typewriterVisibleChars();
		if (worldPreviewActive || overlay) {
			// the preview object lives in the world — nothing extra is drawn over it here
		} else if (charAnim) {
			// per-character preview: keep the same centre/anchor as the default renderer
			WorldText3D.Glyph[] ts = charTransforms(sampleFull());
			if (ts != null) {
				drawChars(g, this.font, sampleFull(), cx + cw / 2f + m.tx, cy + (ch - 9) / 2f + m.ty,
					modColor(m), ts, Math.max(0.01f, m.sx));
			}
		} else {
			// library's own preview, or a custom one registered by another mod
			previewRenderer().render(g, this.font, cx, cy, cw, ch, m);
		}
		// Particles are emitted around the (typed) sample text, not the whole preview window.
		if (anyParticle && !worldPreviewActive && !overlay) {
			String sample = sampleText();
			int tw = font.width(sample);
			int tX = cx + (cw - tw) / 2 + Math.round(m.tx);
			int tY = cy + (ch - 9) / 2 + Math.round(m.ty);
			int tCX = tX + tw / 2;
			int tCY = tY + 4;
			int bw = Math.max(40, tw + 30); // compact horizontal spread around the text
			int bh = 46;                    // compact vertical band around the text baseline
			for (CompositeClip pc : allClips()) {
				float pLocal = (playTimeMs - pc.startMs()) * pc.speed();
				if (pLocal < 0 || pLocal > pc.durationMs() || !pc.effect().startsWith("particle_")) {
					continue;
				}
				drawParticles(g, particleKind(resolveParticle(pc)), pLocal, pLocal / Math.max(1f, pc.durationMs()),
					tCX, tCY, bw, bh);
			}
		}
		typedVisible = -1;
	}

	private int typewriterVisibleChars() {
		int n = -1;
		int len = sampleFull().length();
		for (CompositeClip c : allClips()) {
			String eff = c.effect();
			boolean in = "typewriter_in".equals(eff);
			boolean out = "typewriter_out".equals(eff);
			if (!in && !out) {
				continue;
			}
			float localMs = (playTimeMs - c.startMs()) * c.speed();
			if (localMs < 0 || localMs > c.durationMs()) {
				continue;
			}
			float p = clamp01(localMs / Math.max(1f, c.durationMs()));
			int cnt = Math.round(len * (in ? p : (1f - p)));
			n = n < 0 ? cnt : Math.min(n, cnt);
		}
		return n;
	}

	private static float frac(float v) { return v - (float) Math.floor(v); }

	private static float prand(int i, float seed) {
		double v = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453;
		return (float) (v - Math.floor(v));
	}

	/** Kind name of a particle id: strip the namespace, keep the last underscore segment. */
	private static String particleKind(String key) {
		String k = key;
		int ns = k.indexOf(':');
		if (ns >= 0) {
			k = k.substring(ns + 1);
		}
		int i = k.lastIndexOf('_');
		return i < 0 ? k : k.substring(i + 1);
	}

	/** Vanilla-ish colour for a registered particle id (hash fallback for mod particles). */
	private static int particleColor(String id) {
		int c = switch (particleKind(id)) {
			case "flame" -> 0xE2882E;
			case "soul" -> 0x49C4C9;
			case "heart" -> 0xE24D7E;
			case "angry_villager" -> 0x9FD24C;
			case "note" -> 0x62C94C;
			case "explosion", "cloud", "poof", "smoke", "large_smoke", "campfire_smoke", "dragon_breath", "falling_dust" -> 0xE6E6E6;
			case "star", "glint", "totem", "end_rod", "electric_spark", "crit" -> 0xFFD36B;
			case "portal", "enchant", "sweep_attack" -> 0x9B59D0;
			case "bubble", "splash", "drip_water", "snowflake", "sneeze" -> 0x7FD0FF;
			default -> hashColor(id);
		};
		return c;
	}

	/** Deterministic bright colour from a string hash (for mod / unknown particle ids). */
	private static int hashColor(String s) {
		int h = Math.floorMod(s.hashCode(), 360);
		float x = 1f - Math.abs((h / 60f) % 2f - 1f);
		float r = 0, gg = 0, b = 0;
		if (h < 60) { r = 1; gg = x; }
		else if (h < 120) { r = x; gg = 1; }
		else if (h < 180) { gg = 1; b = x; }
		else if (h < 240) { gg = x; b = 1; }
		else if (h < 300) { r = x; b = 1; }
		else { r = 1; b = x; }
		return (Math.round(r * 255) << 16) | (Math.round(gg * 255) << 8) | Math.round(b * 255);
	}

	/** 2D particle template renderer ({@code kind} = particle id tail). */
	private void drawParticles(GuiGraphics g, String kind, float localMs, float t,
			int X, int Y, int W, int H) {
		switch (kind) {
			case "flame" -> renderFlame(g, t, localMs, X, Y, W, H);
			case "soul" -> renderSoul(g, t, localMs, X, Y, W, H);
			case "heart", "angry_villager" -> renderHeart(g, t, localMs, X, Y, W, H);
			case "note" -> renderNote(g, t, localMs, X, Y, W, H);
			case "explosion" -> renderExplosion(g, t, X, Y, W, H);
			case "cloud", "poof" -> renderCloud(g, t, localMs, X, Y, W, H);
			case "smoke", "large_smoke", "campfire_smoke", "dragon_breath" -> renderSmoke(g, t, localMs, X, Y, W, H);
			case "star" -> renderStar(g, t, localMs, X, Y, W, H);
			case "wave" -> renderWave(g, t, X, Y, W, H);
			case "bubble", "splash", "drip_water", "snowflake", "electric_spark" -> renderBubble(g, t, X, Y, W, H);
			case "portal", "enchant", "crit", "sweep_attack" -> renderPortal(g, t, X, Y, W, H);
			default -> renderSparkle(g, t, localMs, X, Y, W, H); // totem, glint, end_rod, falling_dust, unknown
		}
	}

	/**
	 * Draw a soft circular glow (layered translucent squares instead of a hard square).
	 * {@code rgb} is 0xRRGGBB without an alpha byte; {@code alpha} is the base opacity 0-255.
	 */
	private static void glow(GuiGraphics g, int px, int py, int size, int rgb, int alpha) {
		int s = Math.max(1, size);
		for (int d = s; d >= 1; d--) {
			int a = alpha * (s - d + 1) / (s + 1);
			g.fill(px - d, py - d, px + d + 1, py + d + 1, ((a & 0xFF) << 24) | (rgb & 0xFFFFFF));
		}
	}

	/** 粒子·火焰 — 橙黄火苗向上飘动闪烁（减弱横向抖动） */
	private void renderFlame(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 28; i++) {
			float ph = frac(t + prand(i, 1.3f));
			float sx = prand(i, 2.4f);
			int px = X + (int) (W * (0.2f + 0.6f * sx)) + (int) (Math.sin(ms * 0.4f + i * 1.3f) * 1.5f);
			int py = Y + (int) (H * (0.95f - 0.62f * ph));
			int a = (int) (240f * (1f - ph) * Math.max(0f, 1.05f - ph * 1.5f));
			a = Math.max(0, Math.min(255, a));
			glow(g, px, py, ph > 0.7f ? 2 : 3, 0xE2882E, a);
			if (a > 110) {
				glow(g, px, py, 1, 0xF7E8B0, a);
			}
		}
	}

	/** 粒子·灵魂 — 青蓝灵魂火苗上飘 */
	private void renderSoul(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 22; i++) {
			float ph = frac(t + prand(i, 2.2f));
			float sx = prand(i, 0.9f);
			int px = X + (int) (W * (0.25f + 0.5f * sx)) + (int) (Math.cos(ms * 0.35f + i) * 2f);
			int py = Y + (int) (H * (0.9f - 0.6f * ph));
			int a = (int) (230f * (1f - ph));
			a = Math.max(0, Math.min(255, a));
			glow(g, px, py, 3, 0x49C4C9, a);
			if (a > 100) {
				glow(g, px, py, 1, 0xBCF2F5, a);
			}
		}
	}

	/** 粒子·爱心 — 粉色心形上浮摆动 */
	private void renderHeart(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 14; i++) {
			float ph = frac(t * 0.5f + prand(i, 3.1f));
			float sx = prand(i, 0.7f);
			int px = X + (int) (W * (0.25f + 0.5f * sx)) + (int) (Math.sin(ms * 0.35f + i) * 1.5f);
			int py = Y + (int) (H * (0.9f - 0.6f * ph));
			int a = (int) (255f * (1f - ph));
			a = Math.min(255, a);
			int s = ph < 0.8f ? 3 : 2;
			glow(g, px, py, s, 0xE24D7E, a);
			glow(g, px - 1, py + s / 2, 1, 0xE24D7E, a); // heart notch
		}
	}

	/** 粒子·爆炸 — 灰白烟尘径向扩散，中心发白 */
	private void renderExplosion(GuiGraphics g, float t, int X, int Y, int W, int H) {
		int midX = X + W / 2, midY = Y + H / 2;
		float maxR = Math.min(W, H) * 0.5f;
		for (int i = 0; i < 50; i++) {
			float ang = prand(i, 5.5f) * (float) Math.PI * 2f;
			float r = maxR * (float) Math.sqrt(prand(i, 9.1f)) * t;
			int px = midX + (int) (Math.cos(ang) * r);
			int py = midY + (int) (Math.sin(ang) * r);
			int a = (int) (255f * Math.sin(Math.min(1f, t * 3f) * (float) Math.PI));
			a = Math.max(0, Math.min(255, a));
			glow(g, px, py, 3, 0xD8D8D8, a);
			if (a > 120) {
				glow(g, px, py, 1, 0xFFEFB0, a);
			}
		}
	}

	/** 粒子·音符 — 绿色音符上飘 */
	private void renderNote(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 10; i++) {
			float ph = frac(t + prand(i, 2.2f));
			float sx = prand(i, 4.4f);
			int px = X + (int) (W * (0.22f + 0.56f * sx)) + (int) (Math.sin(ph * (float) Math.PI) * 6f);
			int py = Y + (int) (H * (0.85f - 0.6f * ph));
			int a = (int) (255f * (1f - ph));
			a = Math.min(255, a);
			glow(g, px, py, 2, 0x62C94C, a);
			glow(g, px, py + 2, 1, 0x62C94C, a); // note stem hint
		}
	}

	/** 粒子·云 — 蓬松白雾缓慢上浮 */
	private void renderCloud(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 26; i++) {
			float ph = frac(t * 0.4f + prand(i, 6.0f));
			float sx = prand(i, 1.9f);
			int px = X + (int) (W * (0.12f + 0.76f * sx)) + (int) (Math.sin(ms * 0.2f + i * 0.9f) * 3f);
			int py = Y + (int) (H * (0.75f - 0.5f * ph));
			int a = (int) (150f * (1f - ph));
			a = Math.max(0, Math.min(150, a));
			glow(g, px, py, 3, 0xFFFFFF, a);
		}
	}

	/** 粒子·烟 — 灰色烟柱缓慢上升 */
	private void renderSmoke(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 24; i++) {
			float ph = frac(t * 0.6f + prand(i, 3.3f));
			float sx = prand(i, 5.1f);
			int px = X + (int) (W * (0.2f + 0.6f * sx)) + (int) (Math.sin(ms * 0.15f + i * 1.1f) * 3f);
			int py = Y + (int) (H * (0.85f - 0.6f * ph));
			int a = (int) (120f * (1f - ph));
			a = Math.max(0, Math.min(120, a));
			glow(g, px, py, 2 + (int) (ph * 2), 0x9AA0A8, a);
		}
	}

	/** 粒子·星尘 — 黄色闪光闪烁 */
	private void renderStar(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 16; i++) {
			float ph = frac(t + prand(i, 8.7f));
			float sx = prand(i, 3.3f);
			int px = X + (int) (W * (0.2f + 0.6f * sx));
			int py = Y + (int) (H * (0.8f - 0.6f * ph));
			int a = (int) (255f * (1f - ph));
			a = Math.max(0, Math.min(255, a));
			if (ph > 0.85f) {
				continue;
			}
			glow(g, px, py, 3, 0xFFE066, a);
			glow(g, px, py, 1, 0xFFFFFF, a);
		}
	}

	/** 粒子·涟漪 — 中心扩散的水波圆环 */
	private void renderWave(GuiGraphics g, float t, int X, int Y, int W, int H) {
		int midX = X + W / 2, midY = Y + H / 2;
		float maxR = Math.min(W, H) * 0.5f;
		for (int ring = 0; ring < 4; ring++) {
			float prog = frac(t + (float) ring / 4f);
			int rad = (int) (maxR * prog);
			int a = (int) (200f * (1f - prog));
			a = Math.max(0, Math.min(255, a));
			for (int s = 0; s < 10; s++) {
				float ang = s * (float) Math.PI * 2f / 10f;
				int rx = midX + (int) (Math.cos(ang) * rad);
				int ry = midY + (int) (Math.sin(ang) * rad);
				glow(g, rx, ry, 2, 0x5EC8FF, a);
			}
		}
	}

	/** 粒子·气泡 — 蓝色气泡上浮 */
	private void renderBubble(GuiGraphics g, float t, int X, int Y, int W, int H) {
		int midX = X + W / 2;
		for (int i = 0; i < 18; i++) {
			float ph = frac(t * 0.8f + prand(i, 4.0f));
			float sx = prand(i, 1.7f);
			int px = midX + (int) ((sx - 0.5f) * W * 0.5f);
			int py = Y + (int) (H * (0.85f - 0.6f * ph));
			int a = (int) (180f * (1f - ph));
			a = Math.max(0, Math.min(255, a));
			glow(g, px, py, 2, 0x7FD0FF, a);
			if (a > 90) {
				glow(g, px, py, 1, 0xFFFFFF, a);
			}
		}
	}

	/** 粒子·传送门 — 紫色涡旋环绕 */
	private void renderPortal(GuiGraphics g, float t, int X, int Y, int W, int H) {
		int midX = X + W / 2, midY = Y + H / 2;
		float maxR = Math.min(W, H) * 0.5f;
		for (int i = 0; i < 20; i++) {
			float ang = t * ((float) Math.PI * 2f) + prand(i, 6.6f) * 6f;
			float r = maxR * (0.15f + 0.85f * prand(i, 2.1f));
			int px = midX + (int) (Math.cos(ang) * r);
			int py = midY + (int) (Math.sin(ang) * r);
			int a = (int) (220f * (0.4f + 0.6f * (float) Math.abs(Math.sin(ang))));
			a = Math.max(30, Math.min(255, a));
			glow(g, px, py, 2, 0x9B59D0, a);
			if (a > 130) {
				glow(g, px, py, 1, 0xE7C3FF, a);
			}
		}
	}

	/** 粒子·闪光 — 金色闪烁（默认兜底：totem / glint / end_rod / falling_dust / 未知） */
	private void renderSparkle(GuiGraphics g, float t, float ms, int X, int Y, int W, int H) {
		for (int i = 0; i < 20; i++) {
			float ph = frac(t + prand(i, 9.2f));
			float sx = prand(i, 5.7f);
			int px = X + (int) (W * (0.15f + 0.7f * sx));
			int py = Y + (int) (H * (0.8f - 0.6f * ph));
			int a = (int) (255f * (1f - ph));
			a = Math.max(0, Math.min(255, a));
			if (ph > 0.8f) {
				continue;
			}
			glow(g, px, py, 2, 0xFFD36B, a);
			glow(g, px, py, 1, 0xFFFFFF, a);
		}
	}

	private static int modColor(RenderModifier m) {
		return (Math.round(clamp01(m.a) * 255f) << 24)
			| (Math.round(clamp01(m.r) * 255f) << 16)
			| (Math.round(clamp01(m.g) * 255f) << 8)
			| Math.round(clamp01(m.b) * 255f);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private void renderProperty(GuiGraphics g) {
		int px = propX();
		int lx = px + PROP_INSET; // captions/values keep the same left inset as the palette's text
		int right = propRight();
		// left/right edges of every interactive control (the same rect the fields/buttons are built
		// with in buildPropertyPanel) so the particle-name row lines up with all of them
		int fieldR = px + PROP_W - 8;
		// panel height adapts to the content but is capped so it never covers the timeline;
		// anything taller than the cap scrolls (wheel over the panel)
		int bottom = propPanelBottom();
		g.fill(px - 2, TOP_H + 2, right, bottom, panelBg());
		boolean scrollClip = propMaxScroll() > 0;
		if (scrollClip) {
			// clip to the CONTENT area (below the title bar) so scrolled rows can never be drawn
			// over the window title
			g.enableScissor(px - 2 + propDx, propContentTop() + propDy, right + propDx, bottom + propDy);
		}
		if (selectedClip == null) {
			// no clip selected → only the text-object properties (+ keyframe buttons)
			renderPropLabels(g, px);
		} else {
			CompositeClip c = selectedClip;
			// (the window title lives in the drag handle drawn above this panel)
			// field captions are small (0.75×) and hug their inputs
			if (hasGroupChild()) {
				// the child selected on the timeline: ITS rows instead of the group's properties
				String[] labels = { L10n.tr("anima.ui.prop.particle_id"), L10n.tr("anima.ui.prop.count"), L10n.tr("anima.ui.prop.spread_x"), L10n.tr("anima.ui.prop.spread_y"), L10n.tr("anima.ui.prop.spread_z"), L10n.tr("anima.ui.prop.speed") };
				for (int k = 0; k < labels.length; k++) {
					int rowY = k == 0 ? particleY() : p3dParamY(k - 1);
					drawSmallLabel(g, labels[k], lx, rowY - 6, GuiTheme.SUBTEXT);
				}
				drawSmallLabel(g, L10n.tr("anima.ui.prop.start"), lx, childStartY() - 6, GuiTheme.SUBTEXT);
				drawSmallLabel(g, L10n.tr("anima.ui.prop.child_duration"), lx, childDurY() - 6, GuiTheme.SUBTEXT);
			} else {
				drawSmallLabel(g, L10n.tr("anima.ui.prop.name"), lx, nameY() - 6, GuiTheme.SUBTEXT);
				if (!hideSpeed()) {
					drawSmallLabel(g, L10n.tr("anima.ui.prop.speed_mul"), lx, speedY() - 6, GuiTheme.SUBTEXT);
				}
				if (isParticleGroup()) {
					// group summary row — expand/collapse is the -/+ on the timeline block
					int n = groupCount();
					g.fill(lx, particleY() + 1, fieldR, particleY() + 15, 0x602E2E2E);
					String head = L10n.tr("anima.ui.particle.group_summary", n,
						isGroupExpanded(c) ? L10n.tr("anima.ui.suffix.expanded") : L10n.tr("anima.ui.suffix.collapsed"));
					g.drawString(this.font, font.plainSubstrByWidth(head, fieldR - lx - 14), lx + 1, particleY() + 5, GuiTheme.ACCENT);
					drawSmallLabel(g, L10n.tr("anima.ui.prop.particle_group"), lx, particleY() - 6, GuiTheme.SUBTEXT);
				} else if (isParticleSelected()) {
					g.fill(lx, particleY() + 1, fieldR, particleY() + 15, 0x602E2E2E);
					g.drawString(this.font, font.plainSubstrByWidth(resolveParticle(c), fieldR - lx - 14), lx + 1, particleY() + 5, GuiTheme.ACCENT);
					g.drawString(this.font, particleListOpen ? "▴" : "▾", fieldR - 9, particleY() + 5, GuiTheme.TEXT);
					drawSmallLabel(g, L10n.tr("anima.ui.prop.particle_id"), lx, particleY() - 6, GuiTheme.SUBTEXT);
					if (isParticle3D()) {
						String[] labels = { L10n.tr("anima.ui.prop.count"), L10n.tr("anima.ui.prop.spread_x"), L10n.tr("anima.ui.prop.spread_y"), L10n.tr("anima.ui.prop.spread_z"), L10n.tr("anima.ui.prop.speed") };
						for (int k = 0; k < labels.length; k++) {
							drawSmallLabel(g, labels[k], lx, p3dParamY(k) - 6, GuiTheme.SUBTEXT);
						}
					}
				}
				drawSmallLabel(g, L10n.tr("anima.ui.prop.duration"), lx, durY() - 6, GuiTheme.SUBTEXT);
			}
			renderPropLabels(g, px);
		}
		if (scrollClip) {
			g.disableScissor();
		}
		// the picker pops out to the LEFT of the panel — draw it after the scissor is off,
		// otherwise it is clipped away and only stays clickable (the invisible-list bug)
		if (selectedClip != null) {
			renderParticlePicker(g);
		}
	}

	private void renderTimeline(GuiGraphics g, int mouseX, int mouseY) {
		int ty = timelineY();
		int tx = tlX();
		int tw = tlW();
		g.fill(6, ty, tx + tw + 1, ty + TIMELINE_H, panelBg());
		// the left column holds 播放 / 重头 / 清空 (and doubles as the window's move handle)
		drawControlButtons(g, mouseX, mouseY);

		// ruler with "nice" ticks: align to whole 100/200/500/1000…ms so 1s-landmarks appear
		g.fill(tx, ty, tx + tw, ty + 2, GuiTheme.BORDER);
		double px = pxPerMs();
		double visibleMs = tw / px;
		int stepMs = niceStep(visibleMs);
		int firstTick = (int) Math.ceil(viewOffsetMs / stepMs) * stepMs;
		for (int ms = firstTick; ; ms += stepMs) {
			int x = tx + (int) Math.round((ms - viewOffsetMs) * px);
			if (ms < 0) {
				continue;
			}
			if (ms > (int) timelineLen) {
				break; // don't draw a running-off infinite axis beyond the set length
			}
			if (x > tx + tw) {
				break;
			}
			if (x < tx - 30) {
				continue;
			}
			g.fill(x, ty, x + 1, ty + 4, GuiTheme.BORDER);
			drawSmallLabel(g, tickLabel(ms), x + 2, ty - 6, GuiTheme.SUBTEXT);
		}

		// lane rows (vertically scrollable with the wheel): lanes hold several non-overlapping
		// clips each; rows are automatic (grow on demand, removed when unused) and drawn with
		// only a 1px separator between them — no row background. The child rows of expanded
		// particle groups are drawn in a LATER pass (see renderGroupChildren) so they sit one
		// layer above the timeline's own decorations. Every bar is clamped to the visible band,
		// so a partially scrolled row cannot spill over the panel edges.
		int viewTop = lanesTopY();
		int viewBottom = ty + TIMELINE_H;
		for (int li = 0; li < lanes.size(); li++) {
			int y = laneTopOf(li);
			if (y > viewBottom || y + laneTotalH(li) < viewTop) {
				continue; // this lane (and its children) is entirely outside the visible band
			}
			List<CompositeClip> lane = lanes.get(li);
			int blockY0 = Math.max(viewTop, y);
			int blockY1 = Math.min(viewBottom, y + BLOCK_H);
			boolean laneRowVisible = blockY1 > blockY0;
			for (CompositeClip c : lane) {
				int raw0 = btX0(c);
				int wPx = (int) (Math.max(16, c.durationMs()) * pxPerMs());
				// clip into the visible timeline region (no negative-axis overflow)
				int x0 = Math.max(tx, raw0);
				int x1 = Math.min(tx + tw, raw0 + wPx);
				if (x1 <= x0 || !laneRowVisible) {
					continue;
				}
				boolean sel = c == selectedClip && selectedChild < 0;
				int col = sel ? GuiTheme.ACCENT_DARK : GuiTheme.PANEL_HOVER;
				g.fill(x0, blockY0, x1, blockY1, col);
				// slim left/right grab handles: thin vertical lines, same narrow width as the ruler ticks
				g.fill(x0, blockY0, x0 + 1, blockY1, GuiTheme.DE_GREEN);
				g.fill(x1 - 1, blockY0, x1, blockY1, GuiTheme.DE_GREEN);
				if (y >= viewTop) {
					int labelX = x0 + 3;
					if (isGroup(c) && x1 - x0 > GROUP_TOGGLE_X + GROUP_TOGGLE_W + 4) {
						// bare -/+ glyph on the block (no background box): click it to fold/unfold
						boolean open = isGroupExpanded(c);
						int tx0 = x0 + GROUP_TOGGLE_X;
						g.drawString(this.font, open ? "-" : "+", tx0 + 3, y + 3, GuiTheme.DE_GREEN);
						labelX = tx0 + GROUP_TOGGLE_W + 3;
					}
					g.drawString(this.font, font.plainSubstrByWidth(c.displayName(), Math.max(12, x1 - labelX) - 2),
						labelX, y + 2, GuiTheme.TEXT);
				}
			}
			// separator line between rows
			int sepY = y + BLOCK_H;
			if (sepY >= viewTop && sepY < viewBottom) {
				g.fill(tx, sepY, tx + tw, sepY + 1, 0xFF39444F);
			}
		}

		// up/down affordance while more rows exist above/below the visible area
		int maxLaneScroll = Math.max(0, lanesContentH() - lanesViewH());
		if (maxLaneScroll > 0) {
			if (laneScrollPx > 0) {
				g.drawString(this.font, "▲", tlX() + tlW() - 16, ty - 12, GuiTheme.SUBTEXT);
			}
			if (laneScrollPx < maxLaneScroll) {
				g.drawString(this.font, "▼", tlX() + tlW() - 16, ty + TIMELINE_H - 16, GuiTheme.SUBTEXT);
			}
		}

		// text-object track pinned at the TOP of the timeline, spanning the timeline length
		int tty = ty + LANE_TOP;
		int ttx0 = Math.max(tx, tlXFor(0f));
		int ttx1 = Math.min(tx + tw, tlXFor(timelineLen));
		if (ttx1 > ttx0) {
			boolean selected = selectedClip == null; // the text object is "selected" when no clip is
			g.fill(ttx0, tty, ttx1, tty + 12, selected ? 0xFF3A4351 : 0xFF343434);
			g.drawString(this.font, L10n.tr("anima.ui.type.text"), ttx0 + 2, tty + 2, GuiTheme.TEXT);
			for (int i = 0; i < textKeys.size(); i++) {
				int kx = tlXFor(textKeys.get(i).timeMs);
				if (kx < ttx0 || kx > ttx1) {
					continue;
				}
				boolean sel = i == dragKeyIdx;
				drawDiamond(g, kx, tty + 6, sel ? 4 : 3, sel ? 0xFFFFFFFF : 0xFFE6E6E6);
			}
		}

		// play head
		int ph = tlXFor(playTimeMs);
		if (ph >= tx - 4 && ph <= tx + tw + 4) {
			g.fill(ph - 1, ty, ph + 1, ty + TIMELINE_H - 2, GuiTheme.DE_GREEN);
		}

		// right-edge handle: a different colour from the play head so the two never get confused
		int hookX = tx + tw;
		g.fill(hookX - 1, ty, hookX + 1, ty + TIMELINE_H, GuiTheme.WARN);

		// expanded group children go on TOP of the timeline decorations (they must never be
		// covered by the ruler / play head / text track)
		renderGroupChildren(g, tx, tw, viewTop, viewBottom);
	}

	/** Draws the child-particle rows of every expanded group, above the rest of the timeline.
	 *  Each child bar starts at the GROUP BLOCK's left edge so the list reads as a nested tree. */
	private void renderGroupChildren(GuiGraphics g, int tx, int tw, int viewTop, int viewBottom) {
		for (int li = 0; li < lanes.size(); li++) {
			int y = laneTopOf(li);
			if (y > viewBottom || y + laneTotalH(li) < viewTop) {
				continue;
			}
			int childY = y + LANE_H;
			for (CompositeClip c : lanes.get(li)) {
				if (!isGroupExpanded(c)) {
					continue;
				}
				int n = Math.max(1, groupItems(c).size());
				for (int k = 0; k < n; k++) {
					int cy = childY + k * CHILD_H;
					int ry0 = Math.max(viewTop, cy);
					int ry1 = Math.min(viewBottom, cy + CHILD_H - 1);
					int[] bx = childBarX(c, k);
					int cx0 = bx[0];
					int cx1 = bx[1];
					if (cx1 > cx0 && ry1 > ry0) {
						boolean selChild = c == selectedClip && selectedChild == k;
						g.fill(cx0, ry0, cx1, ry1, selChild ? GuiTheme.ACCENT_DARK : 0xFF303841);
						// both grips, exactly like a normal clip block (drag them to move / resize)
						g.fill(cx0, ry0, cx0 + 1, ry1, GuiTheme.DE_GREEN);
						g.fill(cx1 - 1, ry0, cx1, ry1, GuiTheme.DE_GREEN);
						if (cy >= viewTop && cy + CHILD_H <= viewBottom) {
							String lbl = (k + 1) + ". " + groupItemId(c, k) + " ×" + groupItemCount(c, k);
							g.drawString(this.font, font.plainSubstrByWidth(lbl, Math.max(12, cx1 - cx0) - 4),
								cx0 + 3, cy, selChild ? 0xFFFFFFFF : GuiTheme.SUBTEXT);
						}
					}
				}
				childY += n * CHILD_H;
			}
		}
	}

	/** Small filled diamond, matching the ◆ keyframe button glyph. */
	private static void drawDiamond(GuiGraphics g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int w = r - Math.abs(dy);
			g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
		}
	}

	/** Smallest "nice" tick interval (100/200/250/500/1000…ms) that keeps ticks readable. */
	private static int niceStep(double visibleMs) {
		int[] steps = {100, 200, 250, 500, 1000, 2000, 5000, 10000, 20000};
		for (int s : steps) {
			if (s >= visibleMs / 18) {
				return s;
			}
		}
		return 20000;
	}

	/** Timeline tick label in seconds with a decimal (e.g. 0.5s, 1.0s), as requested. */
	private static String tickLabel(int ms) {
		return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
	}

	/** Fade anchors: fade_in at clip start, fade_out at clip end, both with a fixed short window. */
	private IAnimationEffect effectFor(String key) {
		final float fadeMs = 350f;
		if ("fade_in".equals(key)) {
			return (t, d, r, b) -> {
				float localMs = t * 1000f;
				b.alpha(clamp01(localMs / fadeMs));
			};
		}
		if ("fade_out".equals(key)) {
			return (t, d, r, b) -> {
				float durMs = d * 1000f;
				float localMs = t * 1000f;
				float end = Math.max(1f, durMs - fadeMs);
				b.alpha(clamp01(1f - Math.max(0f, localMs - end) / fadeMs));
			};
		}
		if ("typewriter_in".equals(key) || "typewriter_out".equals(key)) {
			// typed characters are driven by the preview text (see renderCenter), not by alpha
			return (t, d, r, b) -> { };
		}
		return engine.createEffect(key, new com.google.gson.JsonObject());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}