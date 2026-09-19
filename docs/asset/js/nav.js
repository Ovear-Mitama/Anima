/* ============================================================
   文档站 - 站点配置
   siteTitle : 左上角站点标题(标题右侧那个下拉用来切换 MOD)
   mods      : 站内收录的模组;下拉里就是它,点一项跳到那个模组的首页
   nav       : 每个页面自己的侧栏;key = 相对 docs 的路径
               group/items 上的 key 是 i18n 词条名(见 asset/js/i18n.js),
               label 是词条缺失时的中文兜底文字。
   新增一个模组:把它的页面放进一个同名子目录,然后在 mods 追加一项,
                再在 nav 里给它的每个页面加一份配置。
   ============================================================ */
window.ANIMA_DOCS = {
  siteTitle: '模组文档',

  mods: [
    { id: 'anima', label: 'Anima｜赋灵', home: 'anima/index.html', hint: '动画库 · 动画工作台' },
    { id: 'damage-engine', label: 'Damage Engine', home: 'damage-engine/index.html', hint: '战斗评分 · 扩展 API' }
  ],

  nav: {
    /* ---------- Anima｜赋灵 ---------- */
    'anima/index.html': [
      {
        group: '文档',
        key: 'nv.group.docs',
        items: [
          { href: 'index.html', label: '快速开始', key: 'nv.page.index' },
          { href: 'editor.html', label: '编辑器使用教程', key: 'nv.page.editor' },
          { href: 'json.html', label: '资源包 JSON 动画', key: 'nv.page.json' },
          { href: 'api.html', label: 'Java API 教程', key: 'nv.page.api' },
          { href: 'compat.html', label: '兼容性与 FAQ', key: 'nv.page.compat' }
        ]
      },
      {
        group: '本页',
        key: 'nv.group.this',
        items: [
          { href: '#overview', label: '这是什么', key: 'nv.overview' },
          { href: '#features', label: '能做什么', key: 'nv.features' },
          { href: '#structure', label: '源码结构', key: 'nv.structure' },
          { href: '#install', label: '安装', key: 'nv.install' },
          { href: '#build', label: '从源码构建', key: 'nv.build' },
          { href: '#run', label: '开发运行', key: 'nv.run' },
          { href: '#open', label: '打开编辑器', key: 'nv.open' },
          { href: '#first', label: '第一条动画', key: 'nv.first' },
          { href: '#next', label: '接下来看什么', key: 'nv.next' }
        ]
      }
    ],

    'anima/editor.html': [
      {
        group: '文档',
        key: 'nv.group.docs',
        items: [
          { href: 'index.html', label: '快速开始', key: 'nv.page.index' },
          { href: 'editor.html', label: '编辑器使用教程', key: 'nv.page.editor' },
          { href: 'json.html', label: '资源包 JSON 动画', key: 'nv.page.json' },
          { href: 'api.html', label: 'Java API 教程', key: 'nv.page.api' },
          { href: 'compat.html', label: '兼容性与 FAQ', key: 'nv.page.compat' }
        ]
      },
      {
        group: '本页',
        key: 'nv.group.this',
        items: [
          { href: '#entry', label: '打开方式', key: 'nv.entry' },
          { href: '#configworld', label: '配置世界', key: 'nv.configworld' },
          { href: '#layout', label: '界面布局', key: 'nv.layout' },
          { href: '#palette', label: '效果/粒子 调色板', key: 'nv.palette' },
          { href: '#timeline', label: '时间线', key: 'nv.timeline' },
          { href: '#props', label: '属性面板', key: 'nv.props' },
          { href: '#keys', label: '文本关键帧', key: 'nv.keys' },
          { href: '#distscale', label: '距离缩放', key: 'nv.distscale' },
          { href: '#particle', label: '单个粒子', key: 'nv.particle' },
          { href: '#cmd', label: '指令导入', key: 'nv.cmd' },
          { href: '#group', label: '粒子组', key: 'nv.group' },
          { href: '#child', label: '组内子粒子', key: 'nv.child' },
          { href: '#animeditor', label: '动画编辑器', key: 'nv.anim_editor' },
          { href: '#shortcuts', label: '快捷键速查', key: 'nv.shortcuts' },
          { href: '#storage', label: '保存到哪里', key: 'nv.storage' }
        ]
      }
    ],

    'anima/json.html': [
      {
        group: '文档',
        key: 'nv.group.docs',
        items: [
          { href: 'index.html', label: '快速开始', key: 'nv.page.index' },
          { href: 'editor.html', label: '编辑器使用教程', key: 'nv.page.editor' },
          { href: 'json.html', label: '资源包 JSON 动画', key: 'nv.page.json' },
          { href: 'api.html', label: 'Java API 教程', key: 'nv.page.api' },
          { href: 'compat.html', label: '兼容性与 FAQ', key: 'nv.page.compat' }
        ]
      },
      {
        group: '本页',
        key: 'nv.group.this',
        items: [
          { href: '#where', label: '放在哪里', key: 'nv.where' },
          { href: '#types', label: '四种类型', key: 'nv.types' },
          { href: '#sample', label: '完整示例', key: 'nv.sample' },
          { href: '#common', label: '通用字段', key: 'nv.common' },
          { href: '#effects', label: '内置特效', key: 'nv.effects' },
          { href: '#easing', label: '缓动曲线', key: 'nv.easing' },
          { href: '#text', label: '文本动画标签', key: 'nv.text' },
          { href: '#notes', label: '注意事项', key: 'nv.notes' }
        ]
      }
    ],

    'anima/api.html': [
      {
        group: '文档',
        key: 'nv.group.docs',
        items: [
          { href: 'index.html', label: '快速开始', key: 'nv.page.index' },
          { href: 'editor.html', label: '编辑器使用教程', key: 'nv.page.editor' },
          { href: 'json.html', label: '资源包 JSON 动画', key: 'nv.page.json' },
          { href: 'api.html', label: 'Java API 教程', key: 'nv.page.api' },
          { href: 'compat.html', label: '兼容性与 FAQ', key: 'nv.page.compat' }
        ]
      },
      {
        group: '本页',
        key: 'nv.group.this',
        items: [
          { href: '#setup', label: '引入与依赖', key: 'nv.setup' },
          { href: '#facade', label: 'AnimaApi 一览', key: 'nv.facade' },
          { href: '#world3d', label: '真 3D 世界文字', key: 'nv.world3d' },
          { href: '#glyph', label: '逐字动画', key: 'nv.glyph' },
          { href: '#hud', label: 'HUD 文本动画', key: 'nv.hud' },
          { href: '#particles', label: '粒子', key: 'nv.particles' },
          { href: '#sprite', label: '贴图拼字', key: 'nv.sprite' },
          { href: '#texture', label: '绘制动画贴图', key: 'nv.texture' },
          { href: '#register', label: '注册特效 / 缓动 / 动画', key: 'nv.register' },
          { href: '#hooks', label: '挂钩与工具', key: 'nv.hooks' },
          { href: '#export', label: '导出时间线', key: 'nv.export' }
        ]
      }
    ],

    'anima/compat.html': [
      {
        group: '文档',
        key: 'nv.group.docs',
        items: [
          { href: 'index.html', label: '快速开始', key: 'nv.page.index' },
          { href: 'editor.html', label: '编辑器使用教程', key: 'nv.page.editor' },
          { href: 'json.html', label: '资源包 JSON 动画', key: 'nv.page.json' },
          { href: 'api.html', label: 'Java API 教程', key: 'nv.page.api' },
          { href: 'compat.html', label: '兼容性与 FAQ', key: 'nv.page.compat' }
        ]
      },
      {
        group: '本页',
        key: 'nv.group.this',
        items: [
          { href: '#compat', label: '渲染相关', key: 'nv.compat' },
          { href: '#packs', label: '资源包与优先级', key: 'nv.packs' },
          { href: '#server', label: '服务端', key: 'nv.server' },
          { href: '#faq', label: '常见问题', key: 'nv.faq' },
          { href: '#performance', label: '性能与开销', key: 'nv.perf' }
        ]
      }
    ],

    /* ---------- Damage Engine(词条沿用原站) ---------- */
    'damage-engine/index.html': [
      {
        group: '入门',
        key: 'nav.group.start',
        items: [
          { href: '#overview', label: '简介', key: 'nav.overview' },
          { href: '#requirements', label: '前置条件', key: 'nav.requirements' },
          { href: '#quickstart', label: '快速开始', key: 'nav.quickstart' }
        ]
      },
      {
        group: '评分 API',
        key: 'nav.group.rating',
        items: [
          { href: '#register', label: '注册加分项', key: 'nav.register' },
          { href: '#reference', label: 'API 参考', key: 'nav.reference' },
          { href: '#example', label: '完整示例', key: 'nav.example' }
        ]
      },
      {
        group: '通用',
        key: 'nav.group.general',
        items: [
          { href: '#ingame', label: '在游戏内查看', key: 'nav.ingame' },
          { href: '#notes', label: '注意事项', key: 'nav.notes' }
        ]
      }
    ]
  }
};
