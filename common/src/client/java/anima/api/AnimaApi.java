package anima.api;

import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import anima.Anima;
import anima.client.gui.ClipEditorResult;
import anima.client.gui.CompositeEditScreen;
import anima.client.world.ClipParticles;
import anima.client.world.TextSpread;
import anima.client.world.WorldParticles;
import anima.client.world.WorldProjection;
import anima.client.world.WorldText;
import anima.client.world.WorldText3D;
import anima.engine.AnimationEngine;
import anima.engine.RenderModifier;
import anima.manager.AnimatedTextureManager;
import anima.manager.AnimationConfigStore;
import anima.manager.AnimationDefinition;
import anima.manager.AnimationType;
import anima.platform.PlatformHooks;
import anima.platform.WorldRenderHook;
import anima.text.AnimatedTextStyle;
import anima.text.CharClips;
import anima.text.SpriteText;
import anima.text.TextAnimationSpec;
import anima.text.TextAnimations;

/**
 * <b>Anima 的对外 API 门面</b>——给其它 mod 开发者使用的一站式入口。库内部还有更细的类
 * （{@link TextAnimations}、{@link WorldText3D}、{@link SpriteText}、{@link WorldParticles} …），
 * 这里把它们按用途聚合，方法都是薄封装，不改变语义。
 *
 * <h2>常见用法</h2>
 * <pre>{@code
 * // 1) 真 3D 世界文字（有透视、会被方块遮挡）——在世界渲染回调里画
 * PlatformHooks.get().addWorldRenderListener((pose, buffers, camera, partialTick) -> {
 *     AnimaApi.drawWorldText3D(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, 0.012f, null);
 * });
 *
 * // 2) 伤害数字（跳字）：借用已定义的动画规格 + 自己的时钟 / 时长 + 自己算的像素偏移
 * TextAnimationSpec spec = AnimaApi.textSpec("damage");
 * AnimaApi.onWorldRender((pose, buffers, camera, partialTick) ->
 *     AnimaApi.drawWorldText3D(buffers, camera, Component.literal("42"), x, y, z,
 *         offsetX, offsetY, 0xFFFFFFFF, spec, localMs, durationMs,
 *         WorldText3D.DEFAULT_SCALE, 1f, 1f));
 *
 * // 3) 粒子（原版 /particle 语义）
 * AnimaApi.emitParticles("minecraft:flame", x, y, z, 20, 0.2, 0.2, 0.2, 0.05);
 *
 * // 4) 用自定义贴图拼数字（纹理学）
 * SpriteText.Atlas digits = AnimaApi.spriteAtlas(texture, 8, 12, "0123456789");
 * AnimaApi.drawSpriteText(g, digits, "88", 10, 20, 0xFFFFFFFF, modifier);
 * }</pre>
 *
 * <p>本类只做转发；要注册新特效 / 新动画 / 新插值器请用 {@link #registerEffect} 等方法，
 * 或直接访问 {@link AnimationEngine#get()} 与 {@link AnimatedTextureManager#get()}。
 */
public final class AnimaApi {

	private AnimaApi() {
	}

	// ------------------------------------------------------------------ 身份

	/** 本库的命名空间（{@code anima}）。 */
	public static String modId() {
		return Anima.MOD_ID;
	}

	/** 在本库命名空间下创建 {@link ResourceLocation}。 */
	public static ResourceLocation id(String path) {
		return Anima.id(path);
	}

	// ------------------------------------------------------------------ 3D / 世界文字

	/**
	 * 在世界里画<b>真 3D 文字</b>（billboard，有透视、会被方块遮挡）。
	 *
	 * @param worldScale 1 GUI 像素 = 多少个方块（参考 {@link WorldText3D#DEFAULT_SCALE}）
	 * @param m          位移 / 缩放 / 透明度（可为 null）
	 */
	public static boolean drawWorldText3D(MultiBufferSource buffers, Camera camera, String text,
			double x, double y, double z, int color, float worldScale, RenderModifier m) {
		return WorldText3D.draw(buffers, camera, text, x, y, z, color, worldScale, m);
	}

	/** 真 3D 文字 + 逐字动画（打字/下落/飘入等），每个字的位移/缩放/旋转/透明度单独给。 */
	public static boolean drawWorldText3D(MultiBufferSource buffers, Camera camera, String text,
			double x, double y, double z, int color, float worldScale, WorldText3D.Glyph[] glyphs) {
		return WorldText3D.draw(buffers, camera, text, x, y, z, color, worldScale, 0f, 0f, 1f, glyphs, 1f);
	}

	/**
	 * 世界里的<b>真 3D 跳字</b>：世界坐标 + 屏幕像素偏移 + 缩放 / 透明度倍率，动画由 {@code spec}
	 * 在 {@code localMs} 处按剪辑时长 {@code durationMs} 求值——给「自己算随机偏移」的跳字
	 * （如 Damage-Engine）用。与 {@link #drawWorldTextHudOffset} 的区别是它真的画在<b>世界里</b>
	 * （有透视、随距离缩放、会被方块遮挡），并且支持 {@link Component}（粗体等样式保留）。
	 *
	 * @param offsetX/Y  屏幕像素偏移（随机环半径 / 漂移等）
	 * @param worldScale 1 GUI 像素 = 多少个方块（参考 {@link WorldText3D#DEFAULT_SCALE}）
	 * @param scaleMul   调用方额外缩放（如伤害大小微调）
	 * @param alphaMul   调用方额外透明度（0-1）
	 * @return 是否真的画了东西（空文本 / 完全透明时为 false）
	 */
	public static boolean drawWorldText3D(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, float offsetX, float offsetY, int color,
			TextAnimationSpec spec, float localMs, float durationMs, float worldScale,
			float scaleMul, float alphaMul) {
		return drawWorldText3D(buffers, camera, text, x, y, z, offsetX, offsetY, color, spec,
			localMs, durationMs, worldScale, scaleMul, alphaMul, null);
	}

	/**
	 * 与上一个重载相同，但额外接受<b>逐字变换</b>（{@link #charGlyphs} 的返回值）：每个字可以有
	 * 自己的位移 / 缩放 / 旋转 / 透明度（打字入场、打字出场、下落、飘入等）。
	 */
	public static boolean drawWorldText3D(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, float offsetX, float offsetY, int color,
			TextAnimationSpec spec, float localMs, float durationMs, float worldScale,
			float scaleMul, float alphaMul, WorldText3D.Glyph[] glyphs) {
		return drawWorldText3D(buffers, camera, text, x, y, z, offsetX, offsetY, color, spec,
			localMs, durationMs, worldScale, scaleMul, alphaMul, glyphs, false);
	}

	/**
	 * 与上一个重载相同，但可以要求<b>穿透显示</b>：{@code seeThrough = true} 时用 see-through
	 * 文字渲染类型（不做深度测试），<b>不会被方块 / 实体遮挡</b>——伤害跳字这类不想被生物模型
	 * 挡住的东西用它。
	 */
	public static boolean drawWorldText3D(MultiBufferSource buffers, Camera camera, Component text,
			double x, double y, double z, float offsetX, float offsetY, int color,
			TextAnimationSpec spec, float localMs, float durationMs, float worldScale,
			float scaleMul, float alphaMul, WorldText3D.Glyph[] glyphs, boolean seeThrough) {
		RenderModifier anim = spec == null ? null : spec.computeModifier(localMs, durationMs);
		float offX = offsetX;
		float offY = offsetY;
		float scale = scaleMul;
		float alpha = alphaMul;
		int rgb = color;
		if (anim != null) {
			offX += anim.tx;
			offY += anim.ty;
			scale *= anim.sx;
			alpha *= anim.a;
			rgb = ((color >>> 24) << 24)
				| (Math.round(clamp01(anim.r) * ((color >> 16) & 0xFF)) << 16)
				| (Math.round(clamp01(anim.g) * ((color >> 8) & 0xFF)) << 8)
				| Math.round(clamp01(anim.b) * (color & 0xFF));
		}
		return WorldText3D.draw(buffers, camera, text, x, y, z, rgb, worldScale, offX, offY, scale, glyphs, alpha, seeThrough);
	}

	/**
	 * 逐字剪辑求值：返回 {@code text} 在 {@code timeMs} 时刻的逐字变换（没有活跃剪辑时返回
	 * {@code null}，此时按整段绘制即可）。剪辑列表可用 {@link CharClips.Clip} 直接构造，或由
	 * 世界内编辑器编辑后回传。
	 */
	public static WorldText3D.Glyph[] charGlyphs(String text, List<CharClips.Clip> clips, float timeMs) {
		return CharClips.compute(text, clips, timeMs);
	}

	/**
	 * 扩散 / 漂移的像素偏移：调用方只给<b>随机值</b>（方向、起始半径、漂移距离），曲线的求值
	 * 交给库（缓出：先快后慢，{@code durationMs} 处停住）。跳字这类「命中点周围随机撒开 +
	 * 向外漂移」用它，不需要自己算缓动。
	 */
	public static TextSpread.Offset spreadOffset(float dirX, float dirY, float radius, float drift,
			float timeMs, float durationMs) {
		return TextSpread.compute(dirX, dirY, radius, drift, timeMs, durationMs);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/**
	 * 世界锚定的文字，但<b>画在 2D HUD 层</b>（把世界坐标投影到屏幕，大小不随距离变化）。
	 * 适合近距离、需要固定像素大小的场合；要真实透视与遮挡请用 {@link #drawWorldText3D}。
	 */
	public static boolean drawWorldTextHud(GuiGraphics g, Font font, String text,
			double x, double y, double z, int color, TextAnimationSpec spec, float localMs, boolean shadow) {
		return WorldText.draw(g, font, text, x, y, z, color, spec, localMs, shadow);
	}

	/** HUD 上的动画文字（在 GUI 渲染阶段调用），带自己的时钟与时长。 */
	public static int drawHudText(GuiGraphics g, Font font, Component text, int x, int y, int color,
			TextAnimationSpec spec, float localMs, float durationMs) {
		return TextAnimations.draw(g, font, text, x, y, color, spec, localMs, durationMs);
	}

	/**
	 * 世界锚定的动画文字 + <b>屏幕像素偏移</b> + 缩放 / 透明度倍率——给「随机散布 + 自己算偏移」的
	 * 跳字（如 Damage-Engine）用：调用方只给世界坐标与像素偏移，投影与动画由本库完成。
	 *
	 * @param text       支持 {@link Component}（粗体等样式保留）
	 * @param offsetX/Y  屏幕像素偏移（随机环半径 / 漂移等）
	 * @param spec       动画规格（可用 {@link #textSpec} 取标签式定义），可为 null
	 * @param localMs    本实例自己的时钟（ms），{@code durationMs} 为剪辑时长（ms）
	 * @param scaleMul   调用方额外缩放（如配置里的字号倍率）
	 * @param alphaMul   调用方额外透明度（0-1）
	 * @return 世界坐标在相机背后或完全透明时返回 false
	 */
	public static boolean drawWorldTextHudOffset(GuiGraphics g, Font font, Component text,
			double x, double y, double z, float offsetX, float offsetY, int color,
			TextAnimationSpec spec, float localMs, float durationMs, boolean shadow,
			float scaleMul, float alphaMul) {
		return WorldText.drawAnchored(g, font, text, x, y, z, offsetX, offsetY, color, spec,
			localMs, durationMs, shadow, scaleMul, alphaMul);
	}

	/** 世界坐标 → 屏幕坐标（GUI 缩放坐标），相机背后返回 null。 */
	public static float[] projectToScreen(double x, double y, double z) {
		return WorldProjection.project(x, y, z);
	}

	// ------------------------------------------------------------------ 动画规格 / 特效

	/** 取一个已注册的文本动画规格（标签式定义，例如 {@code "damage"}），不存在返回 null。 */
	public static TextAnimationSpec textSpec(String tag) {
		return AnimatedTextStyle.get(tag);
	}

	/** 用若干特效拼一个规格（想临时组合时用）。 */
	public static TextAnimationSpec specOf(IAnimationEffect... effects) {
		return new TextAnimationSpec(List.of(effects));
	}

	/** 注册/替换一个标签式文本动画（可在资源包 JSON 之外用代码定义）。 */
	public static void registerTextTag(String tag, TextAnimationSpec spec) {
		TextAnimations.registerTag(tag, spec);
	}

	/** 注册一个特效工厂（JSON 配置 → 特效实例），标签式与 JSON 定义都能用它。 */
	public static void registerEffect(String name, IAnimationEffect.Factory factory) {
		AnimationEngine.get().registerEffect(name, factory);
	}

	/** 注册一个固定特效实例（忽略 JSON 配置）。 */
	public static void registerEffect(String name, IAnimationEffect effect) {
		AnimationEngine.get().registerEffectInstance(name, effect);
	}

	/** 用名字 + JSON 配置创建特效实例（不存在返回 null）。 */
	public static IAnimationEffect createEffect(String name, JsonObject config) {
		return AnimationEngine.get().createEffect(name, config == null ? new JsonObject() : config);
	}

	/** 注册一个自定义插值器（缓动曲线），名字可被 {@code ease} 字段引用。 */
	public static void registerInterpolator(String name, Interpolator interpolator) {
		AnimationEngine.get().registerInterpolator(name, interpolator);
	}

	/** 用构建器注册一个动画定义（GUI 精灵 / 世界精灵 / 文本）。 */
	public static AnimationDefinition.Builder animation(ResourceLocation id, AnimationType type) {
		return AnimationDefinition.builder(id, type);
	}

	/** 注册（或替换）一个动画定义。 */
	public static void registerAnimation(AnimationDefinition definition) {
		AnimatedTextureManager.get().registerDefinition(definition);
	}

	/** 从 JSON 读一个动画定义（资源包格式）。 */
	public static AnimationDefinition animationFromJson(ResourceLocation id, JsonObject json) {
		return AnimationDefinition.fromJson(id, json);
	}

	// ------------------------------------------------------------------ 粒子

	/** 发射真实 3D 粒子（原版 {@code /particle} 语义：delta = 散布，speed = 初速度倍率）。 */
	public static int emitParticles(String registryId, double x, double y, double z, int count,
			double deltaX, double deltaY, double deltaZ, double speed) {
		return WorldParticles.emit(registryId, x, y, z, count, deltaX, deltaY, deltaZ, speed);
	}

	/** 该粒子注册名能否被发射（参数化粒子如 dust/block 不支持，会返回 false）。 */
	public static boolean isParticleSupported(String registryId) {
		return WorldParticles.optionsOf(registryId) != null;
	}

	/**
	 * 解析编辑器导出的「粒子剪辑」JSON（{@code particle_3d} / {@code particle_group}），
	 * 得到可复用的 {@link ClipParticles} 模板：每个动画实例用 {@code newPlayer()} 建一个播放器，
	 * 再用同一个毫秒时钟调用 {@code emit(x, y, z, timeMs)} 就会按剪辑的时间发射真实 3D 粒子。
	 * <p>
	 * 这样编辑器里拖进来的粒子在<b>实际使用</b>时也会出现（不只是预览）。
	 */
	public static ClipParticles particleClips(JsonArray clips) {
		return ClipParticles.parse(clips);
	}

	// ------------------------------------------------------------------ 纹理文字（贴图拼数字）

	/** 造一个字形图集：贴图按 {@code cellW×cellH} 切格，{@code charset} 第 i 个字符用第 i 格。 */
	public static SpriteText.Atlas spriteAtlas(ResourceLocation texture, int cellW, int cellH, String charset) {
		return SpriteText.Atlas.of(texture, cellW, cellH, charset);
	}

	/** 从 JSON 读图集：{@code {texture, cell_width, cell_height, columns, charset, scale}}。 */
	public static SpriteText.Atlas spriteAtlasFromJson(JsonObject json) {
		return SpriteText.Atlas.fromJson(json);
	}

	/** 用图集拼字符串（纹理学数字），可套用动画修饰符。 */
	public static void drawSpriteText(GuiGraphics g, SpriteText.Atlas atlas, String text, float x, float y,
			int color, RenderModifier modifier) {
		SpriteText.draw(g, atlas, text, x, y, color, modifier);
	}

	// ------------------------------------------------------------------ 挂钩 / 界面

	/**
	 * 平台实现就绪<b>之前</b>注册的 hook 先排在这里：别的 mod 的入口可能比本库先跑，
	 * 那时 {@link PlatformHooks#get()} 还是 null，直接注册会被静默丢掉。就绪后由
	 * {@link #flushPendingHooks()} 补注册（{@link PlatformHooks#set} 会调用它）。
	 */
	private static final java.util.List<WorldRenderHook> pendingWorldHooks = new java.util.ArrayList<>();
	private static final java.util.List<Runnable> pendingTickHooks = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> pendingReloadListeners = new java.util.ArrayList<>();

	/** 注册一个世界渲染回调（世界空间绘制；平台差异由库处理）。 */
	public static void onWorldRender(WorldRenderHook hook) {
		if (hook == null) {
			return;
		}
		if (PlatformHooks.get() != null) {
			PlatformHooks.get().addWorldRenderListener(hook);
		} else {
			pendingWorldHooks.add(hook);
			Anima.LOGGER.warn("AnimaApi.onWorldRender: platform hooks not ready yet — queued (registered once Anima initialises)");
		}
	}

	/** 注册一个客户端 tick 回调（每 tick 末尾）。 */
	public static void onClientTick(Runnable runnable) {
		if (runnable == null) {
			return;
		}
		if (PlatformHooks.get() != null) {
			PlatformHooks.get().addClientTickListener(runnable);
		} else {
			pendingTickHooks.add(runnable);
		}
	}

	/** 客户端资源重载监听（{@code F3+T} 后触发）。 */
	public static void onClientReload(ResourceLocation id, net.minecraft.server.packs.resources.PreparableReloadListener listener) {
		if (id == null || listener == null) {
			return;
		}
		if (PlatformHooks.get() != null) {
			PlatformHooks.get().registerClientReloadListener(id, listener);
		} else {
			pendingReloadListeners.add(new Object[] { id, listener });
		}
	}

	/** 由 {@link PlatformHooks#set} 调用：把排队中的 hook 真正注册上。 */
	public static void flushPendingHooks() {
		if (PlatformHooks.get() == null) {
			return;
		}
		for (WorldRenderHook h : pendingWorldHooks) {
			PlatformHooks.get().addWorldRenderListener(h);
		}
		for (Runnable r : pendingTickHooks) {
			PlatformHooks.get().addClientTickListener(r);
		}
		for (Object[] e : pendingReloadListeners) {
			PlatformHooks.get().registerClientReloadListener((ResourceLocation) e[0],
				(net.minecraft.server.packs.resources.PreparableReloadListener) e[1]);
		}
		int n = pendingWorldHooks.size() + pendingTickHooks.size() + pendingReloadListeners.size();
		pendingWorldHooks.clear();
		pendingTickHooks.clear();
		pendingReloadListeners.clear();
		if (n > 0) {
			Anima.LOGGER.info("AnimaApi: registered {} hook(s) that were queued before platform init", n);
		}
	}

	/** 当前是否在专用配置世界内（只有世界内才有真实渲染的预览）。 */
	public static boolean isConfigWorld() {
		return anima.client.gui.ConfigWorldLauncher.isConfigWorld();
	}

	/**
	 * 进入（不存在则创建）本库的专用配置世界，用于真实渲染的预览。
	 * <p>
	 * 其他 mod 想自己写「请先进入专用世界」的门槛时用它：不在世界内时由调用方给出提示，
	 * 玩家确认后再调这里进来。
	 */
	public static void enterConfigWorld(Screen parent) {
		anima.client.gui.ConfigWorldLauncher.launch(parent);
	}

	/** 打开「动画效果编排 / 时间线」界面（{@code parent} 可为 null）。 */
	public static void openTimelineEditor(Screen parent) {
		Minecraft.getInstance().setScreen(CompositeEditScreen.configScreenFactory(parent));
	}

	/**
	 * 打开<b>世界内编辑器</b>：浮动窗口形态，世界继续渲染，可以直接在场景里编辑 / 预览
	 * 世界锚定的动画对象（与「打开动画编辑器」按键 / Mod Menu 的入口一致）。玩家不在世界里时退化为普通面板。
	 * {@code parent} 可为 null。
	 */
	public static void openWorldEditor(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new CompositeEditScreen(parent, mc.level != null));
	}

	/**
	 * 打开<b>世界内剪辑编辑器</b>：以 {@code clips}（{@code [{"effect","start","duration","speed"}]}）、
	 * {@code textProps}（文本对象属性，可为 {@code null}）与 {@code previewText}（预览里显示的示例文本，
	 * 可为 {@code null} = 用库自己的示例）为初始内容；编辑器关闭时把结果
	 * （{@link ClipEditorResult}：剪辑 + 文本属性）回传给 {@code onSave}——给「默认值写在调用方，
	 * 玩家可自行调整」的用法（如 Damage-Engine 的跳字）。编辑器没有保存按钮，落盘由调用方决定。
	 */
	public static void openClipEditor(Screen parent, JsonArray clips, JsonObject textProps, String previewText,
			Consumer<ClipEditorResult> onSave) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new CompositeEditScreen(parent, mc.level != null, clips, textProps, previewText, onSave));
	}

	/** 文本对象属性的字段顺序：位置 X/Y/Z、缩放、透明度。 */
	private static final String[] TEXT_PROP_KEYS = { "x", "y", "z", "scale", "alpha" };
	private static final float[] TEXT_PROP_DEFAULTS = { 0f, 0f, 0f, 1f, 1f };

	/**
	 * 求值文本对象属性（{@link ClipEditorResult#textProps()}）在 {@code localMs} 时刻的值。
	 *
	 * @return {@code {dx, dy, dz, scale, alpha}}：前三个是<b>方块</b>位移（直接加到文字的世界坐标上），
	 *         后两个是缩放 / 透明度倍率。{@code props} 为 null / 无关键帧时返回基准值。
	 */
	public static float[] evalTextProps(JsonObject props, float localMs) {
		float[] out = TEXT_PROP_DEFAULTS.clone();
		if (props == null) {
			return out;
		}
		for (int p = 0; p < TEXT_PROP_KEYS.length; p++) {
			if (props.has(TEXT_PROP_KEYS[p])) {
				out[p] = props.get(TEXT_PROP_KEYS[p]).getAsFloat();
			}
		}
		if (!props.has("keys") || !props.get("keys").isJsonArray()) {
			return out;
		}
		JsonArray arr = props.getAsJsonArray("keys");
		float[] times = new float[arr.size()];
		String[] eases = new String[arr.size()];
		float[][] vals = new float[arr.size()][TEXT_PROP_KEYS.length];
		int n = 0;
		for (com.google.gson.JsonElement el : arr) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject k = el.getAsJsonObject();
			if (!k.has("v") || !k.get("v").isJsonArray() || k.getAsJsonArray("v").size() < TEXT_PROP_KEYS.length) {
				continue;
			}
			times[n] = k.has("t") ? k.get("t").getAsFloat() : 0f;
			eases[n] = k.has("ease") ? k.get("ease").getAsString() : "linear";
			JsonArray v = k.getAsJsonArray("v");
			for (int p = 0; p < TEXT_PROP_KEYS.length; p++) {
				vals[n][p] = v.get(p).getAsFloat();
			}
			n++;
		}
		if (n == 0) {
			return out;
		}
		if (localMs <= times[0]) {
			return vals[0].clone();
		}
		if (localMs >= times[n - 1]) {
			return vals[n - 1].clone();
		}
		for (int i = 1; i < n; i++) {
			if (localMs <= times[i]) {
				float span = Math.max(1f, times[i] - times[i - 1]);
				float t = (localMs - times[i - 1]) / span;
				float e = anima.api.Easing.ALL.getOrDefault(eases[i], anima.api.Easing.LINEAR).applyClamped(t);
				float[] res = new float[TEXT_PROP_KEYS.length];
				for (int p = 0; p < TEXT_PROP_KEYS.length; p++) {
					res[p] = vals[i - 1][p] + (vals[i][p] - vals[i - 1][p]) * e;
				}
				return res;
			}
		}
		return out;
	}

	/** 把时间线 JSON 导出到 {@code config/anima/timelines/}，返回写入路径。 */
	public static String exportTimeline(JsonObject timelineJson) {
		return AnimationConfigStore.get().exportTimeline(timelineJson);
	}
}
