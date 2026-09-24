# Anima

![icon](https://cdn.modrinth.com/data/cached_images/97178e824964a51d45a9e945765670510afb81b7.png)

Anima is a client-side animation library for Minecraft 1.21.1. It adds keyframe animation to GUI textures, in-world sprites and text, and comes with an in-game editor where you can drag effects onto a timeline, preview the result live, and export it as JSON.

There are two ways to use it: resource pack authors just write JSON, and mod developers can call the Java API. Fabric and NeoForge are both supported. Everything runs on the client, the server does not need the mod. Licensed under Apache-2.0.

## What it can do

Textures and sprites:

- Play frame sequences, either from a sprite sheet UV region or from a list of individual frame textures
- Layer on fade, blink, pulse, shake, slide and scale. Slide and scale support easing curves
- Works on GUI/HUD and in the world. In-world animation is done by injecting `.png.mcmeta` into the resource stack, so it goes through the vanilla atlas pipeline and stays compatible with Sodium / Iris

Text:

- Presets for typewriter in, per-character fall, random fall, drift in and fade out
- Two layers: HUD text and in-world 3D text. 3D text has real perspective and is occluded by blocks, and each character can be offset, scaled, rotated and faded independently
- Drop shadow works on both layers: HUD text uses the vanilla font shadow, 3D text draws its own, and the shadow is depth-tested so entities and blocks in front occlude it correctly

Particles:

- Emitted by particle registry id, so vanilla particles and particles from other mods both work
- Count, spread and speed follow vanilla `/particle` semantics, and a `/particle` command can be pasted in and parsed automatically

Sprite fonts:

- Slice a character atlas into cells and draw text with it, useful for damage numbers

Particle groups and exported timelines live under `config/anima/` as plain JSON, so you can edit or share them. Animation definitions themselves stay resource-pack JSON (`assets/<modid>/texture_animations/`).

## In-game editor

Press the "Open Animation Editor" key to open / close the timeline editor — it is **unbound by default**, bind it under Options → Controls → Anima. Inside a world it opens as a floating window and the world keeps rendering, so you can tune things while looking at them. There is also a separate "Play / Pause (editor)" key, also unbound by default (Space does the same thing while the editor is focused).

Inside a world the editor's preview is drawn by the real world renderer: 3D text and particle clips are painted in the world itself (with depth, so blocks occlude them). That only happens inside the dedicated sandbox world `anima-config` (superflat, spectator, fixed time, created on first use, never touches your saves). Enter it from code with `AnimaApi.enterConfigWorld(parent)`; `AnimaApi.isConfigWorld()` tells you whether you are already there (there is no button for it in the UI). The library does **not** gate the editors behind it — a mod that wants to require the world writes its own prompt and button.

Other entry points: the config button for "Anima" in Mod Menu (opens the timeline editor), or `AnimaApi.openTimelineEditor(parent)` in code.

### Timeline editor

The window has four parts: an effect and particle palette on the left, a live preview in the middle, a property panel on the right, and a multi-track timeline at the bottom. Drag the title bar to move the palette and the property panel, drag the column of buttons on its left to move the timeline.

The palette is grouped into Enter, Exit and Particle categories that collapse when you click the header. Drag an entry onto the timeline to add a clip. Saved particle groups also show up here as their own category.

The editor has no save button of its own: `AnimaApi.openClipEditor(parent, clips, onSave)` hands the edited clip list back to the caller when the editor closes (persisting it is the caller's job, e.g. from the mod's own config screen). The palette window (top-left) and the property window (top-right) share the same top edge.

Timeline controls:

| Action | Result |
|---|---|
| Drag the middle of a clip | Move it left/right, or up/down between tracks (snaps to avoid overlap) |
| Drag the green handles at either end of a clip | Change its duration |
| Drag on empty timeline space | Move the playhead (seek) |
| Scroll wheel | Scroll through tracks |
| Ctrl + scroll wheel | Zoom the time axis, anchored at the cursor |
| Right-click a clip | Menu: Copy / Create group / Delete (flips up/left near the screen edge so every row stays visible) |
| Space | Play / pause (in floating window mode this is left to Jump, use the play button instead) |
| Delete | Delete the selected entry |
| W/A/S/D, Space, Shift (in floating window mode) | Drive the spectator camera around the preview object |
| Drag the right edge of the timeline | Resize the timeline window |

Enter effects (fade in, typewriter in, fall, random fall, drift in) are locked to the start of the timeline, and exit effects (fade out, typewriter out) are locked to the end, so they cannot be dragged away. The timeline length comes from the text duration and clips never exceed it. Particle groups can be expanded with `-` / `+` into child rows, and each child particle has its own start time and duration that can be dragged by the green handles but cannot go past the group's length.

The property panel switches depending on what is selected:

| Selection | Shown |
|---|---|
| Text object (no clip selected) | Position X/Y/Z, scale, alpha, text duration, distance scaling toggle |
| A clip | Name, speed multiplier, duration, remove |
| A particle | Particle registry name, count, spread X/Y/Z, speed, import command |
| A particle group | Add particle / import command / save group JSON; expanded child particles have their own parameters |

The ◆ buttons next to position, scale and alpha insert a text keyframe at the current playhead, and text properties are interpolated between keyframes. With distance scaling off, the text keeps the same visual size regardless of distance. Import command parses something like `/particle minecraft:flame ~ ~ ~ 0.2 0.2 0.2 0.05 20` into particle name, count, spread and speed. After saving a group JSON it appears in the palette and can be shared with others.

## Resource pack JSON animations

Put the JSON in `assets/<modid>/texture_animations/` inside any resource pack and reload resources with `F3 + T`. Resources shipped with the mod are loaded automatically at startup.

A definition id is `<namespace>:<filename>`. There are four types:

| `type` | Purpose | Key fields |
|---|---|---|
| `gui_sprite` | GUI/HUD texture frame sequence (sprite sheet UV regions) | `texture`, `texture_width/height`, `frames` (UV regions), `frame_time` |
| `gui_dynamic` | Animated texture built from separate frame images | `frame_textures` (list of frames) |
| `world_sprite` | In-world sprite frame sequence | `texture` (sprite id), `frame_indices`, `frame_time`, `interpolate` |
| `text` | Text animation tag, used with `<anim:tag>` / `withTag` | `effects` |

Common fields are `loop` (default true), `play_mode` (`loop` / `once` / `pingpong`) and the `effects` array.

```jsonc
{
  "type": "gui_sprite",
  "texture": "damage-engine:textures/gui/rating/star.png",
  "texture_width": 32,
  "texture_height": 16,
  "frames": [[0, 0, 16, 16], [16, 0, 16, 16]],
  "frame_time": 200,
  "loop": true,
  "effects": [
    { "type": "blink", "period": 600, "min_alpha": 0.2, "max_alpha": 1.0 }
  ]
}
```

Built-in effects (placed in the `effects` array, parameters in milliseconds):

| `type` | Parameters | Description |
|---|---|---|
| `fade` | `fade_in`(200) `hold`(0) `fade_out`(200) `loop`(false) | Fade in, hold, fade out |
| `blink` | `period`(500) `min_alpha`(0.1) `max_alpha`(1.0) | Sine wave blink |
| `pulse` | `period`(800) `min_factor`(0.5) `max_factor`(1.0) `color`[r,g,b] | Periodic color / brightness pulse |
| `shake` | `amplitude`(3) `frequency`(12) `duration`(300) `decay`(false) | Shake |
| `slide` | `from`[x,y] `to`[x,y] `duration`(300) `ease`("linear") `loop`(false) | Offset |
| `scale` | `from`(1) `to`(1) `duration`(300) `ease`("linear") `loop`(false) | Scale |

`ease` values are case sensitive: `linear`, `step`, and `easeIn` / `easeOut` / `easeInOut` combined with `Quad`, `Cubic`, `Quart`, `Quint`, `Sine`, `Expo`, `Circ`, `Back`, `Elastic`, `Bounce`, for example `easeOutCubic` or `easeInOutBounce`. You can also register your own, see below.

## Java API

The entry point is the facade class `anima.api.AnimaApi`. The inner classes `TextAnimations`, `WorldText3D`, `SpriteText` and `WorldParticles` can be used directly as well; `AnimaApi` is just a thin wrapper grouping them by purpose.

### Real 3D world text

```java
// Draw inside a world render callback (the float 1234 floats at x,y,z)
AnimaApi.onWorldRender((pose, buffers, camera, partialTick) ->
    AnimaApi.drawWorldText3D(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, 0.012f, null));
```

For per-character animation (typewriter, fall, drift in), use the `WorldText3D.Glyph[]` overload:

```java
Glyph[] glyphs = ...; // per-character offset / scale / rotation / alpha
AnimaApi.drawWorldText3D(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, 0.02f, glyphs);
```

`worldScale` is how many blocks one GUI pixel maps to, see `WorldText3D.DEFAULT_SCALE`.

### Text animation (HUD)

```java
// Tag style: works on any component automatically (global Font mixin)
guiGraphics.drawString(font, TextAnimations.parse("<anim:blink>Critical!</anim>"), x, y, 0xFFFFFFFF);
TextAnimations.withTag(component, "blink");           // or tag an existing component

// Explicit: control the instance clock / duration yourself (each damage number on its own timer)
TextAnimationSpec spec = AnimaApi.textSpec("damage");
AnimaApi.drawHudText(g, font, Component.literal("42"), x, y, 0xFFFFFFFF, spec, localMs, 1000f);
AnimaApi.drawWorldTextHud(g, font, "42", x, y, z, 0xFFFF5555, spec, localMs, true); // world anchored but drawn on the HUD layer

// Floating text with the caller's own screen-pixel offset (random spread / drift) and its own
// scale / opacity multipliers — projection and animation stay in the library
AnimaApi.drawWorldTextHudOffset(g, font, Component.literal("42"),
    x, y, z, offX, offY, 0xFFFF5555, spec, localMs, 4300f, true, 1.0f, 0.8f);
```

Tags are carried through the `font` field of `Style` (`anima:textanim/<tag>`), so `anima:textanim/*` cannot be used as a real font name. A tag also overwrites the original `font` field, so components using a custom font should use explicit drawing instead.

### Particles

```java
// Vanilla /particle semantics: count is the total, delta is the spread, speed is the velocity multiplier
AnimaApi.emitParticles("minecraft:flame", x, y, z, 20, 0.2, 0.2, 0.2, 0.05);
AnimaApi.isParticleSupported("minecraft:flame"); // returns false for parameterized particles (dust/block etc.)

// Play "particle clips" exported by the editor (particle_3d / particle_group) elsewhere:
// parse once for a reusable template, give every animated instance (each damage number, each
// preview object) its own player, then call emit with that instance's own millisecond clock —
// this is what makes editor particles fire in real use, not only in the preview
ClipParticles clips = AnimaApi.particleClips(exportedClipsJson);
ClipParticles.Player particles = clips.newPlayer();
particles.emit(objectX, objectY, objectZ, localMs); // call every frame; a rewinding clock replays
```

The count is the **total over the whole clip**, emitted gradually as the clip progresses; particles
appear at the object position plus the clip's own `ox/oy/oz` offset and are then simulated by the
vanilla particle engine (they do not follow the text).

### Sprite font text

```java
SpriteText.Atlas digits = AnimaApi.spriteAtlas(
    ResourceLocation.fromNamespaceAndPath("damage-engine", "textures/font/digits.png"),
    8, 12, "0123456789");                            // cut into 8x12 cells, cell i is charset character i
AnimaApi.drawSpriteText(g, digits, "88", 10, 20, 0xFFFFFFFF, modifier);
// Can also be read from JSON: { texture, cell_width, cell_height, columns, charset, scale }
```

### Registering animations / effects / interpolators

```java
AnimaApi.registerEffect("my_effect", json -> new MyEffect());        // factory: JSON config to instance
AnimaApi.registerEffect("my_fixed", myFixedEffect);                  // fixed instance: ignores JSON
AnimaApi.registerInterpolator("my_ease", t -> t * t);                // then use "ease": "my_ease" in JSON
AnimaApi.registerTextTag("my_tag", AnimaApi.specOf(myEffect));       // define a tagged text animation in code

AnimaApi.registerAnimation(
    AnimaApi.animation(id, AnimationType.GUI_SPRITE).build());        // builder registration
AnimaApi.registerAnimation(AnimaApi.animationFromJson(id, json));     // or read resource pack format directly
```

### Drawing defined animated textures

```java
AnimatedTextureManager manager = AnimatedTextureManager.get();
manager.drawAnimated(guiGraphics, id, x, y, w, h);                    // one call, uses the global clock
AnimatedGuiSprite sprite = manager.createGuiInstance(id);             // create an instance for independent timing
sprite.draw(guiGraphics, x, y, w, h, AnimationEngine.get().globalTimeMs());

manager.registerWorldSprite(spriteId,
    new WorldSpriteAnimationSpec(new int[]{0, 1}, 250, false));       // in-world frame sequence, applies after F3+T
```

### Hooks and misc

| Method | Purpose |
|---|---|
| `AnimaApi.onWorldRender(hook)` | World render callback, platform differences handled by the library |
| `AnimaApi.onClientTick(runnable)` | Client tick callback |
| `AnimaApi.onClientReload(id, listener)` | Resource reload (`F3+T`) callback |
| `AnimaApi.projectToScreen(x, y, z)` | World position to GUI screen position, returns `null` behind the camera |
| `AnimaApi.openTimelineEditor(parent)` | The timeline / arrangement editor (this is what the Mod Menu entry opens) |
| `AnimaApi.openClipEditor(parent, clips, onSave)` | Clip editor for a caller-provided clip list; `onSave` receives the clips when the editor closes |
| `AnimaApi.isConfigWorld()` / `enterConfigWorld(parent)` | Query / enter the dedicated preview world (write your own gate prompt) |
| `AnimaApi.exportTimeline(json)` | Export timeline JSON to `config/anima/timelines/` |

## Config files

Everything lives under `config/anima/`:

| Path | Contents |
|---|---|
| `config/anima/animations/` | Animation definitions saved from the editor, overriding resource pack definitions with the same id |
| `config/anima/particle_groups/` | Saved / shared particle group JSON |
| `config/anima/timelines/` | Exported timeline JSON |

Upgrading from the old name `texture-animation-library` migrates existing folders automatically, nothing is lost.

The editor UI ships with English and Chinese (`assets/anima/lang/en_us.json`, `zh_cn.json`). To add another language, drop a `<locale>.json` with the same keys (`anima.ui.*`, `key.anima.editor`, `key.anima.play_pause`, `anima.pack.title`) into a resource pack.

## Compatibility

- Sodium / Iris: the GUI layer is unaffected. In-world frame sequences are done by injecting `.png.mcmeta` into the resource stack and share the vanilla atlas pipeline, so they are compatible
- Pack priority: injected animation metadata sits at the top of the resource stack, so it overrides animation definitions for the same texture from lower priority packs
- Reload: in-world sprite animations take effect after the next resource reload (`F3+T`), same behavior as vanilla animations
- One-shot effects: tag style automatic text only supports looping effects (`blink` / `pulse` / `shake` / looping fade). For one-shot fade in or offset, use the explicit API and create a new instance per trigger
- Server: the library does nothing on the server, so clients only need it when playing on a server

## Building from source

| Target | Command | Output |
|---|---|---|
| Fabric build | `./gradlew build` | `build/libs/` |
| NeoForge build | `cd neoforge && ./gradlew build` | `neoforge/build/libs/` |
| Fabric client | `./gradlew runClient` | — |
| NeoForge client | `cd neoforge && ./gradlew runClient` | — |

The Fabric side also needs Fabric API. Mod Menu is optional.

Source layout: `common/src/main/java` (engine / effects / easing, shared by both loaders), `common/src/client/java` (rendering, API, editor, client only), `src/` (Fabric entry points), `neoforge/src/` (NeoForge entry points).

## License

Apache-2.0

# 中文文档

![icon](https://cdn.modrinth.com/data/cached_images/97178e824964a51d45a9e945765670510afb81b7.png)

Anima 是 Minecraft 1.21.1 的客户端动画库，给 GUI 贴图、世界内精灵和文字加上关键帧动画。它同时带一个游戏内编辑器，可以在里面拖着排特效、实时看效果，再导出成 JSON。

动画有两种用法：资源包作者写 JSON 就能用，模组开发者可以调 Java API。加载器支持 Fabric 和 NeoForge，功能全在客户端，服务端不用装。许可 Apache-2.0。

## 能做什么

贴图和精灵：

- 按帧序列播放，支持精灵表 UV 区，也支持多张独立帧图拼成的动态纹理
- 可叠加淡入淡出、闪烁、脉冲、抖动、位移、缩放，位移和缩放支持缓动曲线
- GUI/HUD 和世界内都能用。世界内那套通过注入 `.png.mcmeta` 实现，走原版 atlas 管线，和 Sodium / Iris 兼容

文字：

- 预设了打字机出场、逐字下落、随机下落、飘入、淡出等编排效果
- 分 HUD 文字和世界内 3D 文字两层。3D 文字有真实透视、会被方块遮挡，每个字可以单独控制位移、缩放、旋转、透明度
- 两层文字都带投影：HUD 文字用原版字体的阴影，3D 文字自己绘制一遍；阴影同样参与深度测试，挡在前面的实体和方块会正确遮住它

粒子：

- 按粒子注册名发射，原版和别的 mod 的粒子都能用
- 数量、散布、速度沿用原版 `/particle` 的语义，指令可以直接粘进去自动解析

贴图拼字：

- 把一张字符图集按格子切开当字用，适合画伤害数字这类内容

粒子组与导出的时间线以普通 JSON 存在 `config/anima/` 下，可以手改、可以发给别人；动画定义本身仍是资源包 JSON（`assets/<modid>/texture_animations/`）。

## 游戏内编辑器

用「打开动画编辑器」按键打开 / 关闭时间线编辑器——它**默认未指定**，请在「选项 → 按键控制 → Anima」里绑定。在世界里它以浮窗形式打开，世界继续渲染，可以边看边调。另有独立的「播放 / 暂停（编辑器）」按键，同样默认未指定（编辑器获得焦点时空格效果相同）。

在世界的浮窗编辑器里，预览由真实世界渲染器绘制：3D 文字和粒子动画条直接画在世界中（有深度，会被方块遮挡）。这套只在专用沙盒世界 `anima-config` 里生效（超平坦、旁观、时间固定、首次使用自动创建，不影响你的存档）：代码里用 `AnimaApi.enterConfigWorld(parent)` 进入，`AnimaApi.isConfigWorld()` 判断当前是否已经在里面（界面上没有进世界的按钮）。本库**不再**给编辑器加门槛——需要强制玩家进世界的模组自己写提示和按钮。

另外还有两个入口：Mod Menu 里点「Anima」的配置按钮（打开编排编辑器），或代码里调用 `AnimaApi.openTimelineEditor(parent)`。

### 时间线编辑器

界面分四块：左边是效果和粒子调色板，中间是实时预览，右边是属性面板，底部是多轨时间线。调色板和属性面板拖标题栏移动，时间线拖它左边那列按钮区域移动。

调色板按入场 / 出场 / 粒子分类折叠，把条目拖到时间线上就能新增一条动画。已保存的粒子组也会作为一个分类出现在这里。

编辑器本身**没有保存按钮**：`AnimaApi.openClipEditor(parent, clips, onSave)` 会在编辑器关闭时把剪辑列表回传给调用方，要不要落盘由调用方决定（例如模组自己的配置界面保存）。调色板窗（左上）和属性窗（右上）顶边对齐。

时间线操作：

| 操作 | 效果 |
|---|---|
| 拖动画条中间 | 左右移动 / 上下换轨（自动吸附，避免重叠） |
| 拖动画条两端的绿条 | 改变时长 |
| 拖时间线空白处 | 拖动播放头（跳时间） |
| 滚轮 | 上下翻看轨道 |
| Ctrl + 滚轮 | 缩放时间轴，以光标处为锚点 |
| 右键动画条 | 菜单：复制 / 创建组 / 删除（贴近屏幕下/右边缘时菜单会自动向上/左翻转，保证每一项都可见） |
| 空格 | 播放 / 暂停（世界浮窗模式下留给「跳跃」，此时用底部播放按钮） |
| Delete | 删除选中条目 |
| W/A/S/D、空格、Shift（世界浮窗模式下） | 驱动旁观相机围着预览对象看 |
| 拖时间线右边缘 | 调整时间线窗口宽度 |

入场类效果（淡入、打字入场、下落、随机下落、飘入）锁定在时间线开头，出场类（淡出、打字出场）锁定在末尾，不能随意拖走。时间线长度由文本时长决定，动画条不会超过它。粒子组可以用 `-` / `+` 展开成子行，每个子粒子有自己的起始时间和时长，能拖左右绿条，但不会越过组的长度。

属性面板跟着选中的东西切换：

| 选中 | 显示 |
|---|---|
| 文本对象（不选任何条） | 位置X/Y/Z、缩放、透明度、文本时长、距离缩放开关 |
| 某个动画条 | 名称、速度×、动画时长、移除 |
| 某个粒子 | 粒子注册名、数量、散布X/Y/Z、速度、导入指令 |
| 粒子组 | ＋添加粒子 / 导入指令 / 保存组 JSON；展开后的子粒子另有自己的参数 |

位置、缩放、透明度旁边的 ◆ 按钮会在当前播放头处打一个文本关键帧，文本属性按关键帧插值。距离缩放关掉之后，远处也保持同样的视觉大小。导入指令把 `/particle minecraft:flame ~ ~ ~ 0.2 0.2 0.2 0.05 20` 这样的指令粘进去，会自动解析成粒子名、数量、散布和速度。保存组 JSON 之后，这个粒子组会出现在调色板里，可以分享给别人。

## 资源包 JSON 动画

把 JSON 放进任意资源包的 `assets/<modid>/texture_animations/`，按 `F3 + T` 重载资源即可。模组自带的资源在启动时自动加载。

定义 ID 是 `<命名空间>:<文件名>`，有四种类型：

| `type` | 作用 | 关键字段 |
|---|---|---|
| `gui_sprite` | GUI/HUD 贴图帧序列（精灵表 UV 区） | `texture`, `texture_width/height`, `frames`(UV 区), `frame_time` |
| `gui_dynamic` | 多张独立帧图拼成的动态纹理 | `frame_textures`(帧图列表) |
| `world_sprite` | 世界内精灵帧序列 | `texture`(精灵 ID), `frame_indices`, `frame_time`, `interpolate` |
| `text` | 文本动画标签，和 `<anim:tag>` / `withTag` 配合 | `effects` |

通用字段有 `loop`（默认 true）、`play_mode`（`loop` / `once` / `pingpong`）和 `effects` 数组。

```jsonc
{
  "type": "gui_sprite",
  "texture": "damage-engine:textures/gui/rating/star.png",
  "texture_width": 32,
  "texture_height": 16,
  "frames": [[0, 0, 16, 16], [16, 0, 16, 16]],
  "frame_time": 200,
  "loop": true,
  "effects": [
    { "type": "blink", "period": 600, "min_alpha": 0.2, "max_alpha": 1.0 }
  ]
}
```

内置特效（写在 `effects` 数组里，参数单位是毫秒）：

| `type` | 参数 | 说明 |
|---|---|---|
| `fade` | `fade_in`(200) `hold`(0) `fade_out`(200) `loop`(false) | 淡入 → 保持 → 淡出 |
| `blink` | `period`(500) `min_alpha`(0.1) `max_alpha`(1.0) | 正弦波周期闪烁 |
| `pulse` | `period`(800) `min_factor`(0.5) `max_factor`(1.0) `color`[r,g,b] | 周期颜色 / 亮度脉冲 |
| `shake` | `amplitude`(3) `frequency`(12) `duration`(300) `decay`(false) | 抖动 / 晃动 |
| `slide` | `from`[x,y] `to`[x,y] `duration`(300) `ease`("linear") `loop`(false) | 位移 |
| `scale` | `from`(1) `to`(1) `duration`(300) `ease`("linear") `loop`(false) | 缩放 |

`ease` 可选值大小写敏感：`linear`、`step`，以及 `easeIn` / `easeOut` / `easeInOut` 搭配 `Quad`、`Cubic`、`Quart`、`Quint`、`Sine`、`Expo`、`Circ`、`Back`、`Elastic`、`Bounce`，例如 `easeOutCubic`、`easeInOutBounce`。也可以自己注册，见下。

## Java API

入口是门面类 `anima.api.AnimaApi`。`TextAnimations`、`WorldText3D`、`SpriteText`、`WorldParticles` 这些内部类也能直接用，`AnimaApi` 只是按用途聚合的薄封装。

### 真 3D 世界文字

```java
// 在世界渲染回调里画（浮点数 1234 悬在 (x,y,z)）
AnimaApi.onWorldRender((pose, buffers, camera, partialTick) ->
    AnimaApi.drawWorldText3D(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, 0.012f, null));
```

需要每个字符独立动画（打字、下落、飘入）时，用 `WorldText3D.Glyph[]` 的重载：

```java
Glyph[] glyphs = ...; // 每个字的位移 / 缩放 / 旋转 / 透明度
AnimaApi.drawWorldText3D(buffers, camera, "1234", x, y, z, 0xFFFFFFFF, 0.02f, glyphs);
```

`worldScale` 是 1 GUI 像素对应多少方块，参考 `WorldText3D.DEFAULT_SCALE`。

### 文本动画（HUD）

```java
// 标签式：任意组件自动生效（Font Mixin 全局拦截）
guiGraphics.drawString(font, TextAnimations.parse("<anim:blink>暴击！</anim>"), x, y, 0xFFFFFFFF);
TextAnimations.withTag(component, "blink");           // 或给已有组件打标签

// 显式：自己控制实例时钟 / 时长（每颗伤害数字单独计时）
TextAnimationSpec spec = AnimaApi.textSpec("damage");
AnimaApi.drawHudText(g, font, Component.literal("42"), x, y, 0xFFFFFFFF, spec, localMs, 1000f);
AnimaApi.drawWorldTextHud(g, font, "42", x, y, z, 0xFFFF5555, spec, localMs, true); // 世界锚定但画在 HUD 层

// 跳字：世界坐标 + 调用方自己算好的屏幕像素偏移（随机散布 / 漂移）+ 自己的缩放 / 透明度倍率，
// 投影与动画仍由库负责
AnimaApi.drawWorldTextHudOffset(g, font, Component.literal("42"),
    x, y, z, offX, offY, 0xFFFF5555, spec, localMs, 4300f, true, 1.0f, 0.8f);
```

标签是通过 `Style` 的 `font` 字段承载标记的（`anima:textanim/<tag>`），所以 `anima:textanim/*` 不能用作真实字体名；标记也会覆盖原来的 `font` 字段，用自定义字体的组件请改用显式绘制。

### 粒子

```java
// 沿用原版 /particle 语义：count 总数，delta = 散布范围，speed = 初速度倍率
AnimaApi.emitParticles("minecraft:flame", x, y, z, 20, 0.2, 0.2, 0.2, 0.05);
AnimaApi.isParticleSupported("minecraft:flame"); // 参数化粒子（dust/block 等）返回 false

// 编辑器导出的「粒子剪辑」（particle_3d / particle_group）在别处播放出来：
// 解析一次拿到模板，每个动画实例（一次跳字、一个预览对象）各自 newPlayer()，
// 再用实例自己的毫秒时钟调用 emit —— 这样编辑器里拖进去的粒子在实际使用时也会发射
ClipParticles clips = AnimaApi.particleClips(exportedClipsJson);
ClipParticles.Player particles = clips.newPlayer();
particles.emit(objectX, objectY, objectZ, localMs); // 每帧调用；时钟回退会自动重放
```

数量是**整条剪辑的总量**、在剪辑窗口内按进度陆续发出；粒子出现在对象位置 + 剪辑自身的偏移 `ox/oy/oz` 上，之后由原版粒子引擎自己模拟（不跟随文本移动）。

### 贴图拼字

```java
SpriteText.Atlas digits = AnimaApi.spriteAtlas(
    ResourceLocation.fromNamespaceAndPath("damage-engine", "textures/font/digits.png"),
    8, 12, "0123456789");                            // 按 8×12 切格，第 i 格 = charset 第 i 个字符
AnimaApi.drawSpriteText(g, digits, "88", 10, 20, 0xFFFFFFFF, modifier);
// 也可以从 JSON 读：{ texture, cell_width, cell_height, columns, charset, scale }
```

### 注册动画 / 特效 / 插值器

```java
AnimaApi.registerEffect("my_effect", json -> new MyEffect());        // 工厂：JSON 配置 → 实例
AnimaApi.registerEffect("my_fixed", myFixedEffect);                  // 固定实例：忽略 JSON
AnimaApi.registerInterpolator("my_ease", t -> t * t);                // 之后 JSON 里可写 "ease": "my_ease"
AnimaApi.registerTextTag("my_tag", AnimaApi.specOf(myEffect));       // 代码里定义标签式文本动画

AnimaApi.registerAnimation(
    AnimaApi.animation(id, AnimationType.GUI_SPRITE).build());        // 构建器注册定义
AnimaApi.registerAnimation(AnimaApi.animationFromJson(id, json));     // 或直接读资源包格式
```

### 绘制已定义的动画贴图

```java
AnimatedTextureManager manager = AnimatedTextureManager.get();
manager.drawAnimated(guiGraphics, id, x, y, w, h);                    // 一行画完（走全局时钟）
AnimatedGuiSprite sprite = manager.createGuiInstance(id);             // 需要独立计时就建实例
sprite.draw(guiGraphics, x, y, w, h, AnimationEngine.get().globalTimeMs());

manager.registerWorldSprite(spriteId,
    new WorldSpriteAnimationSpec(new int[]{0, 1}, 250, false));       // 世界内帧序列，下次 F3+T 生效
```

### 挂钩与杂项

| 方法 | 用途 |
|---|---|
| `AnimaApi.onWorldRender(hook)` | 世界渲染回调，平台差异由库处理 |
| `AnimaApi.onClientTick(runnable)` | 客户端 tick 回调 |
| `AnimaApi.onClientReload(id, listener)` | 资源重载（`F3+T`）回调 |
| `AnimaApi.projectToScreen(x, y, z)` | 世界坐标 → GUI 屏幕坐标，相机背后返回 `null` |
| `AnimaApi.openTimelineEditor(parent)` | 编排 / 时间线编辑器（Mod Menu 入口用的就是它） |
| `AnimaApi.openClipEditor(parent, clips, onSave)` | 剪辑编辑器：载入调用方给的剪辑，关闭时把结果回传 |
| `AnimaApi.isConfigWorld()` / `enterConfigWorld(parent)` | 判断 / 进入专用预览世界（门槛提示自己写） |
| `AnimaApi.exportTimeline(json)` | 时间线 JSON 导出到 `config/anima/timelines/` |

## 配置文件

都在 `config/anima/` 下：

| 路径 | 内容 |
|---|---|
| `config/anima/animations/` | 从编辑器保存的动画定义，覆盖资源包同 id 定义 |
| `config/anima/particle_groups/` | 保存 / 分享的粒子组 JSON |
| `config/anima/timelines/` | 导出的时间线 JSON |

从旧名字 `texture-animation-library` 升级时会自动迁移已有目录，配置不会丢。

编辑器界面自带中英双语（`assets/anima/lang/zh_cn.json`、`en_us.json`）。想加别的语言，在资源包里放一份同 key（`anima.ui.*`、`key.anima.editor`、`key.anima.play_pause`、`anima.pack.title`）的 `<locale>.json` 就能覆盖。

## 兼容性

- Sodium / Iris：GUI 层不受影响。世界内帧序列是往资源栈注入 `.png.mcmeta` 实现的，和原版共用 atlas 管线，所以兼容
- 包优先级：注入的动画元数据在资源栈顶层，会覆盖低优先级资源包对同一纹理的动画定义
- 重载：世界内精灵动画在下次资源重载（`F3+T`）后生效，和原版动画行为一致
- 一次性特效：标签式自动文本只支持循环型特效（`blink` / `pulse` / `shake` / 循环淡入淡出）。一次性的淡入、位移请用显式 API，每次触发新建实例
- 服务端：本库不在服务端做任何事，联机时只需要客户端安装

## 从源码构建

| 目标 | 命令 | 产物 |
|---|---|---|
| Fabric 构建 | `./gradlew build` | `build/libs/` |
| NeoForge 构建 | `cd neoforge && ./gradlew build` | `neoforge/build/libs/` |
| Fabric 跑客户端 | `./gradlew runClient` | — |
| NeoForge 跑客户端 | `cd neoforge && ./gradlew runClient` | — |

Fabric 端还需要 Fabric API，Mod Menu 可选。

源码结构：`common/src/main/java`（引擎 / 特效 / 缓动，双端共用）、`common/src/client/java`（渲染、API、编辑器，仅客户端）、`src/`（Fabric 入口）、`neoforge/src/`（NeoForge 入口）。

## License

Apache-2.0
