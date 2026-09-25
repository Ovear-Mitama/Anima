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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * 动画效果混合器（预览界面）。左侧面板 = 内置效果（拖拽以添加），
 * 中间 = 叠加了生效效果的示例文本，底部时间线 = 多轨道动画条行
 * （拖拽色块可移动或切换行，拖拽其边缘可调整长度，滚轮滚动各行，
 * 右键 = 复制/删除菜单，点击空白 = 跳转定位，空格 = 播放/暂停）。
 * 右侧 = 数值属性输入框（按住方向键可步进），未选中动画条时隐藏。
 */
public class CompositeEditScreen extends Screen {
	private static final int TOP_H = 10;
	/** 效果/粒子 窗口的最小宽度 —— 实际宽度会自适应其最长的一行。 */
	private static final int LIST_W_MIN = 72;
	private static final int PROP_W = 112;
	private static final int TIMELINE_H = 60;
	private static final int CTRL_W = 52;
	private static final int SAMPLE_W = 150;

	private record EffectPreset(String key, String name, int defaultDurationMs, String category) {
	}

	/** 已保存的粒子组 JSON，作为独立的面板行显示（像效果一样拖拽进来）。 */
	private record PaletteGroup(String name, JsonObject json) {
	}

	/** 已保存粒子组 JSON 的面板分类（按需解析 —— 见 {@link #propName}）。 */
	private static String savedGroupCat() {
		return L10n.tr("anima.ui.picker.saved_groups");
	}

	/** 效果面板。每次访问时重新构建，以便其标题跟随语言切换。 */
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
	 * 多轨道时间线：每个元素就是一个轨道（一行）。一个轨道可以容纳多个
	 * 动画条，只要它们在时间上不重叠；lanes[i] 按 startMs 保持有序。
	 * 列表中可以包含空轨道（它们会显示为空行）。
	 */
	private final List<List<CompositeClip>> lanes = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private final Set<String> collapsedCats = new HashSet<>(); // 面板中隐藏的分类

	/** 由外部调用方传入的动画条（只加载一次，见 {@link #loadInitialClips}）、
	 *  随其一并传入的文本对象属性，以及编辑器关闭时同时接收这两者的回调
	 *  （null = 不返回任何内容）。 */
	private final JsonArray initialClips;
	private final JsonObject initialTextProps;
	/** 调用方传入的预览示例文本（null/空 = 使用库自带的）。 */
	private final String previewTextOverride;
	/** 本界面内建的预览渲染器（捕获上面的示例文本）。 */
	private final PreviewRenderer builtinPreview;
	private final Consumer<ClipEditorResult> onSave;
	private boolean initialClipsLoaded;
	private boolean initialTextPropsLoaded;
	private boolean clipsHandedBack;

	/** 可见的面板行：分组标题始终显示，条目仅在其分类展开时显示。 */
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
		// 已保存的粒子组 JSON 作为独立分类出现，可直接拖到时间线上
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
	private int timelineLen = 3000; // 时间线总长度（毫秒）= 文本时长
	/** 文本时长 / 时间线长度的绝对边界（毫秒）。最小值为 0.01 秒。 */
	private static final int MIN_TIMELINE_LEN = 10;
	private static final int MAX_TIMELINE_LEN = 30000;
	/** 项目仍为空时（无关键帧且无手动值）使用的长度。 */
	private static final int DEFAULT_TIMELINE_LEN = 3000;
	/** 在属性面板中手动设置的文本时长（0 = 完全由内容驱动）。 */
	private int timelineLenOverride;
	/** 距离缩放: true = 真实透视（近处文字更大，3D 默认值）；
	 *  false = 无论距离多远，文字都保持恒定的视觉大小。 */
	private boolean textDistanceScale = false;
	/** 显示坐标系：世界里的三轴 gizmo 是否可见（默认关，需要拖轴时再打开）。 */
	private boolean showGizmo = false;

	private double zoom = 1.0;            // 时间线缩放
	private float viewOffsetMs;           // 时间线水平平移（左边缘对应的世界毫秒）
	private int listScroll;
	private int laneScrollPx;             // 轨道行的垂直滚动，单位为像素（行高可变）

	private String paletteDrag;
	private CompositeClip movingClip;     // 当前正在水平拖动 / 跨轨道拖动的动画条
	private float moveGrabMs;
	private CompositeClip resizingClip;
	private boolean resizeRight;
	/** 在展开的分组内拖动子行手柄（其自身的时间窗口，被限制在分组内）。 */
	private CompositeClip childResizeClip;
	private int childResizeIdx = -1;
	private boolean childResizeRight;
	/** 按下时间线左侧按钮列：单纯点击执行按钮，拖动则移动窗口。 */
	private boolean ctrlPressed;
	private int ctrlPressIdx = -1;
	private double ctrlPressX, ctrlPressY;
	private boolean ctrlDragged;
	private int ctrlGrabX, ctrlGrabY;
	private boolean panning;
	private float panLastX;
	private boolean seekDrag;   // 在时间线上按住左键拖动定位（视图跟随）
	private CompositeClip ctxClip;        // 右键菜单所指的动画条（null = 已关闭）
	private int ctxX, ctxY;               // 右键菜单打开的位置
	private int ctxChild = -1;            // 右键点击子行时的子粒子下标
	/** 展开的分组动画条中选中的子粒子（-1 = 分组本身）。 */
	private int selectedChild = -1;

	private final Screen parent;
	private final AnimationEngine engine = AnimationEngine.get();
	private final boolean overlay;   // 浮窗模式（面板后方仍可见世界）
	private int ox, oy, pw, ph;      // 基础布局矩形（全屏）；ox/oy 预留

	// 世界空间预览对象（示例文本位于配置世界内的 (0,1,0) 处）
	/** 场景固定的世界原点（坐标系不会移动；对象围绕它移动）。 */
	private static final float ANCHOR_X = 0f, ANCHOR_Y = 1f, ANCHOR_Z = 0f;
	private static CompositeEditScreen activeOverlay; // 当前正在世界内绘制的浮窗编辑器
	private static boolean previewPositioned;         // 观察者已被移动到对象前方
	private static boolean tickHookInstalled;         // 用于发射世界粒子的客户端 tick 钩子
	private boolean worldPreviewActive;               // 位于配置世界内时（当前帧）为 true
	private boolean lookDrag;                          // 在世界内按住左键拖动可旋转视角
	private double lookAccumX, lookAccumY;             // 待处理的视角增量，每帧应用一次
	private boolean viewPanDrag;                       // 在世界内按住右键拖动可平移视角
	/** 我们转发给玩家的移动按键，以及按下它们的 GLFW 键码。 */
	private final java.util.Map<net.minecraft.client.KeyMapping, Integer> heldMoveKeys = new java.util.HashMap<>();

	// ---- 预览对象属性 + 关键帧（位置 / 缩放 / 透明度） ----
	/** 文本对象的关键帧属性数量（0..2 = 位置 xyz，3 = 缩放，4 = 透明度）。 */
	private static final int PROP_COUNT = 5;

	/** 文本对象属性行 {@code i} 的标题（按需解析，因此切换语言
	 *  无需重启游戏即可生效 —— 静态字段会在类加载时将其固定）。 */
	private static String propName(int i) {
		return switch (i) {
			case 0 -> L10n.tr("anima.ui.prop.pos_x");
			case 1 -> L10n.tr("anima.ui.prop.pos_y");
			case 2 -> L10n.tr("anima.ui.prop.pos_z");
			case 3 -> L10n.tr("anima.ui.prop.scale");
			default -> L10n.tr("anima.ui.prop.alpha");
		};
	}

	/** 文本对象属性块中文本时长输入框（时间线长度）所在的行。 */
	private static final int TEXT_DUR_ROW = PROP_COUNT;
	/** 距离缩放开关所在的行。 */
	private static final int DIST_ROW = PROP_COUNT + 1;
	/** 显示坐标系开关（世界空间三轴坐标系）所在的行。 */
	private static final int GIZMO_ROW = PROP_COUNT + 2;
	/** 文本对象属性块的最后一行（用于计算面板内容高度）。 */
	private static final int LAST_PROP_ROW = GIZMO_ROW;
	/** 一个文本对象关键帧：所有属性的快照 —— 所有字段共用同一个关键帧。 */
	private static final class TextKey {
		int timeMs;
		String ease = "linear";
		final float[] v = new float[PROP_COUNT];
	}
	private final List<TextKey> textKeys = new ArrayList<>();
	/** 对象属性是相对于固定原点的偏移量，因此原点永不移动。 */
	private final float[] propDefaults = { 0f, 0f, 0f, 1f, 1f };
	/** 对象世界坐标 = 原点 + 偏移（三个位置属性）。 */
	private float objectX() { return ANCHOR_X + propValue(0, playTimeMs); }
	private float objectY() { return ANCHOR_Y + propValue(1, playTimeMs); }
	private float objectZ() { return ANCHOR_Z + propValue(2, playTimeMs); }
	private int activeProp;                                  // 正在编辑其字段的属性
	private int dragKeyIdx = -1;                             // 文本轨道上正在拖动的关键帧
	private int axisDrag = -1;                               // 正在拖动的世界坐标系轴（0=X，1=Y，2=Z）
	private double axisStartMouseX, axisStartMouseY;
	private float axisStartValue;
	private double axisScreenDx, axisScreenDy;               // 拖动开始时单个轴长度对应的屏幕向量
	private double pollX, pollY;                             // 最近一次轮询到的光标位置（GUI 坐标）
	private boolean pollValid;
	private boolean dragEventFrame;                          // 本帧的拖动已由鼠标事件驱动
	private float axisLenAtDrag = 1f;                        // 拖动开始时该轴的世界长度
	private boolean lengthDrag;                              // 正在拖动时间线右边缘（窗口宽度）
	private int tlWidth = -1;                                // 自定义时间线面板宽度（-1 = 自动填充）
	private double frozenPxPerMs = -1;                        // 调整窗口大小时冻结的比例
	private final List<DecimalField> propFields = new ArrayList<>(); // 五个对象属性输入框
	private NumberField textDurField;                                 // 文本时长（时间线长度）输入框
	private NumberField clipDurField;                                 // 所选动画条的「动画时长」输入框（拖动绿条时实时跟随）
	private boolean updatingFields;                           // 守卫：setValue 不得回写
	/** 为属性面板创建的控件在 {@code children()} 中的下标范围。 */
	private int propWidgetFrom, propWidgetTo;
	private int propScroll;                                   // 属性面板滚动偏移（px）
	private String lastPropSelectionKey;                      // 上次构建属性面板时的选择标识（用于换对象时把滚动归零）
	private boolean particleListOpen;                        // 注册表粒子选择器已展开
	private int particleListScroll;
	private String particleSearch = "";                      // 选择器搜索过滤文本（实时）
	private SearchField searchBox;                           // 选择器搜索输入框（打开时构建）
	private boolean focusSearch;                             // 打开后立即聚焦搜索框
	private List<JsonObject> pickerGroups = new ArrayList<>(); // 选择器中显示的已保存粒子组 JSON
	private String notice = "";                              // 临时状态行（保存组 / 导入）
	private long noticeUntil;
	private boolean addMenuOpen;                             // "+" 菜单：自定义粒子
	private int addMenuY;

	// 各窗口的偏移量（相互独立的浮窗：1=面板，2=属性，3=时间线）
	// 时间线初始略微上移（-12），而不是紧贴底边
	private int palDx, palDy, propDx, propDy, tlDx0;
	private int tlDy0 = -12;
	private int dragWin;             // 正在拖动的窗口（0 = 无）
	private int winGrabX, winGrabY;

	/** 打字机动画条当前已打出的示例文本字符数（-1 = 完整文本）。 */
	private static int typedVisible = -1;

	/** 预览文本：调用方传入时用它的（如 DE 的示例数字），否则用库自己的示例文本。 */
	private String sampleFull() {
		return previewTextOverride != null && !previewTextOverride.isEmpty()
			? previewTextOverride
			: L10n.tr("anima.ui.preview.sample_text");
	}

	/** 截取到打字机动画条可见前缀的示例文本（空闲时为完整文本）。 */
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

	/** 实际使用的预览渲染器：全局覆盖，否则为内建的。 */
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

	/** @param overlay 浮窗模式：半透明面板，使后方世界保持可见 */
	public CompositeEditScreen(Screen parent, boolean overlay) {
		this(parent, overlay, null, null, null, null);
	}

	/**
	 * @param overlay           浮窗模式：半透明面板，使后方世界保持可见
	 * @param initialClips      要加载到时间线的动画条（例如另一个 mod 传入的伤害数字
	 *                          动画条）；{@code null} = 从空时间线开始
	 * @param initialTextProps  要加载的文本对象属性（见 {@link ClipEditorResult#textProps()}）；
	 *                          {@code null} = 使用默认值
	 * @param previewText       预览中绘制的示例文本（例如调用方自己的数字）；
	 *                          {@code null}/空 = 使用库自带的示例文本
	 * @param onSave            编辑器关闭时接收编辑后的动画条 + 文本属性；
	 *                          {@code null} = 不返回任何内容。编辑器自身没有保存按钮，
	 *                          持久化由调用方负责（例如该 mod 的配置界面）。
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
				return; // 不可见（例如淡入的起始时刻）—— 隐藏文本
			}
			String text = sampleText();
			int alpha = Math.round(clamp01(m.a) * 255f);
			int r = Math.round(clamp01(m.r) * 255f);
			int gg = Math.round(clamp01(m.g) * 255f);
			int bb = Math.round(clamp01(m.b) * 255f);
			int color = (alpha << 24) | (r << 16) | (gg << 8) | bb;
			int px = x + (w - font.width(text)) / 2 + Math.round(m.tx);
			int py = y + (h - 9) / 2 + Math.round(m.ty);
			g.text(font, text, px, py, color);
		};
	}

	public static Screen configScreenFactory(Screen parent) {
		return new CompositeEditScreen(parent);
	}

	@Override
	protected void init() {
		clearWidgets();
		// 将观察者一次性放到预览对象前方并面朝它 —— 仅在专用的
		// 配置世界内（绝不在其他世界劫持玩家）
		if (overlay && ConfigWorldLauncher.isConfigWorld() && !previewPositioned) {
			net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				previewPositioned = true;
				player.setPos(ANCHOR_X, ANCHOR_Y + 1.6, ANCHOR_Z + 6.0);
				player.setYRot(180.0f); // 面朝 -Z → 朝向原点
				player.setXRot(15.0f); // 略微向下看，使对象位于屏幕中心附近
			}
		}
		// 安装用于在世界中发射真实 3D 粒子的客户端 tick 钩子
		if (overlay && !tickHookInstalled && PlatformHooks.get() != null) {
			PlatformHooks.get().addClientTickListener(CompositeEditScreen::tickOverlayStatic);
			tickHookInstalled = true;
		}
		// 安装世界渲染阶段的钩子，将预览文本绘制为真实的 3D 文本
		if (overlay && !worldTextHookInstalled && PlatformHooks.get() != null) {
			PlatformHooks.get().addWorldRenderListener(CompositeEditScreen::renderWorldTextStatic);
			worldTextHookInstalled = true;
		}
		// NeoForge 把原版移动键的上下文设成 IN_GAME（有界面打开时 KeyMapping#isDown()
		// 恒为 false），会让世界里转发的 WASD 失效 —— 编辑器打开期间先放宽，
		// 关闭时在 removed() 里还原
		if (PlatformHooks.get() != null && Minecraft.getInstance().level != null) {
			PlatformHooks.get().allowMovementKeysInGui(true);
		}
		// 覆盖在世界之上的三个可独立拖动的窗口；基础布局为全屏
		pw = this.width;
		ph = this.height;
		ox = 0;
		oy = 0;
		// 时间线不再有长度输入框：其长度跟随文本/动画条内容
		// （见 refreshTimelineLen），窗口通过左侧按钮列拖动。
		loadInitialTextProps();
		loadInitialClips();
		refreshTimelineLen();

		// 时间线的左列就是它的移动手柄（没有标题栏）：三个
		// 按钮由 renderControlButtons 绘制并手动处理，因此按住其中一个并
		// 拖动会移动窗口，而单纯点击会执行按钮动作。
		refreshPickerGroups();
		buildParticleSearchBox();
		propWidgetFrom = this.children().size(); // 属性控件的下标范围
		buildPropertyPanel();
		propWidgetTo = this.children().size();
		buildCommandEditor();
		clampPropScroll();
		clampLaneScroll(); // 折叠分组会使行数减少 —— 保持滚动范围有效
		listScroll = Math.max(0, Math.min(Math.max(0, rows().size() - (paletteH() - 4) / 20), listScroll));
		hideScrolledPropWidgets();
	}

	/** 选择器的搜索栏控件，仅在选择器打开时构建。 */
	private void buildParticleSearchBox() {
		if (particleListOpen && selectedClip != null && isParticleSelected()) {
			this.searchBox = new SearchField(this.font, pmListX() + propDx, pmListY() + propDy,
				pmListW(), PICKER_SEARCH_H, L10n.tr("anima.ui.picker.search"),
				s -> { if (!s.equals(particleSearch)) { particleSearch = s; particleListScroll = 0; } });
			if (!particleSearch.isEmpty()) {
				searchBox.setValue(particleSearch); // 在界面重建之间保留过滤文本
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

	/** 隐藏滚动到（有高度上限的）面板之外的属性控件 —— 它们绘制时不带
	 *  面板的 pose，因此这一步可防止它们溢出到时间线上，也避免它们
	 *  被绘制到窗口标题栏之上（内容区从标题栏下方开始）。
	 *  只考虑由 {@link #buildPropertyPanel()} 构建的控件（按下标记录），
	 *  所以粒子选择器 / 指令弹窗的控件永远不会被这一步处理。 */
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
	 * 时间线长度仅跟随 <b>文本时长</b> —— 属性面板中的手动值，
	 * 或最后一个文本关键帧；完全没有内容时回退到
	 * {@link #DEFAULT_TIMELINE_LEN}。因此编辑动画条的时长/位置永远不会改变
	 * 时间线长度（动画条会被限制在时间线之内），出场动画条（淡出 / 打字出场）会
	 * 重新锚定到末尾。
	 */
	private void refreshTimelineLen() {
		int end = timelineLenOverride; // 0 = 未手动设置
		for (TextKey k : textKeys) {
			end = Math.max(end, k.timeMs);
		}
		if (end <= 0) {
			end = DEFAULT_TIMELINE_LEN; // 空项目 → 一个合理的默认标尺
		}
		timelineLen = Math.max(MIN_TIMELINE_LEN, Math.min(MAX_TIMELINE_LEN, end));
		// 保持每个动画条都在时间线之内（动画时长不超过文本时长）+ 重新锚定出场动画条
		for (int i = 0; i < lanes.size(); i++) {
			List<CompositeClip> lane = lanes.get(i);
			for (int j = 0; j < lane.size(); j++) {
				CompositeClip c = lane.get(j);
				float dur = Math.max(16f, c.durationMs());
				float start = c.startMs();
				if (isExit(c)) {
					start = Math.max(0f, timelineLen - dur); // 吸附到末尾
				} else if (dur > timelineLen) {
					dur = timelineLen;                       // 绝不超过文本时长
					start = 0f;
				} else {
					start = Math.max(0f, Math.min(start, timelineLen - dur));
				}
				if (Math.abs(start - c.startMs()) > 0.5f || Math.abs(dur - c.durationMs()) > 0.5f) {
					final CompositeClip moved = c.withStart(start).withDuration(dur);
					lane.set(j, moved);
					int[] done = emittedByClip.remove(c); // 让粒子计数器跟随动画条
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
			viewOffsetMs = Math.max(0f, timelineLen); // 缩短文本时长不应使视图变为空白
		}
	}

	// ------------------------------------------------------------------ 几何计算（含缩放/平移）

	/** 面板顶部 —— 与属性面板的标题栏处于同一行，使两个顶角
	 *  窗口（左上方面板、右上方属性）完全对齐。 */
	private int paletteY0() { return TOP_H + 2 + HANDLE_H; }

	/** 面板允许的最大高度（内容更长时用滚轮滚动）。 */
	private int paletteMaxH() { return Math.max(60, ph - TIMELINE_H - 30 - TOP_H - 16); }

	/** 面板高度：适应内容，并设上限以免遮住时间线。 */
	private int paletteH() { return Math.min(paletteMaxH(), Math.max(60, rows().size() * 20 + 8)); }

	/** 每个效果预设的面板项目符号 —— 所有预设统一使用同一个标记（与淡出相同）。 */
	private static final String PRESET_MARK = "◆ ";

	/** 面板宽度：按最长可见行所需宽度（上限为窗口宽度的三分之一）。 */
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
	/** 尽管面板本身现在会适应其行高，预览画布仍保持完整高度。 */
	private int centerH() { return paletteMaxH(); }
	private int timelineY() { return ph - TIMELINE_H + 2; }
	private int propX() { return pw - PROP_W - 4; }
	private int tlX() { return CTRL_W + 4; }

	/** 保留的右侧空白，使时间线（及其右边缘手柄）永不接触屏幕边缘。 */
	private static final int TL_RIGHT_MARGIN = 18;

	private int tlW() {
		int max = Math.max(120, pw - tlX() - TL_RIGHT_MARGIN);
		return tlWidth > 0 ? Math.min(tlWidth, max) : Math.max(pw - CTRL_W - 10 - 12, 40);
	}

	/** 每毫秒像素数：默认时间线填满窗口；一旦用户手动调整窗口大小，
	 *  比例即被冻结，使其中的动画条保持原有大小而不缩小。 */
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

	private static final int LANE_TOP = 6;   // 标尺与第一条轨道之间的间距
	private static final int LANE_H = 15;    // 单条轨道的行高（动画条高度 + 1px 分隔线）
	private static final int ROW_H = 22;      // 属性行间距（紧凑、输入框较短）
	private static final int FIELD_H = 14;    // 输入框高度
	private static final int HANDLE_H = 12;   // 窗口标题栏高度
	private static final int BLOCK_H = 14;   // 轨道内单个动画条色块的可见高度
	private static final int TEXT_ROW_H = 14; // 固定在顶部文本对象轨道的高度
	private static final int CHILD_H = 11;   // 展开分组下单个子粒子行的高度

	/** 第一条轨道的 Y 坐标（滚动前）。 */
	private int lanesTopY() { return timelineY() + LANE_TOP + TEXT_ROW_H; }

	/** 轨道区域可用的垂直空间。 */
	private int lanesViewH() { return Math.max(LANE_H, TIMELINE_H - LANE_TOP - TEXT_ROW_H); }

	/** 粒子团动画条时为 true。 */
	private static boolean isGroup(CompositeClip c) {
		return c != null && "particle_group".equals(c.effect());
	}

	/** 当分组动画条在时间线上处于展开状态时为 true（其子项会有自己的行）。 */
	private static boolean isGroupExpanded(CompositeClip c) {
		if (!isGroup(c)) {
			return false;
		}
		com.google.gson.JsonElement e = clipParams(c).get("expanded");
		return e != null && e.getAsBoolean();
	}

	/** 切换分组的展开状态（存储在动画条的 params 中，以便编辑后仍保留）。 */
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

	/** 该轨道因展开的分组子项而增加的像素高度。 */
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

	/** 单条轨道行的总像素高度（动画条行 + 展开的子项）。 */
	private int laneTotalH(int laneIdx) {
		return LANE_H + laneExtraH(laneIdx);
	}

	/** 所有轨道行的像素高度（内容高度，用于限制滚动范围）。 */
	private int lanesContentH() {
		int h = 0;
		for (int i = 0; i < lanes.size(); i++) {
			h += laneTotalH(i);
		}
		return h;
	}

	/** 轨道行顶部的屏幕 Y 坐标（已滚动）。 */
	private int laneTopOf(int laneIdx) {
		int y = lanesTopY() - laneScrollPx;
		for (int i = 0; i < laneIdx && i < lanes.size(); i++) {
			y += laneTotalH(i);
		}
		return y;
	}

	/** 其行（动画条行或其子行）包含 {@code y} 的轨道下标；
	 *  当该点位于所有轨道下方时返回 {@code lanes.size()}（用于放置时新增轨道）。 */
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

	/** 限制垂直滚动范围，使底部轨道始终可达。 */
	private void clampLaneScroll() {
		int max = Math.max(0, lanesContentH() - lanesViewH());
		laneScrollPx = Math.max(0, Math.min(max, laneScrollPx));
	}

	/** 绘制在分组时间线色块左侧的 -/+ 展开切换按钮的宽度。 */
	private static final int GROUP_TOGGLE_W = 11;
	/** 切换按钮位于 4px 左侧调整手柄之后，使分组上的手柄仍可使用。 */
	private static final int GROUP_TOGGLE_X = 5;

	/** 当该点位于分组动画条的 -/+ 切换按钮上时为 true（时间窗局部坐标）。 */
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
			return false; // 太窄，无法显示切换按钮
		}
		int y0 = laneTopOf(lane);
		return x >= x0 + GROUP_TOGGLE_X && x <= x0 + GROUP_TOGGLE_X + GROUP_TOGGLE_W
			&& y >= y0 && y <= y0 + BLOCK_H;
	}

	/** 子粒子行的命中结果：属于哪个已展开分组、哪个条目、以及哪一部分。
	 *  {@code edge}：-1 = 主体，0 = 左手柄（移动子项），1 = 右手柄（其长度）。 */
	private record GroupChildHit(CompositeClip group, int index, int edge) {
	}

	/** 子项条形的矩形（时间窗局部坐标，{@code [0]} = x0，{@code [1]} = x1）。 */
	private int[] childBarX(CompositeClip group, int idx) {
		float gx = group.startMs();
		float dur = Math.max(16f, group.durationMs());
		float s = (float) Math.min(groupItemStart(group, idx), Math.max(0f, dur - 16f));
		float d = (float) Math.min(groupItemDur(group, idx), Math.max(16f, dur - s));
		int x0 = tlXFor(gx + s);
		int x1 = tlXFor(gx + s + d);
		return new int[] { Math.max(tlX(), x0), Math.min(tlX() + tlW(), x1) };
	}

	/** 命中测试：绘制在轨道中已展开分组动画条下方的子行，若无则 {@code null}。 */
	private GroupChildHit hitGroupChild(int lane, int x, int y) {
		if (lane < 0 || lane >= lanes.size()) {
			return null;
		}
		int childTop = laneTopOf(lane) + LANE_H;
		if (y < childTop) {
			return null; // 仍在轨道自身的动画条行内
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

	/** 所有轨道上的全部动画条（按轨道顺序）。 */
	private List<CompositeClip> allClips() {
		List<CompositeClip> all = new ArrayList<>();
		for (List<CompositeClip> lane : lanes) {
			all.addAll(lane);
		}
		return all;
	}

	/** 加载外部提供的动画条（仅一次）—— 每个放入各自独立的轨道。 */
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
			// 还原 dumpClips 时存下的粒子 id 与参数（没有则为默认）
			String particle = o.has("particle") ? o.get("particle").getAsString() : "";
			String params = o.has("params") ? o.get("params").getAsString() : "{}";
			// 自定义名称（dumpClips 里存为 name）；没有就用预设名
			String name = o.has("name") && !o.get("name").getAsString().isBlank()
				? o.get("name").getAsString() : paletteName(effect);
			List<CompositeClip> lane = new ArrayList<>();
			lane.add(new CompositeClip(effect, name, particle, speed, dur, start, params));
			lanes.add(lane);
		}
	}

	/** 文本对象属性的字段顺序：位置 X/Y/Z、缩放、透明度（与属性面板的行一致）。 */
	private static final String[] TEXT_PROP_KEYS = { "x", "y", "z", "scale", "alpha" };

	/** 应用调用方传入的文本对象属性（见 {@link #dumpTextProps}）。 */
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

	/** 序列化文本对象属性（位置 / 缩放 / 透明度 / 时长 / 距离缩放 + 共享关键帧）。 */
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

	/** 为外部调用方序列化时间线上的动画条（见 {@link #onSave}）。 */
	private JsonArray dumpClips() {
		JsonArray arr = new JsonArray();
		for (CompositeClip c : allClips()) {
			JsonObject o = new JsonObject();
			o.addProperty("effect", c.effect());
			o.addProperty("start", c.startMs());
			o.addProperty("duration", c.durationMs());
			o.addProperty("speed", c.speed());
			// 自定义名称必须一起存 —— 否则重新打开编辑器时名字会被重置回预设名
			if (c.displayName() != null && !c.displayName().isBlank()) {
				o.addProperty("name", c.displayName());
			}
			// 粒子 id 与参数（粒子类型、组内容、偏移等）必须一起存，否则重载时粒子会
			// 回退到默认值（minecraft:flame）——「保存退出后粒子又变成火」就是这样来的。
			if (c.particle() != null && !c.particle().isBlank()) {
				o.addProperty("particle", c.particle());
			}
			if (c.params() != null && !c.params().isBlank() && !"{}".equals(c.params())) {
				o.addProperty("params", c.params());
			}
			arr.add(o);
		}
		return arr;
	}

	/** 面板条目的显示名称（回退为原始 key）。 */
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

	/** 在其轨道内用 {@code updated} 替换 {@code old}（按引用匹配）。 */
	private void replaceClip(CompositeClip old, CompositeClip updated) {
		for (int i = 0; i < lanes.size(); i++) {
			List<CompositeClip> lane = lanes.get(i);
			for (int j = 0; j < lane.size(); j++) {
				if (lane.get(j) == old) {
					lane.set(j, updated);
					sortLane(i);
					// 编辑会替换动画条实例：需把粒子发射计数一并带过去，
					// 否则新实例会认为自身尚未发射过而重复发射
					// （这就是编辑 1 个粒子的动画条时它“一直在召唤”的原因）
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

	/** 在 {@code lane} 内为长度为 {@code dur} 的动画条寻找不与其他动画条重叠的
	 * 最近合法起点。当所有空闲窗口都太小时回退为 {@code want}（保持不变），
	 * 留下临时重叠，由后续编辑解决。
	 */
	private float snapStart(List<CompositeClip> lane, CompositeClip self, float want, float dur) {
		float lo = 0f, hi = Math.max(0f, timelineLen - dur);
		float best = -1f;
		float bestDist = Float.MAX_VALUE;
		CompositeClip probe = new CompositeClip("", "", "", 1f, dur, want);
		if (!overlapsIn(lane, self, probe)) {
			return Math.max(lo, Math.min(hi, want));
		}
		// 候选空隙：从 0 开始以及紧跟在每个其他动画条之后
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

	/** {@code lane} 中色块恰好起始于 {@code endMs} 之后的动画条（可能为 null）。 */
	private static float nextStartAfter(List<CompositeClip> lane, float endMs) {
		float best = Float.MAX_VALUE;
		for (CompositeClip o : lane) {
			if (o.startMs() > endMs + 0.5f && o.startMs() < best) {
				best = o.startMs();
			}
		}
		return best;
	}

	/** 向轨道添加一个新动画条，当目标位置被占用时在其下方自动创建轨道
	 * （锚定在边缘的淡入淡出因此会落到各自空闲的轨道上）。
	 * 返回实际存储的实例（其起点可能已吸附到空闲位置）。
	 */
	private CompositeClip addToLanes(CompositeClip clip, int preferredLane) {
		// 寻找第一个时间窗口空闲的轨道，从偏好行附近开始并向顶部
		// 回绕；只有当所有现有轨道都被占用时才新增一条轨道。
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
			lanes.add(new ArrayList<>()); // 仅在确实需要时新增（不保留空的占位行）
			best = lanes.size() - 1;
		}
		List<CompositeClip> list = lanes.get(best);
		float start = snapStart(list, null, clip.startMs(), dur);
		CompositeClip placed = clip.withStart(start);
		list.add(placed);
		sortLane(best);
		return placed;
	}

	/** 移除不再包含任何动画条的轨道（行是自动的：需要则新增，不用则移除）。 */
	private void removeEmptyLanes() {
		lanes.removeIf(List::isEmpty);
		clampLaneScroll();
	}

	/** {@code lane} 中可见色块包含 x 的动画条（最上层/命中项），否则为 null。 */
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

	// ------------------------------------------------------------------ 属性面板（数值输入框）

	private boolean isParticleSelected() {
		return selectedClip != null && selectedClip.effect().startsWith("particle_");
	}

	private boolean isParticle3D() {
		return selectedClip != null && "particle_3d".equals(selectedClip.effect());
	}

	private boolean isParticleGroup() {
		return selectedClip != null && "particle_group".equals(selectedClip.effect());
	}

	// 当前选中动画条各属性行的垂直布局（Y）（26px 间距；
	// 标题绘制在每个输入框上方 11px 处，因此不再紧贴输入框）
	/** 名称行（仅在选中动画条时）—— 动画条可编辑的时间线名称。 */
	private int nameY() { return TOP_H + 32 - propScroll; }
	/** 速度行：存在名称行时下移一个行距（选中的分组子项没有
	 *  名称/速度行 —— 此时面板改为显示该子项自身的属性）。 */
	private int speedY() { return nameY() + (selectedClip != null && !hasGroupChild() ? ROW_H : 0); }
	private int particleY() { return hasGroupChild() ? nameY() : speedY() + ROW_H; }
	/** 粒子参数字段的行 Y 坐标（0 = 粒子/注册表行下方的第一行）。 */
	private int p3dParamY(int k) { return particleY() + ROW_H + k * ROW_H; }
	/** 入场/出场动画条没有速度行 —— 只有动画时长。 */
	private boolean hideSpeed() { return selectedClip != null && isPinned(selectedClip); }
	/** 选中的是所选分组中的某个子粒子时为 true（显示其自身的行）。 */
	private boolean hasGroupChild() {
		return isParticleGroup() && selectedChild >= 0 && selectedChild < groupCount();
	}

	// ---- 行下标 -----------------------------------------------------------------------
	// 分组（内部未选中任何项）：添加粒子 / 导入指令 / 保存组 JSON
	// 分组子项（在时间线上选中）：粒子 / 数量 / 散布X/Y/Z / 速度 / 起始 / 时长 / 移除
	private static final int GROUP_ROWS = 3;
	private static final int CHILD_REMOVE_ROW = 7; // 子粒子(0行) + 数量…速度(5) + 起始 + 时长
	/** 单个粒子的发射参数行：数量 / 散布X / 散布Y / 散布Z / 速度 / 导入指令。 */
	private static final int SINGLE_EMIT_ROWS = 6;

	/** 所选分组子项的起始/时长行（其在分组内的自身窗口）。 */
	private int childStartY() { return p3dParamY(5); }
	private int childDurY() { return p3dParamY(6); }

	private int durY() {
		if (hasGroupChild()) {
			// 子项模式没有 动画时长 行：最后一行是它自己的 移除该粒子，因此对象
			// 属性块（子项不使用）紧接其下方开始
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
	/** 移除该条 位于最底部，紧接在所示属性行的下方。 */
	private int removeY() { return propRowBaseY() + propShownRows() * ROW_H; }
	/** 子项自身的 移除该粒子 按钮（仅子项模式）。 */
	private int childRemoveY() { return p3dParamY(CHILD_REMOVE_ROW); }

	/** 客户端所有可用的粒子注册表 id（原版 + 其他 mod），已排序。 */
	private static List<String> registryIds;
	private static List<String> registryParticleIds() {
		if (registryIds == null) {
			List<String> ids = new ArrayList<>();
			for (Identifier rl : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
				ids.add(rl.toString());
			}
			ids.sort(Comparator.naturalOrder());
			registryIds = ids;
		}
		return registryIds;
	}

	private void buildPropertyPanel() {
		int wx = propX() + PROP_INSET + propDx; // 控件 x 坐标（已并入窗口偏移；控件在 pose 之外绘制）
		// 右边缘 = propX()+PROP_INSET+pw，面板结束于 propX()+PROP_W-6 → 保留 2px 间隙
		// 使按钮/输入框永远不会超出面板
		int pw = PROP_W - PROP_INSET - 8;
		int wy = propDy;
		clipDurField = null; // 下面按需重建；旧控件可能已被 clearWidgets 移除
		// 换了对象就把属性面板的滚动拉回顶部：否则新面板会带着上一个对象的滚动偏移，
		// 顶部几行被推出可视区，看起来像排布乱了
		String selKey = propSelectionKey();
		if (!selKey.equals(lastPropSelectionKey)) {
			lastPropSelectionKey = selKey;
			propScroll = 0;
		}
		if (selectedClip != null && !hasGroupChild()) {
			// 可编辑的时间线名称（显示在动画条色块上的标签）。只有真正的修改才会
			// 被应用：init() 会重新填充此输入框，而程序化的 setValue 不得替换
			// 动画条实例（这曾破坏进行中的时间线拖动）。
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
			// 此单个粒子的原版 /particle 发射参数：数量 + delta（散布）+ 速度
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
				// 在时间线上选中了子项：面板编辑的是该子项，而不是分组
				// （分组自身的行为改为点击分组色块来编辑）。
				final int idx = selectedChild;
				// 子项的粒子注册表 id 可像动画条名称一样编辑（⌨ 输入原版
				// / mod id），而小 ▾ 仍会打开注册表选择器
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
				// 其在分组内的自身窗口（绝不会比分组本身更长）
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
				// 分组的粒子位于时间线上（分组展开时它们会拥有自己的子行）；
				// 此面板只对它们做整体管理。展开通过分组色块上的
				// -/+ 完成 —— 这里没有对应的按钮。
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(0), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.particle.add")), b -> {
					selectedChild = -1;
					openParticlePicker();
				}));
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(1), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.button.import_command_short")), b -> importParticleCommand()));
				// 将分组保存为可分享的 JSON（随后会出现在选择器的已保存分组列表中）
				addRenderableWidget(ThemeButton.of(wx, wy + p3dParamY(2), pw, FIELD_H, Component.literal(L10n.tr("anima.ui.particle.save_group")), b -> {
					if (selectedClip != null) {
						saveGroupJson(selectedClip);
					}
				}));
			}
		}
		if (!hasGroupChild()) {
			// 动画时长：拖动时间线两端的绿条时由 refreshPropFields() 实时回填，
			// 因此程序化 setValue 不得再回写（否则每帧都会替换动画条实例）
			clipDurField = new NumberField(this.font, wx, wy + durY(), pw, FIELD_H, 16, Math.max(16, timelineLen), 50, L10n.tr("anima.ui.prop.duration"), Math.round(selectedClip.durationMs()),
				v -> { if (!updatingFields && selectedClip != null) setClip(selectedClip, selectedClip.withDuration(v)); });
			addRenderableWidget(clipDurField);
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

	/** 子项的 起始 必须为其在分组内的当前长度留出空间。 */
	private double clampChildStart(int idx, double v) {
		if (selectedClip == null) {
			return v;
		}
		double gDur = Math.max(16, selectedClip.durationMs());
		double d = Math.min(groupItemDur(selectedClip, idx), gDur);
		return Math.max(0, Math.min(Math.max(0, gDur - d), v));
	}

	/** 子项的 时长 永远不能超出分组末尾。 */
	private double clampChildDur(int idx, double v) {
		if (selectedClip == null) {
			return v;
		}
		double gDur = Math.max(16, selectedClip.durationMs());
		double s = groupItemStart(selectedClip, idx);
		return Math.max(16, Math.min(Math.max(16, gDur - s), v));
	}


	/** 在其轨道内替换动画条的数据；入场/出场动画条始终粘附在时间线边缘。
	 *  用户拖动期间（名称框、数量……）的字段编辑会替换动画条实例 ——
	 *  拖动指针会跟随它，否则拖动会悄悄停止工作。 */
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

	/** 入场动画条（淡入 / 打字入场 / 各类逐字入场）锚定到时间线起点。 */
	private static boolean isEntry(CompositeClip c) {
		return c != null && ("fade_in".equals(c.effect()) || isCharEntry(c.effect()));
	}

	/** 出场动画条（淡出 / 打字出场）锚定到时间线末尾。 */
	private static boolean isExit(CompositeClip c) {
		return c != null && ("fade_out".equals(c.effect()) || "typewriter_out".equals(c.effect()));
	}

	/** 字符级入场动画时为 true（它们始终从 0 ms 开始）。 */
	private static boolean isCharEntry(String effect) {
		return switch (effect) {
			case "typewriter_in", "char_fall_in", "char_fall_random_in", "char_drift_in" -> true;
			default -> false;
		};
	}

	/** 位置被锁定到时间线边缘的动画条时为 true。 */
	private static boolean isPinned(CompositeClip c) {
		return isEntry(c) || isExit(c);
	}

	/** 入场动画条固定在 0 ms，出场动画条固定在时间线长度处。 */
	private CompositeClip pinAnchor(CompositeClip c) {
		if (isEntry(c)) {
			return c.withStart(0f);
		}
		if (isExit(c)) {
			return c.withStart(Math.max(0f, timelineLen - Math.max(16, c.durationMs())));
		}
		return c;
	}

	/** 动画条 JSON params 中某个键的整数值（缺失或无法解析时用默认值）。 */
	private static int p3dParam(CompositeClip c, String key, int dflt) {
		return (int) Math.round(param(c, key, dflt));
	}

	/** 动画条 JSON params 中某个键的数值（缺失或无法解析时用默认值）。 */
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

	/** 动画条自身粒子某个发射键的数值（单粒子动画条）。 */
	private static double emitParam(CompositeClip c, String key, double dflt) {
		if ("count".equals(key)) {
			return p3dParam(c, "count", (int) dflt);
		}
		return param(c, key, dflt);
	}

	/** 分组子条目某个发射键的数值。 */
	private static double groupItemParam(CompositeClip c, int i, String key, double dflt) {
		com.google.gson.JsonArray a = groupItems(c);
		if (i < 0 || i >= a.size() || !a.get(i).isJsonObject()) {
			return dflt;
		}
		com.google.gson.JsonElement e = a.get(i).getAsJsonObject().get(key);
		return e == null ? dflt : e.getAsDouble();
	}

	/** 单个子粒子在其分组时间范围内的延迟（毫秒）。 */
	private static double groupItemStart(CompositeClip c, int i) {
		return Math.max(0, groupItemParam(c, i, "s", 0));
	}

	/** 单个子粒子的长度（毫秒）—— 默认为整个分组长度。 */
	private static double groupItemDur(CompositeClip c, int i) {
		double d = groupItemParam(c, i, "d", 0);
		return d <= 0 ? Math.max(16, c.durationMs()) : d;
	}

	/** 写入分组子项的某个数值键（供面板和时间线拖动使用）。 */
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

	/** 覆盖分组子条目的某个数值键（不存在时创建）。 */
	private static String withGroupItemNumber(CompositeClip c, int idx, String key, double value) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		if (idx >= 0 && idx < a.size() && a.get(idx).isJsonObject()) {
			a.get(idx).getAsJsonObject().addProperty(key, value);
			o.add("items", a);
		}
		return o.toString();
	}

	/** 重命名分组子项 —— 其粒子注册表 id（直接在面板中输入）。 */
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

	/** 更新所选动画条 JSON params 中的某个键，保留其他键。 */
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

	/** 对单个粒子或所选分组子项应用发射键的编辑。 */
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

	/** 为当前选择显示的发射键数值（单个粒子，或所选分组子项）。 */
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
			removeEmptyLanes(); // 行是自动的 —— 轨道没有动画条后即移除
		}
		if (selectedClip == c) {
			selectedClip = null;
		}
		if (ctxClip == c) {
			ctxClip = null;
		}
	}

	/** 在同一轨道（或下一条空闲轨道）上紧跟在原动画条之后复制一份。淡入淡出会
	 *  重新锚定到其时间线边缘，因此副本绝不会漂移到中间。 */
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

	/** 动画条显示/使用的粒子 id：显式覆盖值，否则为合理的默认值。 */
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

	// ------------------------------------------------------------------ 粒子团

	private static com.google.gson.JsonObject clipParams(CompositeClip c) {
		try {
			return (c.params() != null && !c.params().isBlank())
				? com.google.gson.JsonParser.parseString(c.params()).getAsJsonObject()
				: new com.google.gson.JsonObject();
		} catch (RuntimeException e) {
			return new com.google.gson.JsonObject();
		}
	}

	/** 分组动画条的粒子条目：[{id, count, dx, dy, dz}, ...]。 */
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

	/** 向分组动画条添加一个注册表粒子；返回新的 params JSON。接收到
	 *  第一个粒子的分组会在时间线上展开，使新行立即可见。 */
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

	/** 覆盖某个子条目的注册表粒子（选中子行时使用）。 */
	private static String withGroupItemId(CompositeClip c, int idx, String id) {
		com.google.gson.JsonObject o = clipParams(c);
		com.google.gson.JsonArray a = groupItems(c);
		if (idx >= 0 && idx < a.size() && a.get(idx).isJsonObject()) {
			a.get(idx).getAsJsonObject().addProperty("id", id);
			o.add("items", a);
		}
		return o.toString();
	}

	/** 将原版 {@code /particle …} 指令导入当前选择：
	 *  {@code particle <id> <pos> <deltaX deltaY deltaZ> <speed> <count>}。位置会被忽略
	 *  （由动画条自身的偏移/坐标系来决定发射器的位置）。 */
	private void importParticleCommand() {
		if (selectedClip == null || !isParticleSelected()) {
			return;
		}
		// 剪贴板只是方便的预填充 —— 文本在我们自己的弹窗中编辑
		String seed = Minecraft.getInstance().keyboardHandler.getClipboard();
		cmdEditorOpen = true;
		cmdSeed = seed == null ? "" : seed.trim();
		init();
	}

	/** 将输入的指令转换为当前选择（单个粒子 / 所选子项 / 新子项）。 */
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
		i += 3; // x y z（忽略 —— 由该动画条自身的偏移决定发射器位置）
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

	/** 向分组动画条的 items 数组追加一个条目对象。 */
	private static com.google.gson.JsonArray appendItem(CompositeClip c, com.google.gson.JsonObject item) {
		com.google.gson.JsonArray a = groupItems(c).deepCopy();
		a.add(item);
		return a;
	}

	/** 解析一个指令 token：{@code ~}/{@code ~x} → 0，否则为该数字。 */
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

	/** 将分组动画条的 items 设为给定数组（替换旧列表）。 */
	private static String withGroupItems(CompositeClip c, com.google.gson.JsonArray items) {
		com.google.gson.JsonObject o = clipParams(c);
		o.add("items", items);
		return o.toString();
	}

	/** 将单粒子动画条转换为包含给定条目的分组动画条（展开，
	 *  使子项立即出现在时间线上）。 */
	private static CompositeClip toGroupClip(CompositeClip c, com.google.gson.JsonArray items) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.add("items", items);
		o.addProperty("expanded", true);
		return CompositeClip.of("particle_group", L10n.tr("anima.ui.prop.particle_group"))
			.withDuration(c.durationMs()).withStart(c.startMs()).withParams(o.toString());
	}

	/** 应用选择器中的一行：已保存分组会加载其 items，注册表粒子则被设置 / 追加。 */
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
				// 选中的子行会被重新指向；否则追加该粒子
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

	/** 将所选分组动画条保存为可分享的 JSON（config/anima/
	 *  particle_groups/）并复制到剪贴板；随后它会出现在选择器中。 */
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

	/** 在播放头处从已保存的粒子组 JSON（面板行）添加一个动画条。 */
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

	/** 在播放头处创建一个新的粒子动画条（单个粒子或分组）并选中它。 */
	private void addParticleClip(String effect, String name, String params) {
		int dur = 1000;
		float start = Math.max(0f, Math.min(Math.max(0, timelineLen - dur), playTimeMs));
		CompositeClip clip = CompositeClip.of(effect, name).withDuration(dur).withStart(start);
		if (params != null) {
			clip = clip.withParams(params);
		}
		if ("particle_3d".equals(effect)) {
			clip = clip.withParticle("minecraft:flame"); // 默认注册表粒子
		}
		selectedClip = addToLanes(clip, Math.max(0, laneForY(lanesTopY())));
		clampLaneScroll();
		init();
	}


	// ------------------------------------------------------------------ 右键上下文菜单（竖排下拉）

	private static final int CTX_W = 62;
	private static final int CTX_ROW_H = 13;
	private static final int CTX_PAD_TOP = 2; // 使强调线与第一行的高亮保持分离

	/**
	 * 打开右键菜单并把位置夹在屏幕内（和 Windows 右键一样）：默认贴着光标，靠近右/下边界时
	 * 向左/上翻转，保证每一项都看得见、点得到。
	 *
	 * @param clip  被右键的动画条；null 表示不显示菜单
	 * @param child 被右键的子粒子行下标，-1 表示不是子行
	 * @param x,y   时间窗局部坐标下的右键位置
	 */
	private void openCtxMenu(CompositeClip clip, int child, int x, int y) {
		ctxClip = clip;
		ctxChild = child;
		if (clip == null) {
			return;
		}
		int menuH = ctxMenuH();
		int sx = x + tlDx0; // 时间窗局部 -> 屏幕
		int sy = y + tlDy0;
		int maxX = this.width - CTX_W - 2;
		int maxY = this.height - menuH - 2;
		if (sx > maxX) {
			sx = maxX; // 右侧放不下 -> 整体左移，右边缘贴屏幕
		}
		if (sy > maxY) {
			sy = sy - menuH; // 下侧放不下 -> 向上翻转，底边贴光标
		}
		sx = Math.max(2, sx);
		sy = Math.max(2, sy);
		ctxX = sx - tlDx0;
		ctxY = sy - tlDy0;
	}

	/** 时间窗局部坐标下位于菜单行上的行号，否则 -1（绘制与点击共用）。 */
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

	/** 菜单行：右键子行只提供 删除该粒子；单个粒子还会提供 创建组。 */
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

	/** 执行 {@code row} 对应的上下文菜单动作；随后菜单关闭。 */
	private void ctxAction(int row) {
		if (ctxClip == null || laneOf(ctxClip) < 0) {
			ctxClip = null;
			ctxChild = -1;
			return;
		}
		if (ctxChild >= 0) {
			// 从分组中移除此子粒子（子项在时间线上管理）
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

	/** 将单粒子动画条包装成包含该粒子的粒子团（时间范围相同）。 */
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

	/** 在点击的动画条附近绘制已打开的上下文菜单（时间窗局部坐标）。 */
	private void renderContextMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (ctxClip == null || laneOf(ctxClip) < 0) {
			return;
		}
		int rows = ctxRows();
		int mx = ctxX + tlDx0;
		int my = ctxY + tlDy0;
		// 将原始（屏幕）鼠标位置转换为时间窗局部坐标，使悬停
		// 高亮与点击会执行的行精确对齐
		int hovRow = ctxRowAt((int) (mouseX - tlDx0), (int) (mouseY - tlDy0));
		g.fill(mx, my, mx + CTX_W, my + ctxMenuH(), 0xE6121418);
		g.fill(mx, my, mx + CTX_W, my + 1, GuiTheme.ACCENT);
		for (int i = 0; i < rows; i++) {
			int ry = my + CTX_PAD_TOP + i * CTX_ROW_H;
			if (i == hovRow) {
				g.fill(mx + 1, ry, mx + CTX_W - 1, ry + CTX_ROW_H, GuiTheme.PANEL_HOVER);
			}
			// 紧凑（0.75×）标题，使整个菜单保持小巧
			drawSmallLabel(g, ctxRowLabel(i), mx + 6, ry + (CTX_ROW_H - 6) / 2,
				i == hovRow ? GuiTheme.WARN : GuiTheme.TEXT);
		}
	}

	// ------------------------------------------------------------------ 输入

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();
		// 控件（按钮 / 输入框 / 时间线长度框）总是优先接收点击
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		// 没点到任何控件（点了时间线 / 空白 / 世界）→ 让输入框失焦。
		// 否则焦点还在框里，之后按 WASD、空格、Delete 都会当成在输入，而不是快捷键。
		blurWidgetFocus();
		// /particle 弹窗是模态的：吞掉所有落在它之外的点击
		if (cmdEditorOpen) {
			return true;
		}
		// 窗口拖动手柄（屏幕空间）：拖动窗口的标题栏会移动该窗口
		if (button == 0) {
			for (int region = 1; region <= 2; region++) {
				if (hitWindowHandle(mouseX, mouseY, region)) {
					dragWin = region;
					winGrabX = (int) mouseX - (region == 1 ? palDx : propDx);
					winGrabY = (int) mouseY - (region == 1 ? palDy : propDy);
					return true;
				}
			}
			// 时间线：其左侧按钮列就是移动手柄 —— 记录按下，
			// 在释放时判断是点击（执行按钮）还是拖动（移动窗口）
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
		// 面板窗口空间
		mouseX = rawX - palDx;
		mouseY = rawY - palDy;
		int x = (int) mouseX;
		int y = (int) mouseY;

		// "+" 菜单命中测试（面板局部坐标）：只有 自定义粒子 —— 粒子团现在
		// 在时间线上创建（右键粒子 → 创建组）
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

		// 左侧面板：点击分组标题切换分类，点击效果开始拖动
		if (button == 0 && x >= 6 && x <= listW() && y >= paletteY0() && y <= paletteY0() + paletteH()) {
			int row = (y - paletteY0() - 4) / 20 + listScroll;
			List<Object> rows = rows();
			if (row >= 0 && row < rows.size()) {
				Object o = rows.get(row);
				if (o instanceof EffectPreset p) {
					if ("particle_add".equals(p.key())) {
						// "+" 打开粒子菜单，而不是开始拖动
						addMenuOpen = true;
						addMenuY = Math.min(paletteY0() + paletteH() - 20, y - 2);
						return true;
					}
					paletteDrag = p.key();
					return true;
				} else if (o instanceof PaletteGroup pg) {
					// 已保存的分组 JSON：直接放到时间线上播放头处
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

		// 属性窗口空间（只有真正落在面板上的点击才算数 —— 否则同一高度
		// 屏幕任意位置的点击都会打开粒子列表）
		mouseX = rawX - propDx;
		mouseY = rawY - propDy;
		x = (int) mouseX;
		y = (int) mouseY;
		boolean inPropPanel = x >= propX() - 2 && x <= propRight() && y >= TOP_H + 2 && y <= propPanelBottom();
		if (particleListOpen) {
			if (inParticlePicker(x, y) && y >= pmRowsY()) { // 搜索栏下方 → 选择一行
				int idx = particleListScroll + (y - pmRowsY()) / pmRowH();
				List<PickerEntry> entries = pickerEntries();
				if (idx >= 0 && idx < entries.size() && selectedClip != null) {
					applyParticlePick(entries.get(idx)); // 设置粒子、追加粒子，或加载分组
				}
				return true;
			}
			closeParticlePicker(); // 点击其他任何位置都会关闭列表（及其搜索栏）
		}
		if (button == 0 && inPropPanel && selectedClip != null && !hasGroupChild()
				&& y >= particleY() && y <= particleY() + 18) {
			if (isParticleGroup()) {
				toggleGroupExpanded(selectedClip); // 摘要行折叠时间线上的子行
			} else if (isParticleSelected()) {
				openParticlePicker();
			}
			return true;
		}

		// 时间线窗口空间
		mouseX = rawX - tlDx0;
		mouseY = rawY - tlDy0;
		x = (int) mouseX;
		y = (int) mouseY;
		// 右键下拉菜单：点击某一行执行它，其他点击关闭菜单
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
			if (button == 2) { // 中键 → 平移时间线视图
				panning = true;
				panLastX = x;
				return true;
			}
			int ttyHit = timelineY() + LANE_TOP;
			if (y >= ttyHit && y <= ttyHit + 12) {
				// 文本对象轨道：共享关键帧（一个关键帧包含所有属性）
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
				// 文本轨道上的空白处：选中文本对象（并移动播放头）
				selectedClip = null;
				ctxClip = null;
				playTimeMs = Math.max(0, Math.min(timelineLen, tlTimeForX(x)));
				init();
				return true;
			}
			// 右边缘手柄：拖动它改变时间线窗口宽度（不是缩放）
			int hookX = tlX() + tlW();
			if (Math.abs(x - hookX) <= 4) {
				if (frozenPxPerMs <= 0) {
					// 记录当前比例，使调整窗口大小永不会缩小动画条
					frozenPxPerMs = tlW() / (double) Math.max(1, timelineLen);
				}
				lengthDrag = true;
				return true;
			}
			int lane = laneForY(y);
			// 展开的分组子项在轨道行下方拥有自己的行
			GroupChildHit childHit = hitGroupChild(lane, x, y);
			if (childHit != null) {
				if (button == 1) { // 右键子行 → 删除该粒子
					openCtxMenu(childHit.group(), childHit.index(), x, y);
					init();
					return true;
				}
				ctxChild = -1;
				selectedClip = childHit.group();
				selectedChild = childHit.index();
				ctxClip = null;
				if (childHit.edge() >= 0) {
					// 手柄拖动：子项自身的起点 / 长度（绝不超出分组末尾）
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
			if (button == 1) { // 右键：光标下动画条的上下文菜单（复制/创建组/删除）
				selectedClip = hit;
				selectedChild = -1;
				openCtxMenu(hit, -1, x, y);
				init();
				return true;
			}
			if (hit != null) {
				selectedClip = hit;
				selectedChild = -1;
				ctxClip = null;
				if (button == 0 && inGroupToggle(hit, x, y)) {
					toggleGroupExpanded(hit); // 分组色块上的 -/+ 折叠子行
					return true;
				}
				boolean entry = isEntry(hit);
				boolean exit = isExit(hit);
				int bx0 = btX0(hit);
				int bx1 = bx0 + (int) (Math.max(16, hit.durationMs()) * pxPerMs());
				// 入场动画条：左边缘固定在 0 → 没有左手柄。出场动画条：右边缘固定在
				// 末尾 → 没有右手柄。这些边缘只用于选中动画条。
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
					// 入场/出场动画条无法沿时间线拖动 —— 它们保持锚定
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
			// 轨道空白处：按住左键拖动定位播放头（时间线跟随鼠标）
			ctxClip = null;
			playTimeMs = Math.max(0, Math.min(timelineLen, tlTimeForX(x)));
			seekDrag = true;
			return true;
		}
		// 世界内：按住左键拖动（在所有 UI 之外）旋转视角，按住右键拖动平移视角。
		// 控件和编辑器面板已在上面接收了点击（见本方法开头）。
		if (worldPreviewActive && (button == 0 || button == 1) && !insideAnyWindow(rawX, rawY)) {
			if (button == 0) {
				int axis = gizmoVisible() ? hitGizmoAxis(rawX, rawY) : -1;
				if (axis >= 0) {
					// 开始拖动该轴（Unity 式平移）
					axisDrag = axis;
					axisStartMouseX = rawX;
					axisStartMouseY = rawY;
					axisStartValue = gizmoValue(axis);
					float[] o = gizmoOrigin();
					float[] e = axisTip(axis);
					axisLenAtDrag = gizmoAxisLen();
					if (o != null && e != null) {
						axisScreenDx = e[0] - o[0]; // 一个轴长度对应的屏幕向量（axisLenAtDrag 个方块）
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

	/** 屏幕点位于三个编辑器窗口之一上时为 true。 */
	private boolean insideAnyWindow(double sx, double sy) {
		// 面板窗口（以 palDx/palDy 偏移绘制）
		if (sx >= 4 + palDx && sx <= listW() + palDx
				&& sy >= paletteY0() + palDy && sy <= paletteY0() + paletteH() + palDy) {
			return true;
		}
		// 属性窗口（两条边都必须带上相同的拖动偏移 —— 右边缘漏掉 propDx
		// 会使面板在被拖到左侧后看起来横跨全屏，从而
		// 吞掉所有落在坐标系轴上的世界点击）
		int px = propX() + propDx;
		int bottom = propPanelBottom() + propDy;
		if (sx >= px - 2 && sx <= propRight() + propDx && sy >= TOP_H + 2 + propDy && sy <= bottom) {
			return true;
		}
		// 时间线窗口（可通过拖动右边缘调整宽度）
		if (sx >= 6 + tlDx0 && sx <= tlX() + tlW() + 6 + tlDx0
				&& sy >= timelineY() + tlDy0 && sy <= timelineY() + TIMELINE_H + tlDy0) {
			return true;
		}
		// 已展开的粒子选择器
		return particleListOpen && inParticlePicker((int) sx - propDx, (int) sy - propDy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();
		if (axisDrag >= 0) {
			axisDrag = -1;
			init(); // 用拖动后的值刷新属性输入框
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
			init(); // 按新的窗口偏移重新定位控件
			return true;
		}
		if (ctrlPressed) { // 时间线左列：点击执行按钮，拖动则移动了窗口
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
						clip = clip.withParticle("minecraft:flame"); // 默认注册表粒子
					}
					clip = pinAnchor(clip); // 入场/出场动画条粘附到时间线边缘
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
		// 释放手动时间线拖动（移动 / 调整大小 / 拖动定位）会暂停播放
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
		return super.mouseReleased(event);
	}

	/** 水平拖动动画条和/或拖到另一条轨道；水平位置会吸附，使同一条轨道上的
	 *  动画条永不重叠（而是落在最近的空闲位置）。 */
	private void dragMove(CompositeClip cur, int x, int y) {
		int src = laneOf(cur);
		if (src < 0) {
			return;
		}
		float dur = Math.max(16, cur.durationMs());
		float want = Math.max(0f, Math.min(Math.max(0f, timelineLen - dur), tlTimeForX(x) - moveGrabMs));
		int rawLane = laneForY(y);
		int target;
		int maxAuto = laneScrollPx / LANE_H + lanesViewH() / LANE_H + 1; // 不要在屏幕外新增行
		if (rawLane >= lanes.size() && lanes.size() < maxAuto) {
			// 拖到最后一行下方 → 新增一条轨道（紧接着就会填充它）
			lanes.add(new ArrayList<>());
			target = lanes.size() - 1;
		} else {
			target = Math.max(0, Math.min(Math.max(0, lanes.size() - 1), rawLane));
		}
		boolean movedLane = false;
		if (target != src && !overlapsIn(lanes.get(target), null, cur.withStart(want))) {
			lanes.get(src).remove(cur);
			if (lanes.get(src).isEmpty() && target > src) {
				// 空行会立即移除 —— 行只在被使用时存在
				lanes.remove(src);
				target--;
			}
			if (target >= lanes.size()) {
				lanes.add(new ArrayList<>()); // 兜底：保留目标行
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

	/** {@code lane} 上在 {@code endRef} 之前结束的最近动画条的结束位置（没有则为 0）。 */
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
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();
		// 同步轮询位置，使第一次轮询的增量为零，然后应用事件增量
		pollX = mouseX;
		pollY = mouseY;
		pollValid = true;
		dragEventFrame = true; // 本帧稍后运行的 pollDrag 不能与此事件冲突
		return applyDrag(mouseX, mouseY, dragX, dragY, button);
	}

	/** 任一拖动/拖动定位手势正在进行时为 true。 */
	private boolean anyDragActive() {
		return lookDrag || viewPanDrag || axisDrag >= 0 || dragWin != 0 || movingClip != null
			|| resizingClip != null || childResizeClip != null || paletteDrag != null || seekDrag
			|| panning || dragKeyIdx >= 0 || lengthDrag || ctrlDragged;
	}

	/** 根据当前光标位置（GLFW）驱动正在进行的拖动。每帧调用一次，因此
	 *  即使鼠标离开窗口或移动/释放事件丢失，拖动也能继续工作。 */
	private void pollDrag() {
		Minecraft mc = Minecraft.getInstance();
		long win = mc.getWindow().handle();
		if (win == 0L) {
			return;
		}
		double[] mx = new double[1];
		double[] my = new double[1];
		GLFW.glfwGetCursorPos(win, mx, my);
		// GLFW 以屏幕坐标报告光标，因此除数必须是窗口的屏幕尺寸
		// —— 正是原版 MouseHandler 所用的。除以帧缓冲尺寸
		// （getWidth）会在 Windows/GPU 缩放不为 100% 时悄悄重新缩放位置，
		// 而由于此轮询每帧运行，它会覆盖来自鼠标事件的（正确）位置
		// —— 于是拖动看起来“只移动了一次”。
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
			return; // 本帧的拖动已由鼠标事件以权威位置驱动
		}
		applyDrag(guiX, guiY, dx, dy, 0);
	}

	private boolean applyDrag(double mouseX, double mouseY, double dragX, double dragY, int button) {
		if (axisDrag >= 0) { // 沿抓取的世界轴拖动所选对象
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
		if (lookDrag) { // 累加 MC 的真实增量；与取整后的坐标比较会每帧漂移
			if (Math.abs(dragX) > 0.01 || Math.abs(dragY) > 0.01) {
				lookAccumX += dragX;
				lookAccumY += dragY;
			}
			return true;
		}
		if (viewPanDrag) { // 右键拖动：沿相机自身平面平移相机
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
		if (dragWin != 0) { // 移动被拖动的窗口
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
			init(); // 拖动时让窗口的按钮/输入框紧贴窗口
			return true;
		}
		if (ctrlPressed) { // 时间线：按下其左侧按钮列
			if (!ctrlDragged && (Math.abs(mouseX - ctrlPressX) > 3 || Math.abs(mouseY - ctrlPressY) > 3)) {
				ctrlDragged = true; // 发生了移动 → 这是窗口拖动，而不是按钮点击
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
		mouseX -= tlDx0; // 其余拖动都作用于时间线窗口
		mouseY -= tlDy0;
		int x = (int) mouseX;
		if (dragKeyIdx >= 0 && dragKeyIdx < textKeys.size()) { // 拖动共享文本关键帧
			TextKey k = textKeys.get(dragKeyIdx);
			k.timeMs = Math.max(0, Math.min(timelineLen, Math.round(tlTimeForX(x))));
			sortTextKeys(textKeys);
			dragKeyIdx = textKeys.indexOf(k);
			return true;
		}
		if (lengthDrag) { // 拖动右边缘调整时间线窗口自身大小
			tlWidth = Math.max(120, Math.min(Math.max(120, pw - tlX() - 6), Math.round((float) x - tlX())));
			return true;
		}
		if (seekDrag) { // 拖动定位播放头并保持其在视野内（时间线跟随）
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
			// 拖动子项条的手柄：移动其起点 / 改变其长度，但窗口
			// 始终保持在分组自身的时间范围之内
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
				movingClip = null; // 其实例已在别处被替换 —— 停止而不是卡住
			} else {
				// 所有动画条（包括淡入淡出）都可自由拖动：水平移动 + 切换轨道
				dragMove(movingClip, x, (int) mouseY);
			}
			return true;
		}
		if (resizingClip != null) {
			CompositeClip c = resizingClip;
			int src = laneOf(c);
			if (src < 0) {
				// 其实例已在别处被替换（例如 pinAnchor 重锚定了入场/出场
				// 动画条）—— 丢弃过期的引用，而不是悄悄冻结拖动
				resizingClip = null;
				return true;
			}
			{
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
				} else { // 保持右边缘固定
					float end = c.startMs() + dur;
					float minStart = prevEndBefore(lane, c, end - 0.5f);
					float ns = Math.max(minStart, Math.min(end - 16, ms));
					candidate = c.withStart(ns).withDuration(end - ns);
				}
				if (candidate != null) {
					// setClip 内部已把 resizingClip 指向重锚定后的实例（入场/出场剪辑经 pinAnchor
					// 会换成新实例）；若在这里再用 candidate 覆盖回去，下一帧 laneOf 就找不到它，
					// 表现为"拖一下就拖不动"。所以这里不再回写。
					setClip(c, candidate);
				}
			}
			return true;
		}
		if (panning) { // 中键：平移时间线视图
			double dms = (x - panLastX) / pxPerMs();
			viewOffsetMs = (float) Math.max(0, viewOffsetMs - dms);
			panLastX = x;
			return true;
		}
		// 26.1：拖拽回退需要事件对象，这里按当前坐标/按键重建一个
		return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY,
			new net.minecraft.client.input.MouseButtonInfo(button, 0)), dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		mouseX -= ox;
		mouseY -= oy;
		double rawX = mouseX;
		double rawY = mouseY;
		// 粒子选择器：滚轮滚动过滤后的列表
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
		// 属性窗口：内容溢出时滚轮滚动面板内容
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
		// 时间线窗口空间
		mouseX = rawX - tlDx0;
		mouseY = rawY - tlDy0;
		// Ctrl + 滚轮悬停时间线 → 缩放
		if (ctrlDown() && mouseX >= tlX() && mouseX <= tlX() + tlW() && mouseY >= timelineY() && mouseY <= timelineY() + TIMELINE_H) {
			double factor = Math.signum(verticalAmount) > 0 ? 1.15 : 1 / 1.15;
			// 以光标为中心缩放：保持光标下的世界时间不变（并做限制，
			// 使左边缘自动对齐到 0，而不是溢出/回弹）。
			// 0.15 让你能缩小到足以一次看到很长的时间线。
			float worldAtCursor = tlTimeForX((int) mouseX);
			zoom = Math.max(0.15, Math.min(6.0, zoom * factor));
			viewOffsetMs = Math.max(0f, (float) (worldAtCursor - (mouseX - tlX()) / pxPerMs()));
			return true;
		}
		// 普通滚轮悬停时间线 → 垂直滚动轨道行（查看下方的动画条）
		if (mouseX >= tlX() && mouseX <= tlX() + tlW() && mouseY >= timelineY() && mouseY <= timelineY() + TIMELINE_H) {
			laneScrollPx = laneScrollPx - (int) Math.signum(verticalAmount) * LANE_H;
			clampLaneScroll();
			return true;
		}
		// 面板窗口空间：普通滚轮滚动列表
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
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		if (cmdEditorOpen) {
			// 模态弹窗：Enter 确认，ESC 取消（不得关闭整个编辑器）
			if (keyCode == 257 || keyCode == 335) {
				confirmCommandEditor();
				return true;
			}
			if (keyCode == 256) {
				closeCommandEditor();
				return true;
			}
			return super.keyPressed(event);
		}
		if (worldPreviewActive && handleMovementKey(event, true)) {
			return true; // 在世界内编辑时 WASD / 跳跃 / 潜行 驱动观察者
		}
		// 播放 / 暂停按键（默认未指定，可在「按键控制」里绑定）；正在输入时不触发
		if (!isTextFieldFocused() && EditorKeybinds.PLAY_PAUSE.matches(event)) {
			playing = !playing;
			return true;
		}
		if (keyCode == 32 && !worldPreviewActive && !isTextFieldFocused()) { // 空格 → 播放/暂停（输入时绝不触发）
			playing = !playing;
			return true;
		}
		if (keyCode == 261 && selectedClip != null && !isTextFieldFocused()) { // Delete → 移除所选动画条
			removeClip(selectedClip);
			init();
			return true;
		}
		return super.keyPressed(event);
	}

	/** 当我们的某个文本输入框持有键盘焦点时为 true —— 快捷键不得抢占其按键。
	 *  （259 是 Backspace 而不是 Delete：把它当作“移除动画条”会在重命名中途删除动画条。） */
	private boolean isTextFieldFocused() {
		return getFocused() instanceof net.minecraft.client.gui.components.EditBox;
	}

	/** 让当前获得焦点的控件（输入框）失焦 —— 点了控件之外的地方时调用，
	 *  否则焦点留在输入框里，之后按 WASD / 空格 / Delete 会被当成打字而不是快捷键。 */
	private void blurWidgetFocus() {
		if (getFocused() != null) {
			setFocused(null);
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (worldPreviewActive && handleMovementKey(event, false)) {
			return true;
		}
		return super.keyReleased(event);
	}

	/** 转发原版移动按键（WASD + 跳跃/潜行），使观察者可以移动。
	 *  跳跃/潜行是升/降观察者的按键，因此必须保持转发 —— 之前的
	 *  漂移实际上是由取整的鼠标增量造成的，而不是这些按键。 */
	private boolean handleMovementKey(KeyEvent event, boolean down) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.KeyMapping[] keys = {
			mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
			mc.options.keyJump, mc.options.keyShift
		};
		for (net.minecraft.client.KeyMapping km : keys) {
			if (km.matches(event)) {
				km.setDown(down);
				if (down) {
					heldMoveKeys.put(km, event.key());
				} else {
					heldMoveKeys.remove(km);
				}
				return true;
			}
		}
		return false;
	}

	/** 26.1：{@code Screen.hasControlDown()} 已移除，改为直接查询按键状态。 */
	private static boolean ctrlDown() {
		com.mojang.blaze3d.platform.Window win = Minecraft.getInstance().getWindow();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	/** 释放我们转发的所有移动按键（界面关闭时使用）。 */
	private void releaseMovementKeys() {
		for (net.minecraft.client.KeyMapping km : heldMoveKeys.keySet()) {
			km.setDown(false);
		}
		heldMoveKeys.clear();
	}

	/** 每帧应用一次累积的右键拖动视角增量，以匹配原版鼠标手感。 */
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
		// 屏幕鼠标坐标已按 GUI 缩放，因此乘回真实像素（原版使用像素）
		double scale = mc.getWindow().getGuiScale();
		double s = mc.options.sensitivity().get() * 0.6 + 0.2;
		double factor = s * s * s * 8.0 * 0.15 * scale;
		float yaw = (float) (player.getYRot() + lookAccumX * factor); // 向右拖动 → 向右看
		// 俯仰：向上拖动 → 向上看。屏幕 Y 轴向下增长，因此向上拖动得到的是负
		// 增量；把它加到俯仰（同样向下增长）上可让动作保持与鼠标
		// 相同的方向。
		float pitch = (float) Math.max(-90.0, Math.min(90.0, player.getXRot() + lookAccumY * factor));
		player.setYRot(yaw);
		player.setXRot(pitch);
		// 保持上一帧角度同步，以免渲染插值发生漂移
		player.yRotO = yaw;
		player.xRotO = pitch;
		lookAccumX = 0;
		lookAccumY = 0;
	}

	/**
	 * 清除物理按键已不再按下的拖动/按键状态。GUI 的释放事件
	 * 可能丢失（窗口焦点变化、鼠标离开窗口），否则会留下
	 * 过期状态 —— 例如卡住的移动键慢慢抬升观察者，或过期的调整大小
	 * 拖动吞掉之后所有的拖动。
	 */
	private void sanitizeInputState() {
		Minecraft mc = Minecraft.getInstance();
		long win = mc.getWindow().handle();
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
			init(); // 按已应用的偏移重新定位控件
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
		// 释放任何不再被物理按下的移动键
		for (java.util.Iterator<java.util.Map.Entry<net.minecraft.client.KeyMapping, Integer>> it = heldMoveKeys.entrySet().iterator(); it.hasNext();) {
			java.util.Map.Entry<net.minecraft.client.KeyMapping, Integer> e = it.next();
			if (GLFW.glfwGetKey(win, e.getValue()) != GLFW.GLFW_PRESS) {
				e.getKey().setDown(false);
				it.remove();
			}
		}
		// 除非是我们按下的，否则绝不让移动键保持按下（修复缓慢的“上升”漂移）
		net.minecraft.client.KeyMapping[] guard = { mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
			mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint };
		for (net.minecraft.client.KeyMapping km : guard) {
			if (km.isDown() && !heldMoveKeys.containsKey(km)) {
				km.setDown(false);
			}
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// 留空：不绘制原版的模糊层
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.currentTimeMillis();
		if (lastFrameMs < 0) {
			lastFrameMs = now;
		}
		if (playing) {
			playTimeMs = Math.min(timelineLen, playTimeMs + (now - lastFrameMs));
		}
		lastFrameMs = now;
		if (overlay) {
			activeOverlay = this; // 世界渲染阶段会绘制我们的预览对象
		}
		applyLookDelta(); // 每帧应用一次累积的右键拖动视角（平滑、无抖动）
		sanitizeInputState(); // 清除物理按键/键已不再按下的拖动/按键状态
		if (!anyDragActive()) {
			refreshTimelineLen(); // 内容驱动的长度，仅在空闲时执行以保持比例稳定
		}
		if (anyDragActive()) {
			pollDrag(); // 即使鼠标离开窗口 / 事件丢失也继续拖动
		} else {
			pollValid = false;
		}
		dragEventFrame = false; // 已被上面的 pollDrag 消费
		refreshPropFields();  // 对象字段实时跟随坐标系拖动 / 播放

		int lmx = mouseX - ox;
		int lmy = mouseY - oy;
		if (!overlay) {
			g.fill(0, 0, pw, ph, GuiTheme.BG);
		}
		// 世界空间预览：在配置世界内我们自行投影对象，无论
		// 编辑器以何种方式打开（浮窗或全屏）。在 render() 末尾会再次绘制
		// 到顶层，使浮窗永远无法遮住它。
		worldPreviewActive = ConfigWorldLauncher.isConfigWorld();
		renderCenter(g);

		// 三个相互独立的浮窗，各自按自身的拖动偏移平移
		g.pose().pushMatrix();
		g.pose().translate(palDx, palDy);
		renderPalette(g, lmx - palDx, lmy - palDy);
		drawWindowHandle(g, 1, 4, paletteY0() - HANDLE_H, listW() - 4, L10n.tr("anima.ui.title.palette"), 4);
		g.pose().popMatrix();

		g.pose().pushMatrix();
		g.pose().translate(propDx, propDy);
		renderProperty(g);
		// 标题栏横跨面板整个宽度（包括两侧 2px 的间隙），因此顶部
		// 边缘不再显得比其后的面板短；其文字保持与面板窗口
		// 标题相同的缩进
		drawWindowHandle(g, 2, propX() - 2, TOP_H + 2, PROP_PANEL_W, propLabel(), PROP_INSET);
		g.pose().popMatrix();

		g.pose().pushMatrix();
		g.pose().translate(tlDx0, tlDy0);
		renderTimeline(g, lmx, lmy);
		g.pose().popMatrix();

		// /particle 弹窗背景绘制在控件之下（其输入框/按钮都是控件，
		// 因此必须在此之后绘制），其边框会在下面重新绘制到顶层
		renderCommandEditorBackground(g);

		// 控件在 init() 中按相同的窗口偏移定位，因此它们能对齐
		super.extractRenderState(g, lmx, lmy, partialTick);

		// 右键下拉菜单绘制在窗口之上（如同原生的上下文菜单）
		renderContextMenu(g, mouseX, mouseY);
		// 弹窗边框/标题绘制在最上层
		renderCommandEditor(g);

		// 临时状态行（保存/导入粒子组）
		if (!notice.isEmpty() && now < noticeUntil) {
			g.text(this.font, notice, 6, 6, GuiTheme.DE_GREEN);
		}

		// 投影出来的坐标系画在最上层，任何窗口都盖不住它
		if (worldPreviewActive) {
			drawGizmo(g); // 原点标记 + 选中对象的可拖拽三轴
		}
	}

	/** 浮窗编辑器打开时为 true（用于避免重复绘制坐标系）。 */
	public static boolean isOverlayActive() {
		return activeOverlay != null;
	}

	/** 绘制某个浮窗的小拖动条（区域：1 面板，2 属性，3 时间线）。 */
	/** 窗口标题栏：比小字段标题大一号的文字，紧贴文字。 */
	private void drawWindowHandle(GuiGraphicsExtractor g, int region, int x, int y, int w, String label, int labelInset) {
		g.fill(x, y, x + w, y + HANDLE_H, handleBg());
		g.fill(x, y, x + w, y + 1, GuiTheme.ACCENT);
		if (!label.isEmpty()) {
			g.text(this.font, label, x + labelInset, y + 2, GuiTheme.SUBTEXT, false);
		}
	}

	/** 时间线窗口的拖动手柄 —— 左侧按钮列本身就是手柄，因此不绘制
	 *  额外的手柄（不移动地点击时这些按钮就是普通按钮）。 */

	// ---- 左侧控制列（播放 / 重头 / 清空）—— 手动绘制，以便拖动时移动窗口 ----

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

	/** 左侧控制按钮数量：播放 / 重头 / 清空。保存由调用方负责。 */
	private int ctrlCount() {
		return 3;
	}

	/** 屏幕坐标点下方的控制按钮下标，否则 -1。 */
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

	/** 屏幕点位于时间线左列（其移动手柄）上的任意位置时为 true。 */
	private boolean inCtrlColumn(double screenX, double screenY) {
		double lx = screenX - tlDx0;
		double ly = screenY - tlDy0;
		return lx >= 4 && lx <= tlX() - 2 && ly >= timelineY() && ly <= timelineY() + TIMELINE_H;
	}

	private void drawControlButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
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
			g.text(this.font, label, 6 + (w - this.font.width(label)) / 2, by + 4,
				over ? GuiTheme.TEXT : GuiTheme.SUBTEXT);
		}
	}

	/** 执行某个左侧控制按钮。 */
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

	/** 属性窗口的标题（显示面板当前正在编辑的内容）。 */
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

	// ------------------------------------------------------------------ 预览对象属性 + 关键帧

	/** 文本对象属性字段的第一行（位置/缩放/透明度）—— 紧接在 动画时长 之后并入
	 *  动画条列表，因此不再孤立地位于 移除 按钮下方。 */
	private int propRowBaseY() { return selectedClip == null ? nameY() : durY() + ROW_H; }
	private int propRowY(int i) {
		int y = propRowBaseY() + i * ROW_H;
		// 文本时长 / 距离缩放 / 显示坐标系 这三行是"标题 + 整行按钮"，按 22px 的默认行距排下来
		// 标题几乎贴着按钮。让它们逐行多让出一点空间。
		if (i >= TEXT_DUR_ROW) {
			y += (i - TEXT_DUR_ROW + 1) * TOGGLE_ROW_GAP;
		}
		return y;
	}

	/** 额外行距：让"距离缩放"这类整行按钮上方留出可见的标题间距。 */
	private static final int TOGGLE_ROW_GAP = 7;

	/** 标题相对其控件顶部的抬高量；开关行抬得更多，避免标题压住按钮。 */
	private int propLabelLift(int i) {
		return i >= TEXT_DUR_ROW ? 10 : 6;
	}

	/** 面板为当前选择显示其中多少行。文本对象拥有全部
	 *  行（位置X/Y/Z + 缩放 + 透明度 + 文本时长 + 距离缩放）；选中的粒子 / 分组只保留
	 *  自身的 偏移X/Y/Z；分组子项和普通动画没有。 */
	private int propShownRows() {
		if (selectedClip == null) {
			return LAST_PROP_ROW + 1;
		}
		return isParticleSelected() && !hasGroupChild() ? 3 : 0;
	}

	// ---- 面板高度（内容驱动、设有上限）+ 滚动 -------------------------------------------------

	/** 属性面板文字/输入框的左缩进（与面板自身的内边距一致）。 */
	private static final int PROP_INSET = 4;
	/** 属性窗口的总宽度（包括两侧 2px 的间隙）。 */
	private static final int PROP_PANEL_W = PROP_W;
	/** 属性面板的右边缘 —— 与其左边缘对称（PROP_INSET + 2px 间隙）。 */
	private int propRight() { return propX() + PROP_PANEL_W - 2; }
	/** 可滚动内容起始的 Y 坐标：窗口标题栏下方（绝不能重叠）。 */
	private int propContentTop() { return TOP_H + 2 + HANDLE_H; }

	/** 属性面板在触及时间线之前可用的高度（保持较短，使其
	 *  永不挤压时间线 —— 内容更高时面板会滚动）。 */
	private int propMaxHeight() { return Math.max(60, timelineY() - 22 - (TOP_H + 2)); }

	/** 滚动为 0 时的内容高度，从内容顶部（标题栏下方）起算。 */
	private int propContentHeight() {
		int save = propScroll;
		propScroll = 0;
		int bottom = propRowBaseY() + propShownRows() * ROW_H + 20;
		if (selectedClip != null) {
			bottom = Math.max(bottom, removeY() + ROW_H);
			// 选中粒子 / 分组时面板里也有「显示坐标系」开关，它排在其它行之后
			if (gizmoToggleShown()) {
				bottom = Math.max(bottom, propRowY(gizmoRowIndex()) + FIELD_H + 8);
			}
		}
		propScroll = save;
		return bottom - propContentTop();
	}

	/** 可见内容区域（面板减去其标题栏）。 */
	private int propViewH() { return Math.max(20, propMaxHeight() - HANDLE_H); }

	private int propMaxScroll() { return Math.max(0, propContentHeight() - propViewH()); }

	/** 面板的底边缘：内容高度，设有上限以免遮住时间线。 */
	private int propPanelBottom() {
		return TOP_H + 2 + Math.min(propContentHeight() + HANDLE_H, propMaxHeight());
	}

	private void clampPropScroll() { propScroll = Math.max(0, Math.min(propMaxScroll(), propScroll)); }

	/** 属性面板的「选择标识」：只有真正换了编辑对象才把滚动归零。
	 *  刻意用 效果名 + 轨道 + 子项下标，而不是动画条实例 —— 编辑器内部（改名 / 改时长 /
	 *  拖窗口 / 播放头刷新）会不断替换动画条实例，比较实例会把编辑过程误判成「换了对象」。 */
	private String propSelectionKey() {
		if (selectedClip == null) {
			return "text";
		}
		return selectedClip.effect() + "#" + laneOf(selectedClip) + "#" + selectedChild;
	}

	/** 文本对象属性的范围（0..2 = 位置 xyz，3 = 缩放，4 = 透明度）。 */
	private double propMin(int p) { return p == 3 ? 0.01 : (p == 4 ? 0 : -64); }
	private double propMax(int p) { return p == 3 ? 5 : (p == 4 ? 1 : 64); }
	private double propStep(int p) { return 0.01; }

	/** 当前播放头时间下属性输入框中显示的值。 */
	private double propDisplay(int p) {
		return propValue(p, playTimeMs);
	}

	// ---- 前三个行的含义取决于当前选择 --------------
	// 选中文本对象：带关键帧的 位置X/Y/Z。选中粒子 / 分组：该动画条
	// 自身的偏移（ox/oy/oz），因此编辑或拖动这些字段永远不会移动文本。

	/** 属性行 {@code i}（0..2）编辑所选粒子自身的偏移时为 true。 */
	private boolean particleOffsetRow(int i) {
		return i <= 2 && isParticleSelected();
	}

	/** 行 {@code i} 所编辑的粒子偏移对应的 JSON 键。 */
	private static String offsetKey(int i) {
		return i == 0 ? "ox" : (i == 1 ? "oy" : "oz");
	}

	/** 属性行 {@code i} 的标题。 */
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

	/** 当前选择下属性行 {@code i} 中显示的值。 */
	private double propRowDisplay(int i) {
		return particleOffsetRow(i) ? param(selectedClip, offsetKey(i), 0) : propDisplay(i);
	}

	private double propRowMin(int i) {
		return particleOffsetRow(i) ? -32 : propMin(i);
	}

	private double propRowMax(int i) {
		return particleOffsetRow(i) ? 32 : propMax(i);
	}

	/** 将属性行 {@code i} 写入它当前描述的目标。 */
	private void setPropRowValue(int i, double v) {
		if (particleOffsetRow(i)) {
			setParam(offsetKey(i), v); // 粒子移动，文本保持原位
		} else {
			setPropFromInput(i, v);
		}
	}

	/** 某个属性在某个时间的值（共享关键帧：每个字段都在相同的关键帧上插值）。 */
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

	/** 播放头处所有属性的快照 —— 创建新的共享关键帧时使用。 */
	private float[] currentPropSnapshot() {
		float[] snap = new float[PROP_COUNT];
		for (int p = 0; p < PROP_COUNT; p++) {
			snap[p] = propValue(p, playTimeMs);
		}
		return snap;
	}

	/** 播放头处（±1ms）的关键帧下标，否则 -1。 */
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

	/** 在播放头处添加一个包含所有属性当前值的关键帧（共享关键帧）。 */
	private void addKeyAtPlayhead() {
		int t = Math.round(Math.max(0f, playTimeMs));
		int idx = keyAtPlayhead();
		if (idx >= 0) {
			return; // 此处已存在关键帧 —— 无需添加
		}
		TextKey k = new TextKey();
		k.timeMs = t;
		System.arraycopy(currentPropSnapshot(), 0, k.v, 0, PROP_COUNT);
		textKeys.add(k);
		sortTextKeys(textKeys);
	}

	/** 字段编辑：无关键帧 → 编辑默认值；有关键帧 → 在此处更新/创建共享关键帧。 */
	private void setPropFromInput(int p, double value) {
		if (updatingFields) {
			return; // 程序化刷新，而非用户编辑
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

	/** 在播放头处写入属性值（坐标系拖动）—— 规则与字段编辑相同。 */
	private void setPropAtPlayhead(int p, float v) {
		setPropFromInput(p, v);
	}

	/** 构建当前选择实际拥有的属性行（见 {@link #propShownRows()}）：
	 *  文本对象的 位置X/Y/Z + 缩放 + 透明度（+ 文本时长 / 距离缩放），或者 —— 选中粒子
	 *  时 —— 只有该粒子自身的 偏移X/Y/Z。偏移行不带关键帧，因此没有
	 *  ◆ 按钮。 */
	private void buildPropFields(int wx, int wy) {
		int rows = propShownRows();
		propFields.clear();
		for (int i = 0; i < PROP_COUNT && i < rows; i++) {
			final int p = i;
			boolean offsetRow = particleOffsetRow(p);
			// 没有 ◆ 按钮的行使用与面板中其他控件相同的完整宽度，
			// 因此粒子的 偏移X/Y/Z 与上方的 数量 / 散布 / 速度 对齐
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
			// 选中的粒子 / 分组也有自己的坐标系（见 gizmoVisible），所以这里同样要有
			// 「显示坐标系」开关 —— 否则选中粒子时根本没法把 gizmo 打开
			addGizmoToggle(wx, wy);
			return; // 文本时长 / 距离缩放 描述的是文本对象，而不是动画条
		}
		// 文本时长 == 时间线长度（时间线自身的长度输入框已被移除）
		textDurField = new NumberField(this.font, wx, wy + propRowY(TEXT_DUR_ROW), PROP_W - PROP_INSET - 8,
			FIELD_H, MIN_TIMELINE_LEN, MAX_TIMELINE_LEN, 10, L10n.tr("anima.ui.prop.text_duration"), timelineLen, v -> {
				// 只有真正的修改才会固定长度（该字段也会被程序化刷新）
				if (v != timelineLen) {
					timelineLenOverride = v;
					refreshTimelineLen();
				}
			});
		addRenderableWidget(textDurField);
		// 距离缩放：开 = 真实透视（近大远小）；关 = 恒定视觉大小
		addRenderableWidget(ThemeButton.of(wx, wy + propRowY(DIST_ROW), PROP_W - PROP_INSET - 8, FIELD_H,
			Component.literal(textDistanceScale ? L10n.tr("anima.ui.prop.distance_scale_on") : L10n.tr("anima.ui.prop.distance_scale_off")), b -> {
				textDistanceScale = !textDistanceScale;
				init();
			}));
		// 显示坐标系（世界里的三轴 gizmo）：默认关闭，需要拖轴调整位置时再打开
		addGizmoToggle(wx, wy);
	}

	/** 「显示坐标系」整行开关 —— 文本对象与选中的粒子 / 分组都能开关世界里的三轴 gizmo。 */
	private void addGizmoToggle(int wx, int wy) {
		if (!gizmoToggleShown()) {
			return;
		}
		addRenderableWidget(ThemeButton.of(wx, wy + propRowY(gizmoRowIndex()), PROP_W - PROP_INSET - 8, FIELD_H,
			Component.literal(showGizmo ? L10n.tr("anima.ui.prop.gizmo_on") : L10n.tr("anima.ui.prop.gizmo_off")), b -> {
				showGizmo = !showGizmo;
				init();
			}));
	}

	/** 「显示坐标系」按钮是否出现在当前选择下：文本对象，或选中了整个粒子 / 分组。
	 *  （分组的子粒子没有自己的偏移，因此不显示。） */
	private boolean gizmoToggleShown() {
		return selectedClip == null || (isParticleSelected() && !hasGroupChild());
	}

	/** 「显示坐标系」按钮的行号：文本对象排在各开关行之后；选中粒子时紧接其「移除该条」下方。 */
	private int gizmoRowIndex() {
		return selectedClip == null ? GIZMO_ROW : propShownRows() + 1;
	}

	/** 让属性字段实时与数值（坐标系拖动 / 播放 / 时间线上改长度）保持同步。 */
	private void refreshPropFields() {
		// 时间线上拖动绿条改长度时，属性面板的「动画时长」也要跟着变
		if (clipDurField != null && selectedClip != null && !clipDurField.isFocused()) {
			String want = String.valueOf(Math.round(selectedClip.durationMs()));
			if (!want.equals(clipDurField.getValue())) {
				updatingFields = true;
				clipDurField.setValue(want);
				updatingFields = false;
			}
		}
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
				continue; // 用户输入时绝不与之冲突
			}
			String want = DecimalField.format(propRowDisplay(i));
			if (!want.equals(f.getValue())) {
				updatingFields = true;
				f.setValue(want);
				updatingFields = false;
			}
		}
	}

	/** 用于每个字段标题的小号（0.75×）标签，使标题紧贴其输入框。 */
	private void drawSmallLabel(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75f, 0.75f);
		g.text(this.font, text, 0, 0, color, false);
		g.pose().popMatrix();
	}

	/** 当前选择所拥有属性行的标签（小号，位于输入框上方，左对齐）。
	 *  选中粒子时显示的行是该粒子自身的 偏移X/Y/Z。 */
	private void renderPropLabels(GuiGraphicsExtractor g, int px) {
		int rows = propShownRows();
		for (int i = 0; i < PROP_COUNT && i < rows; i++) {
			drawSmallLabel(g, propRowLabel(i), px + PROP_INSET, propRowY(i) - propLabelLift(i),
				i == activeProp && !particleOffsetRow(i) ? GuiTheme.ACCENT : GuiTheme.SUBTEXT);
		}
		if (selectedClip == null) {
			drawSmallLabel(g, L10n.tr("anima.ui.prop.text_duration"), px + PROP_INSET,
				propRowY(TEXT_DUR_ROW) - propLabelLift(TEXT_DUR_ROW), GuiTheme.SUBTEXT);
			drawSmallLabel(g, L10n.tr("anima.ui.prop.distance_scale"), px + PROP_INSET,
				propRowY(DIST_ROW) - propLabelLift(DIST_ROW), GuiTheme.SUBTEXT);
		}
	}

	// ------------------------------------------------------------------ 粒子选择器（注册表列表 + 已保存分组）

	private static final int PICKER_SEARCH_H = 13;

	/** 粒子选择器中的一行：一个注册表粒子，或一个已保存的粒子组 JSON。 */
	private record PickerEntry(String label, boolean isGroup, String id, JsonObject groupJson) {
	}

	/** 选择器中显示的所有行：先是已保存分组，然后是注册表粒子，并按搜索过滤。 */
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

	/** 重新加载显示在选择器顶部的已保存粒子组 JSON。 */
	private void refreshPickerGroups() {
		pickerGroups = AnimationConfigStore.get().loadParticleGroups();
	}

	/** 关闭选择器而不重建界面 —— 同时隐藏其搜索栏控件。 */
	private void closeParticlePicker() {
		particleListOpen = false;
		if (searchBox != null) {
			searchBox.visible = false;
		}
	}

	/** 打开选择器：重置搜索，滚动到当前粒子，聚焦搜索栏。 */
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
	/** 搜索栏下方可见的行数。 */
	private int pmVisibleRows() { return Math.max(1, (pmListH() - PICKER_SEARCH_H - 6) / pmRowH()); }
	/** 第一列表行的窗口局部 Y 坐标（搜索栏下方）。 */
	private int pmRowsY() { return pmListY() + PICKER_SEARCH_H + 3; }

	/** 绘制已展开的选择器：顶部为搜索栏，然后是已保存分组和注册表粒子。 */
	private void renderParticlePicker(GuiGraphicsExtractor g) {
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
			g.text(this.font, font.plainSubstrByWidth(label, lw - 8), lx + 4, ry + 1,
				e.isGroup() ? GuiTheme.DE_GREEN : (sel ? 0xFFFFFFFF : GuiTheme.TEXT));
		}
	}

	/** 给定的窗口局部点位于粒子选择器矩形内时为 true。 */
	private boolean inParticlePicker(int x, int y) {
		return particleListOpen && selectedClip != null && isParticleSelected()
			&& x >= pmListX() && x <= pmListX() + pmListW() && y >= pmListY() && y <= pmListY() + pmListH();
	}

	/** 显示一条临时状态行（例如 “已保存粒子组”）。 */
	private void showNotice(String s) {
		notice = s;
		noticeUntil = System.currentTimeMillis() + 3000L;
	}

	// ------------------------------------------------------------------ /particle 指令弹窗

	/** 用于输入原版 /particle 指令的弹窗（右下角为 确认 / 取消）。 */
	private static final int CMD_W_MAX = 420;
	private static final int CMD_H = 88;

	private boolean cmdEditorOpen;
	private EditBox cmdBox;
	private String cmdSeed = "";

	private int cmdW() { return Math.min(CMD_W_MAX, Math.max(220, pw - 40)); }
	private int cmdX() { return Math.max(6, (pw - cmdW()) / 2); }
	private int cmdY() { return Math.max(TOP_H + 34, (ph - CMD_H) / 2); }

	/** 该点位于指令弹窗内时为 true（用于保持其模态）。 */
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

	/** 构建弹窗的控件（仅在它打开时）。 */
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

	/** 弹窗背景（在控件之前绘制，使输入框/按钮位于其上方）。 */
	private void renderCommandEditorBackground(GuiGraphicsExtractor g) {
		if (!cmdEditorOpen) {
			return;
		}
		g.fill(cmdX() - 4, cmdY() - 4, cmdX() + cmdW() + 4, cmdY() + CMD_H + 4, 0xFF14171C);
	}

	private void renderCommandEditor(GuiGraphicsExtractor g) {
		if (!cmdEditorOpen) {
			return;
		}
		int x = cmdX();
		int y = cmdY();
		int w = cmdW();
		g.fill(x - 4, y - 4, x + w + 4, y - 3, GuiTheme.ACCENT);
		g.text(this.font, L10n.tr("anima.ui.button.import_command"), x + 8, y + 8, GuiTheme.ACCENT);
		drawSmallLabel(g, L10n.tr("anima.ui.cmd.example"), x + 8, y + 42,
			GuiTheme.SUBTEXT);
	}

	/** 面板背景：在世界之上近乎透明，全屏模式下为不透明。 */
	private int panelBg() {
		return overlay ? 0x44282828 : GuiTheme.PANEL;
	}

	private int handleBg() {
		return overlay ? 0x802E2E2E : 0xE02E2E2E;
	}

	/** 窗口手柄的屏幕坐标矩形（用于命中测试）。 */
	private boolean hitWindowHandle(double mx, double my, int region) {
		if (region == 1) {
			return mx >= palDx + 4 && mx <= palDx + 4 + (listW() - 4) && my >= palDy + paletteY0() - HANDLE_H && my <= palDy + paletteY0();
		}
		if (region == 2) {
			return mx >= propDx + propX() - 2 && mx <= propDx + propRight() && my >= propDy + TOP_H + 2 && my <= propDy + TOP_H + 2 + HANDLE_H;
		}
		// 时间线没有标题栏：它通过左侧按钮列移动（在
		// mouseClicked 中处理，使按钮能区分点击与拖动）
		return false;
	}

	/** 浮窗编辑器打开时保持世界继续 tick/渲染。 */
	@Override
	public boolean isPauseScreen() {
		return !overlay;
	}

	@Override
	public void removed() {
		releaseMovementKeys(); // 绝不让转发的移动键卡在按下状态
		// 还原为 NeoForge 原本的 IN_GAME 上下文（Fabric 是空实现）
		if (PlatformHooks.get() != null) {
			PlatformHooks.get().allowMovementKeysInGui(false);
		}
		// 一次性把编辑后的动画条 + 文本对象属性交还给调用方（编辑器没有
		// 保存按钮；由调用方决定何时/是否持久化它们，例如其配置界面的保存）
		if (!clipsHandedBack && onSave != null) {
			clipsHandedBack = true;
			onSave.accept(new ClipEditorResult(dumpClips(), dumpTextProps()));
		}
		if (activeOverlay == this) {
			activeOverlay = null;
			previewPositioned = false; // 下次编辑器打开时重新居中视图
		}
		super.removed();
	}

	// ------------------------------------------------------------------ 世界空间预览对象（3D 文本）

	private static boolean worldTextHookInstalled;    // 世界渲染钩子只注册一次
	private static boolean worldText3DEnabled = true; // false = 回退到 HUD 投影

	/** 用真正的 3D 文本绘制预览文本（世界空间、透视、深度遮挡）。 */
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
			double dist = camera.position().distanceTo(new net.minecraft.world.phys.Vec3(objectX(), objectY(), objectZ()));
			scale *= (float) Math.max(0.05, dist / DIST_SCALE_REF) * DIST_SCALE_OFF_BOOST;
		}
		WorldText3D.draw(buffers, camera, text, objectX(), objectY(), objectZ(), color,
			scale, m.tx, m.ty, m.sx, glyphs, 1f);
	}

	/** 缩放 = 1 时，预览文本中 1 个 GUI 像素 = 这么多方块。 */
	private static final float TEXT_WORLD_SCALE = 0.018f;

	/** 基础文本比例校准到的距离（方块）；距离缩放关闭时使用。 */
	private static final double DIST_SCALE_REF = 6.0;

	/**
	 * 距离缩放关闭时的额外放大：关闭后恒定大小是按 {@link #DIST_SCALE_REF} 校准的，在更近的
	 * 距离下会明显小于开启状态（近大远小），这里补一点尺寸，让两种状态的观感差距更小。
	 */
	private static final float DIST_SCALE_OFF_BOOST = 1.4f;

	/** 世界渲染阶段入口：绘制当前活动浮窗编辑器的 3D 预览文本。 */
	private static void renderWorldTextStatic(PoseStack pose, MultiBufferSource buffers,
			Camera camera, float partialTick) {
		CompositeEditScreen screen = activeOverlay;
		if (screen != null) {
			screen.renderWorldText3D(buffers, camera);
		}
	}

	/**
	 * 画坐标系（原点标记 + 三条可拖拽的轴，Unity 式移动轴）。
	 * <p>
	 * 全部在<b>物理像素</b>下光栅化（把 pose 除以 GUI 缩放），这样覆盖率抗锯齿在任何 GUI 缩放下
	 * 都能得到平滑边缘。轴的位置仍来自世界坐标投影（{@link #axisTip}），所以轴会随目标在世界里的
	 * 位置变化，但线条本身画在 2D 层、屏幕朝向固定。
	 */
	private void drawGizmo(GuiGraphicsExtractor g) {
		if (!gizmoVisible()) {
			return;
		}
		float[] o = gizmoOrigin();
		if (o == null || o[2] <= 0.001f) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int scale = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
		g.pose().pushMatrix();
		g.pose().scale(1f / scale, 1f / scale); // 从这里开始 1 单位 = 1 物理像素
		float ox = o[0] * scale;
		float oy = o[1] * scale;
		// 固定的世界原点（偏移量相对它计算）——一个不会移动的柔和光点
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
			// 先画一层半透明外发光，再画实心内芯——边缘渐隐，不会出现硬台阶
			drawLineAA(g, ox, oy, ex, ey, withAlpha(col, 0.22f), core + 2.0f * scale);
			drawLineAA(g, ox, oy, ex, ey, col, core);
			drawDiscAA(g, ex, ey, (hot ? 4.6f : 4.0f) * scale, withAlpha(col, 0.22f));
			drawDiscAA(g, ex, ey, (hot ? 3.2f : 2.7f) * scale, col);
		}
		// 中心标记：粒子发射点 / 粒子组的中心点。
		// （这里刻意不画散布圆环——在屏幕上容易被误认成一个多余的圆圈。）
		drawDiscAA(g, ox, oy, 3.6f * scale, withAlpha(0xFFFFFFFF, 0.16f));
		drawDiscAA(g, ox, oy, 1.6f * scale, 0xFFFFFFFF);
		g.pose().popMatrix();

		// 轴端字母留在 GUI 空间，这样它们保持正常字号
		for (int a = 0; a < 3; a++) {
			float[] e = axisTip(a);
			if (e == null || e[2] <= 0.001f) {
				continue;
			}
			drawSmallLabel(g, AXIS_NAMES[a], Math.round(e[0]) + 4, Math.round(e[1]) - 10, AXIS_COLORS[a]);
		}
	}

	// ------------------------------------------------------------------ 世界坐标系（原点 + 三条轴）

	private static final int[] AXIS_COLORS = { 0xFFFF5A5A, 0xFF6BE86B, 0xFF5AA9FF }; // X 红，Y 绿，Z 蓝
	private static final String[] AXIS_NAMES = { "X", "Y", "Z" };

	/** 坐标系有可编辑对象时为 true：文本对象，或选中的粒子动画条。 */
	private boolean gizmoVisible() {
		return worldPreviewActive && showGizmo && (selectedClip == null || isParticleSelected());
	}

	/** 坐标系附着点的世界坐标。文本对象使用带关键帧的 位置；
	 *  粒子 / 粒子团拥有自身的偏移（ox/oy/oz），因此移动粒子永远不会
	 *  把示例文本一起带走。 */
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

	/** 坐标系在某个轴上显示/编辑的值：文本对象带关键帧的 位置，或
	 *  所选粒子 / 分组相对于对象自身的偏移。 */
	private float gizmoValue(int axis) {
		if (selectedClip == null) {
			return propValue(axis, playTimeMs);
		}
		String key = axis == 0 ? "ox" : (axis == 1 ? "oy" : "oz");
		return (float) param(selectedClip, key, 0);
	}

	/** 回写坐标系拖动：文本写回带关键帧的对象属性，粒子写回自身偏移。 */
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

	/** 坐标系附着点（所选对象）的屏幕位置。 */
	private float[] gizmoOrigin() {
		float[] p = gizmoPos();
		return WorldProjection.project(p[0], p[1], p[2]);
	}

	/** 固定世界原点的屏幕位置（偏移量相对的锚点）。 */
	private float[] anchorScreen() {
		return WorldProjection.project(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
	}

	/** 坐标系中透视缩短最少的轴期望的屏幕长度，单位为 GUI 像素。每条轴都按
	 *  此尺寸乘以其透视缩短比例绘制，因此坐标系仍
	 *  不会随距离变大/变小，但转为正对镜头的轴会向
	 *  原点收缩，而不是沿纯属投影噪声的方向按全长绘制。 */
	private static final float GIZMO_LEN_PX = 52f;

	/** 绘制长度低于此值的轴会被隐藏：它（几乎）正对
	 *  相机，因此其屏幕方向没有意义 —— 这正是 3D 编辑器对
	 *  正对镜头的轴所做的处理。保持在抓取半径之上，使隐藏的轴永远无法被选中。 */
	private static final float GIZMO_MIN_AXIS_PX = 9f;

	/** 沿 {@code axis} 方向一个世界方块的屏幕向量，形式为 {@code {dx, dy, len}}（len 是
	 *  透视缩短后的像素长度，轴正对镜头时为 0），当附着点
	 *  / 轴端无法投影（位于相机背后）时为 {@code null}。 */
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

	/** 透视缩短最少的轴上每个世界方块的像素数 —— 无论相机角度和距离
	 *  如何，都用它保持整个坐标系在屏幕上的大小恒定。 */
	private float gizmoRefPx() {
		float ref = 0f;
		for (int a = 0; a < 3; a++) {
			float[] s = axisScreen(a);
			if (s != null) {
				ref = Math.max(ref, s[2]);
			}
		}
		return ref < 0.05f ? 1f : ref; // 所有轴都正对镜头（退化）→ 合理的回退值
	}

	/** 所绘制的轴代表的世界长度。因为每条轴都按
	 *  {@code GIZMO_LEN_PX * itsOwnForeshortening / ref} 绘制，所以三条轴都代表这个相同长度，
	 *  鼠标沿轴拖动时就用它换算回去。 */
	private float gizmoAxisLen() {
		return (float) Math.max(0.001f, Math.min(64.0f, GIZMO_LEN_PX / gizmoRefPx()));
	}

	/** 轴端的屏幕位置：{@link #GIZMO_LEN_PX} 按该轴自身的
	 *  透视缩短比例缩减，因此正对镜头的轴会收缩到原点。当它指向
	 *  背离方向足够远、完全不应绘制时为 {@code null}。 */
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
		float k = len / s[2]; // 将单位方块的屏幕向量放大到绘制长度
		return new float[] { o[0] + s[0] * k, o[1] + s[1] * k, o[2] };
	}

	/** 通过对线条矩形做扫描线覆盖率实现的抗锯齿线段 —— 没有逐像素循环，
	 *  因此即使在 guiScale 4 下开销也很低（每条扫描线只有少数几个四边形）。 */
	private static void drawLineAA(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color, float width) {
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
		// 安全阀：极长的线条使用更粗的条带绘制，以保持填充次数较低
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
			float cov = (w0 + w1) * 0.5f; // 此条带的平均覆盖率
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

	/** 将凸四边形的扫描线交点收集到 {@link #SPAN_X} 中；返回交点数量。 */
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

	/** 抗锯齿的实心圆盘，同样基于扫描线（每行一个四边形）。 */
	private static void drawDiscAA(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
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

	/** 圆在扫描线 y 处的弦长（在圆外为 0）。 */
	private static float chord(float r, float cy, float y) {
		float dy = y - cy;
		float t = r * r - dy * dy;
		return t <= 0f ? 0f : 2f * (float) Math.sqrt(t);
	}

	/** 光标下是哪条轴（0=X，1=Y，2=Z），否则 -1。 */
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

	/** 缩放 ARGB 颜色的 alpha 通道。 */
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

	// ------------------------------------------------------------------ 逐字符文本动画

	/** 字符级入场/出场动画：它们按各自的时钟移动/缩放每个字符
	 *  （打字入场/出场 加上下落 / 漂移变体），不同于整串文本效果。 */
	private static boolean isCharAnim(String key) {
		return CharClips.isCharClip(key);
	}

	/** 当前位于时间线上的字符动画条（见 {@link CharClips}）。 */
	private List<CharClips.Clip> charClips() {
		List<CharClips.Clip> out = new ArrayList<>();
		for (CompositeClip c : allClips()) {
			if (CharClips.isCharClip(c.effect())) {
				out.add(new CharClips.Clip(c.effect(), c.startMs(), c.durationMs(), c.speed()));
			}
		}
		return out;
	}

	/** 至少有一个字符动画条正在播放时为 true（此时预览会逐字符
	 *  绘制文本，而不是作为一整串绘制）。 */
	private boolean hasActiveCharAnim() {
		return CharClips.isActive(charClips(), playTimeMs);
	}

	/** 所有活动字符动画合并后的逐字符变换（null = 无）。 */
	private WorldText3D.Glyph[] charTransforms(String text) {
		return CharClips.compute(text, charClips(), playTimeMs);
	}

	/** 逐字符绘制文本，每个字符都有自己的偏移/缩放/旋转/透明度。 */
	private void drawChars(GuiGraphicsExtractor g, Font font, String text, float centreX, float y, int color,
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
				g.pose().pushMatrix();
				// (x + cw/2, y + 4) 是字形的中心 → 缩放和旋转围绕它进行
				g.pose().translate(x + cw / 2f + t.dx, y + 4f + t.dy);
				g.pose().scale(s, s);
				if (t.rot != 0f) {
					g.pose().rotate(t.rot);
				}
				g.text(font, ch, -font.width(ch) / 2, -4, (a << 24) | rgb, false);
				g.pose().popMatrix();
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

	// ------------------------------------------------------------------ 世界中的真实 3D 粒子

	private static void tickOverlayStatic() {
		CompositeEditScreen screen = activeOverlay;
		if (screen != null) {
			screen.emitWorldParticles();
		}
	}

	/**
	 * 当 自定义粒子 动画条正在播放时，在世界空间的预览位置发射真实的 3D 原版粒子。
	 * 这些粒子存在于客户端世界层级中，因此具有真实的深度/遮挡，
	 * 并由原版粒子引擎模拟。
	 */
	/** 动画条截至目前应发射的粒子总数，以及我们已发射的数量。
	 *  数量 现在表示整条动画条的总量（1 = 恰好一个粒子），而不是每 tick 的数量。 */
	private final java.util.Map<CompositeClip, int[]> emittedByClip = new java.util.IdentityHashMap<>();
	private float lastEmitPlayhead = -1f;

	private void emitWorldParticles() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		if (playTimeMs < lastEmitPlayhead) {
			emittedByClip.clear(); // 播放头向后跳转 → 从头重放
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
			// 动画条自身相对于对象的偏移（ox/oy/oz）—— 粒子自行移动，
			// 独立于文本对象带关键帧的位置
			double ox = param(c, "ox", 0);
			double oy = param(c, "oy", 0);
			double oz = param(c, "oz", 0);
			if ("particle_group".equals(c.effect())) {
				// 分组会发射其持有的每个条目，各自带自己的 数量 / 散布 / 速度
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
					// 每个子项在分组内都有自己的窗口：只有当播放头位于
					// [s, s+d] 内时才发射，并用该窗口计算其进度
					float cs = (float) groupItemStart(c, i);
					float cd = (float) Math.max(16f, Math.min(groupItemDur(c, i), Math.max(16f, dur - cs)));
					float localItem = local - cs;
					if (localItem < 0 || localItem > cd) {
						done[i] = 0; // 在其窗口之外 → 重新进入时会重放
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
			// 自身相对于对象的偏移 + 原版散布/速度，使粒子留在
			// 放置的位置，而不是跟随文本漂移
			int n = emitProgress(resolveParticle(c), objectX() + ox, objectY() + oy, objectZ() + oz,
				p3dParam(c, "count", 1),
				param(c, "dx", 0), param(c, "dy", 0), param(c, "dz", 0), param(c, "speed", 0),
				progress, done, 0);
			if (n > 0) {
				done[0] += n;
			}
		}
	}

	/** 仅发射在动画条 {@code progress} 处“到期”的粒子（总量 = 数量）。
	 *  总量为 1 时会在最开始时立即发射其唯一粒子（向下取整会把它推迟到动画条
	 *  结束之后 —— 这就是“1 个粒子”以前什么都不显示的原因）。{@code deltaX/Y/Z} 和 {@code speed}
	 *  遵循原版 {@code /particle} 语义：0/0/0 + 0 使每个粒子完全固定不动。 */
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
		int n = Math.min(missing, 8); // 每帧最多几个，使爆发保持平滑
		WorldParticles.emit(id, x, y, z, n, deltaX, deltaY, deltaZ, speed);
		return n;
	}

	/** 直接绘制在背景上的单行操作提示，紧贴最左侧（无边框）。 */
	private void renderGuide(GuiGraphicsExtractor g) {
		int gy = (int) (timelineY() - 14);
		g.text(this.font, L10n.tr("anima.ui.guide.timeline"),
			4, gy, GuiTheme.SUBTEXT);
	}

	private void renderPalette(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y0 = paletteY0();
		int h = paletteH();
		// 窗口背景与属性窗口从同一顶线开始（其标题栏
		// 位于该背景之内），因此两个顶角窗口完全对齐
		g.fill(4, TOP_H + 2, listW(), y0 + h, panelBg());
		int rowH = 20;
		int visible = (h - 4) / rowH;
		List<Object> rows = rows();
		// 指针悬在哪一行（用于悬停反馈 / 折叠提示）
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
				// 仅悬停高亮 —— 竖条已移除；色块在 20px 的行上
				// 居中，因此相对文字永远不会显得上/下偏移
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.text(this.font, PRESET_MARK + p.name(), 8, y + 5, over ? GuiTheme.WARN : GuiTheme.TEXT);
			} else if (o instanceof PaletteGroup pg) {
				// 已保存的粒子组 JSON → 点击将其添加到时间线
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.text(this.font, font.plainSubstrByWidth("≡ " + pg.name(), listW() - 12), 8, y + 5,
					over ? GuiTheme.WARN : GuiTheme.DE_GREEN);
			} else { // 分组标题 → 折叠切换：展开时为 "- " / 折叠时为 "+ "
				String cat = (String) o;
				boolean open = !collapsedCats.contains(cat);
				if (over) {
					g.fill(6, y + 1, listW() - 2, y + 19, 0x803C3C3C);
				}
				g.text(this.font, (open ? "- " : "+ ") + cat, 8, y + 5, GuiTheme.ACCENT);
			}
		}
		// 拖动残影跟随光标
		if (paletteDrag != null) {
			EffectPreset p = palette().stream().filter(e -> e.key().equals(paletteDrag)).findFirst().orElse(null);
			if (p != null) {
				int gx = mouseX + 8, gy = mouseY + 2;
				g.fill(gx - 2, gy - 2, gx + this.font.width(p.name()) + 6, gy + 10, GuiTheme.PANEL);
				g.text(this.font, p.name(), gx, gy, GuiTheme.WARN);
			}
		}

		// "+" 菜单：自定义粒子（从面板打开；分组在时间线上创建）
		if (addMenuOpen) {
			int mx = listW() + 6;
			int my = addMenuY;
			g.fill(mx, my, mx + 92, my + 20, 0xE6121418);
			g.fill(mx, my, mx + 92, my + 1, GuiTheme.ACCENT);
			g.text(this.font, L10n.tr("anima.ui.effect.custom_particle"), mx + 6, my + 6, GuiTheme.TEXT);
		}
	}

	private void renderCenter(GuiGraphicsExtractor g) {
		int cx = centerX();
		int cy = centerY();
		int cw = centerW();
		int ch = centerH();
		if (!overlay) {
			// 全屏编辑器：不透明的预览画布。在浮窗中中间区域
			// 有意留空 —— 其后的真实世界就是预览。
			g.fill(cx - 2, cy - 2, cx + cw + 2, cy + ch + 2, GuiTheme.BORDER);
			g.fill(cx, cy, cx + cw, cy + ch, 0xFF000000);
		}

		RenderModifier.Builder b = RenderModifier.builder();
		for (CompositeClip c : allClips()) {
			float localMs = (playTimeMs - c.startMs()) * c.speed();
			if (localMs < 0 || localMs > c.durationMs()) {
				continue;
			}
			if (c.effect().startsWith("particle_")) {
				continue; // 粒子由世界里的 3D 路径发射，不参与文本修饰
			}
			IAnimationEffect ef = effectFor(c.effect());
			if (ef != null) {
				ef.apply(Math.max(0, localMs) / 1000f, Math.max(1, c.durationMs()) / 1000f, random, b);
			}
		}
		RenderModifier m = b.build();

		// 打字机 / 下落 / 漂移 动画条会逐字符动画（见 drawChars）；单纯的
		// 打字机前缀仅用于整串文本路径
		boolean charAnim = hasActiveCharAnim();
		typedVisible = charAnim ? -1 : typewriterVisibleChars();
		if (worldPreviewActive || overlay) {
			// 预览对象存在于世界中 —— 此处不额外在其上绘制任何内容
		} else if (charAnim) {
			// 逐字符预览：保持与默认渲染器相同的中心/锚点
			WorldText3D.Glyph[] ts = charTransforms(sampleFull());
			if (ts != null) {
				drawChars(g, this.font, sampleFull(), cx + cw / 2f + m.tx, cy + (ch - 9) / 2f + m.ty,
					modColor(m), ts, Math.max(0.01f, m.sx));
			}
		} else {
			// 库自带的预览，或其他 mod 注册的自定义预览
			previewRenderer().render(g, this.font, cx, cy, cw, ch, m);
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

	/** 取粒子 id 的种类名：去掉命名空间，只保留最后一段下划线之后的部分。 */
	private static String particleKind(String key) {
		String k = key;
		int ns = k.indexOf(':');
		if (ns >= 0) {
			k = k.substring(ns + 1);
		}
		int i = k.lastIndexOf('_');
		return i < 0 ? k : k.substring(i + 1);
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

	private void renderProperty(GuiGraphicsExtractor g) {
		int px = propX();
		int lx = px + PROP_INSET; // 标题/数值保持与面板文字相同的左缩进
		int right = propRight();
		// 每个交互控件的左/右边缘（与 buildPropertyPanel 中构建字段/按钮所用的
		// 矩形相同），使粒子名称行与它们全部对齐
		int fieldR = px + PROP_W - 8;
		// 面板高度适应内容，但设有上限以免遮住时间线；
		// 超过上限的部分可滚动（在面板上滚动滚轮）
		int bottom = propPanelBottom();
		g.fill(px - 2, TOP_H + 2, right, bottom, panelBg());
		boolean scrollClip = propMaxScroll() > 0;
		if (scrollClip) {
			// 裁剪到内容区域（标题栏下方），使滚动后的行永远不会
			// 被绘制到窗口标题之上
			g.enableScissor(px - 2 + propDx, propContentTop() + propDy, right + propDx, bottom + propDy);
		}
		if (selectedClip == null) {
			// 未选中动画条 → 只显示文本对象属性（+ 关键帧按钮）
			renderPropLabels(g, px);
		} else {
			CompositeClip c = selectedClip;
			// （窗口标题位于此面板上方绘制的拖动条中）
			// 字段标题为小号（0.75×）并紧贴其输入框
			if (hasGroupChild()) {
				// 在时间线上选中的子项：显示其自身的行，而非分组的属性
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
					// 分组摘要行 —— 展开/折叠由时间线色块上的 -/+ 完成
					int n = groupCount();
					g.fill(lx, particleY() + 1, fieldR, particleY() + 15, 0x602E2E2E);
					String head = L10n.tr("anima.ui.particle.group_summary", n,
						isGroupExpanded(c) ? L10n.tr("anima.ui.suffix.expanded") : L10n.tr("anima.ui.suffix.collapsed"));
					g.text(this.font, font.plainSubstrByWidth(head, fieldR - lx - 14), lx + 1, particleY() + 5, GuiTheme.ACCENT);
					drawSmallLabel(g, L10n.tr("anima.ui.prop.particle_group"), lx, particleY() - 6, GuiTheme.SUBTEXT);
				} else if (isParticleSelected()) {
					g.fill(lx, particleY() + 1, fieldR, particleY() + 15, 0x602E2E2E);
					g.text(this.font, font.plainSubstrByWidth(resolveParticle(c), fieldR - lx - 14), lx + 1, particleY() + 5, GuiTheme.ACCENT);
					g.text(this.font, particleListOpen ? "▴" : "▾", fieldR - 9, particleY() + 5, GuiTheme.TEXT);
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
		// 选择器从面板左侧弹出 —— 在裁剪关闭后绘制它，
		// 否则它会被裁掉，只保留可点击状态（不可见列表 bug）
		if (selectedClip != null) {
			renderParticlePicker(g);
		}
	}

	private void renderTimeline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int ty = timelineY();
		int tx = tlX();
		int tw = tlW();
		g.fill(6, ty, tx + tw + 1, ty + TIMELINE_H, panelBg());
		// 左列包含 播放 / 重头 / 清空（同时兼作窗口的移动手柄）
		drawControlButtons(g, mouseX, mouseY);

		// 带有“整齐”刻度的标尺：对齐到整 100/200/500/1000……ms，使 1 秒标记能出现
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
				break; // 不要在设定长度之外绘制无限延伸的轴
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

		// 轨道行（可用滚轮垂直滚动）：每条轨道可容纳多个互不重叠的
		// 动画条；行是自动的（按需新增，不用时移除），行之间
		// 只绘制 1px 分隔线 —— 没有行背景。已展开
		// 粒子团的子行会在稍后的阶段绘制（见 renderGroupChildren），因此它们位于
		// 时间线自身装饰之上的一层。每个条形都被限制在可见带内，
		// 因此部分滚动的行不会溢出面板边缘。
		int viewTop = lanesTopY();
		int viewBottom = ty + TIMELINE_H;
		for (int li = 0; li < lanes.size(); li++) {
			int y = laneTopOf(li);
			if (y > viewBottom || y + laneTotalH(li) < viewTop) {
				continue; // 该轨道（及其子项）完全在可见带之外
			}
			List<CompositeClip> lane = lanes.get(li);
			int blockY0 = Math.max(viewTop, y);
			int blockY1 = Math.min(viewBottom, y + BLOCK_H);
			boolean laneRowVisible = blockY1 > blockY0;
			for (CompositeClip c : lane) {
				int raw0 = btX0(c);
				int wPx = (int) (Math.max(16, c.durationMs()) * pxPerMs());
				// 裁剪到可见的时间线区域（避免负轴溢出）
				int x0 = Math.max(tx, raw0);
				int x1 = Math.min(tx + tw, raw0 + wPx);
				if (x1 <= x0 || !laneRowVisible) {
					continue;
				}
				boolean sel = c == selectedClip && selectedChild < 0;
				int col = sel ? GuiTheme.ACCENT_DARK : GuiTheme.PANEL_HOVER;
				g.fill(x0, blockY0, x1, blockY1, col);
				// 细窄的左右抓取手柄：细竖线，宽度与标尺刻度相同
				g.fill(x0, blockY0, x0 + 1, blockY1, GuiTheme.DE_GREEN);
				g.fill(x1 - 1, blockY0, x1, blockY1, GuiTheme.DE_GREEN);
				if (y >= viewTop) {
					int labelX = x0 + 3;
					if (isGroup(c) && x1 - x0 > GROUP_TOGGLE_X + GROUP_TOGGLE_W + 4) {
						// 色块上的裸 -/+ 字形（无背景框）：点击它折叠/展开
						boolean open = isGroupExpanded(c);
						int tx0 = x0 + GROUP_TOGGLE_X;
						g.text(this.font, open ? "-" : "+", tx0 + 3, y + 3, GuiTheme.DE_GREEN);
						labelX = tx0 + GROUP_TOGGLE_W + 3;
					}
					g.text(this.font, font.plainSubstrByWidth(c.displayName(), Math.max(12, x1 - labelX) - 2),
						labelX, y + 2, GuiTheme.TEXT);
				}
			}
			// 行之间的分隔线
			int sepY = y + BLOCK_H;
			if (sepY >= viewTop && sepY < viewBottom) {
				g.fill(tx, sepY, tx + tw, sepY + 1, 0xFF39444F);
			}
		}

		// 可见区域上方/下方还有更多行时的上/下提示
		int maxLaneScroll = Math.max(0, lanesContentH() - lanesViewH());
		if (maxLaneScroll > 0) {
			if (laneScrollPx > 0) {
				g.text(this.font, "▲", tlX() + tlW() - 16, ty - 12, GuiTheme.SUBTEXT);
			}
			if (laneScrollPx < maxLaneScroll) {
				g.text(this.font, "▼", tlX() + tlW() - 16, ty + TIMELINE_H - 16, GuiTheme.SUBTEXT);
			}
		}

		// 固定在时间线顶部的文本对象轨道，横跨整个时间线长度
		int tty = ty + LANE_TOP;
		int ttx0 = Math.max(tx, tlXFor(0f));
		int ttx1 = Math.min(tx + tw, tlXFor(timelineLen));
		if (ttx1 > ttx0) {
			boolean selected = selectedClip == null; // 没有选中动画条时，文本对象即为“选中”状态
			g.fill(ttx0, tty, ttx1, tty + 12, selected ? 0xFF3A4351 : 0xFF343434);
			g.text(this.font, L10n.tr("anima.ui.type.text"), ttx0 + 2, tty + 2, GuiTheme.TEXT);
			for (int i = 0; i < textKeys.size(); i++) {
				int kx = tlXFor(textKeys.get(i).timeMs);
				if (kx < ttx0 || kx > ttx1) {
					continue;
				}
				boolean sel = i == dragKeyIdx;
				drawDiamond(g, kx, tty + 6, sel ? 4 : 3, sel ? 0xFFFFFFFF : 0xFFE6E6E6);
			}
		}

		// 播放头
		int ph = tlXFor(playTimeMs);
		if (ph >= tx - 4 && ph <= tx + tw + 4) {
			g.fill(ph - 1, ty, ph + 1, ty + TIMELINE_H - 2, GuiTheme.DE_GREEN);
		}

		// 右边缘手柄：颜色与播放头不同，使二者永不被混淆
		int hookX = tx + tw;
		g.fill(hookX - 1, ty, hookX + 1, ty + TIMELINE_H, GuiTheme.WARN);

		// 展开的分组子项绘制在时间线装饰之上（它们绝不能
		// 被标尺 / 播放头 / 文本轨道遮住）
		renderGroupChildren(g, tx, tw, viewTop, viewBottom);
	}

	/** 绘制每个已展开分组的子粒子行，位于时间线其余部分之上。
	 *  每个子项条都从分组色块的左边缘开始，使列表看起来像一棵嵌套树。 */
	private void renderGroupChildren(GuiGraphicsExtractor g, int tx, int tw, int viewTop, int viewBottom) {
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
						// 两个手柄，与普通动画条色块完全相同（拖动它们可移动 / 调整大小）
						g.fill(cx0, ry0, cx0 + 1, ry1, GuiTheme.DE_GREEN);
						g.fill(cx1 - 1, ry0, cx1, ry1, GuiTheme.DE_GREEN);
						if (cy >= viewTop && cy + CHILD_H <= viewBottom) {
							String lbl = (k + 1) + ". " + groupItemId(c, k) + " ×" + groupItemCount(c, k);
							g.text(this.font, font.plainSubstrByWidth(lbl, Math.max(12, cx1 - cx0) - 4),
								cx0 + 3, cy, selChild ? 0xFFFFFFFF : GuiTheme.SUBTEXT);
						}
					}
				}
				childY += n * CHILD_H;
			}
		}
	}

	/** 与 ◆ 关键帧按钮字形一致的小实心菱形。 */
	private static void drawDiamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int w = r - Math.abs(dy);
			g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
		}
	}

	/** 能保持刻度可读的最小“整齐”刻度间隔（100/200/250/500/1000……ms）。 */
	private static int niceStep(double visibleMs) {
		int[] steps = {100, 200, 250, 500, 1000, 2000, 5000, 10000, 20000};
		for (int s : steps) {
			if (s >= visibleMs / 18) {
				return s;
			}
		}
		return 20000;
	}

	/** 按需求以带一位小数的秒为时间线刻度标签（例如 0.5s、1.0s）。 */
	private static String tickLabel(int ms) {
		return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
	}

	/** 淡入淡出锚点：fade_in 在动画条起点，fade_out 在动画条终点，二者都使用固定的短窗口。 */
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
			// 已打出的字符由预览文本驱动（见 renderCenter），而不是由 alpha
			return (t, d, r, b) -> { };
		}
		return engine.createEffect(key, new com.google.gson.JsonObject());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
