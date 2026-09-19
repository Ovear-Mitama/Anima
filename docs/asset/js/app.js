/* ============================================================
   文档站 - 页面逻辑
   依赖: asset/js/nav.js  (window.ANIMA_DOCS)
         asset/js/i18n.js (window.SITE_LANGS / window.SITE_I18N)
   负责: 顶部「切换模组」下拉、右上角语言切换、侧栏渲染、分类折叠、
         滚动高亮、深浅色主题、代码复制、移动端目录抽屉
   ============================================================ */
(function () {
  'use strict';

  var DOCS = window.ANIMA_DOCS || { siteTitle: '文档', mods: [], nav: {} };
  var MODS = DOCS.mods || [];
  var NAV = DOCS.nav || {};
  var I18N = window.SITE_I18N || { zh: {} };
  var LANGS = window.SITE_LANGS || [{ code: 'zh', label: '简体中文' }];

  var THEME_KEY = 'anima-doc-theme';
  var LANG_KEY = 'anima-doc-lang';

  var currentTheme = 'light';
  var currentLang = 'zh';

  /* ---------- 当前是哪个模组 / 哪一个页面 ---------- */
  function pathName() {
    return decodeURIComponent(location.pathname);
  }

  function currentMod() {
    var path = pathName();
    for (var i = 0; i < MODS.length; i++) {
      if (path.indexOf('/' + MODS[i].id + '/') !== -1) return MODS[i];
    }
    return null;
  }

  function currentPageKey() {
    var file = pathName().split('/').pop();
    if (!file) file = 'index.html';
    var mod = currentMod();
    return mod ? (mod.id + '/' + file) : file;
  }

  /* ---------- 本地存储(隐私模式下静默失败) ---------- */
  function store(key, value) {
    try { localStorage.setItem(key, value); } catch (e) {}
  }
  function read(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  }

  /* ---------- 顶栏:站点标题 + 模组切换下拉(标题右侧) ---------- */
  function initTopbar() {
    var select = document.getElementById('docSelect');
    var btn = document.getElementById('docBtn');
    var menu = document.getElementById('docMenu');
    var label = document.getElementById('docLabel');
    if (!select || !btn || !menu) return;

    var here = currentMod();

    MODS.forEach(function (mod) {
      var opt = document.createElement('a');
      opt.className = 'doc-option' + (here && here.id === mod.id ? ' active' : '');
      opt.href = mod.home;
      opt.textContent = mod.label;
      if (mod.hint) {
        var hint = document.createElement('span');
        hint.className = 'doc-hint';
        hint.textContent = mod.hint;
        opt.appendChild(hint);
      }
      menu.appendChild(opt);
    });

    if (label) label.textContent = here ? here.label : '切换模组';

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      select.classList.toggle('open');
    });
    document.addEventListener('click', function () { select.classList.remove('open'); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') select.classList.remove('open');
    });
  }

  /* ---------- 侧栏:按 nav.js 里当前页的配置渲染 ---------- */
  function renderNav() {
    var nav = document.getElementById('sidebar');
    if (!nav) return;

    var groups = NAV[currentPageKey()] || [];
    /* 落地页没有侧栏:让正文占满整行 */
    if (!groups.length) {
      var layout = document.querySelector('.layout');
      if (layout) layout.classList.add('no-side');
      return;
    }

    groups.forEach(function (group, index) {
      var section = document.createElement('div');
      /* 第一个分类默认展开,其余默认收起 */
      section.className = 'nav-section' + (index > 0 ? ' collapsed' : '');

      var head = document.createElement('button');
      head.className = 'nav-group-btn';
      head.type = 'button';

      var title = document.createElement('span');
      title.textContent = group.group;
      if (group.key) title.setAttribute('data-i18n', group.key);
      var chev = document.createElement('i');
      chev.className = 'fa-solid fa-chevron-right chev';

      head.appendChild(title);
      head.appendChild(chev);
      section.appendChild(head);

      var items = document.createElement('div');
      items.className = 'nav-items';
      (group.items || []).forEach(function (item) {
        var a = document.createElement('a');
        a.setAttribute('href', item.href);
        a.textContent = item.label;
        if (item.key) a.setAttribute('data-i18n', item.key);
        items.appendChild(a);
      });
      section.appendChild(items);

      nav.appendChild(section);
    });
  }

  /* ---------- 多语言 ---------- */
  function langMeta(code) {
    for (var i = 0; i < LANGS.length; i++) {
      if (LANGS[i].code === code) return LANGS[i];
    }
    return null;
  }

  /** 记下每个带 data-i18n 的元素最初的文字(页面里写的中文),切换语言时用它兜底。 */
  function snapshotOriginal() {
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      if (el.getAttribute('data-i18n-orig') == null) {
        el.setAttribute('data-i18n-orig', el.innerHTML);
      }
    });
  }

  function applyLang(lang) {
    var dict = I18N[lang] || I18N.zh || {};
    currentLang = lang;
    document.documentElement.lang = (lang === 'zh') ? 'zh-CN' : (lang === 'zhTW' ? 'zh-Hant' : 'en');

    /* data-i18n 标记的元素:没有对应词条时回到页面原本的文字(中文回退) */
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      var orig = el.getAttribute('data-i18n-orig');
      if (dict[key] != null) {
        el.innerHTML = dict[key];
      } else if (orig != null) {
        el.innerHTML = orig;
      }
    });

    /* 没有 data-i18n 的几处固定文案 */
    var brand = document.getElementById('brandName');
    if (brand) brand.textContent = dict['site.title'] || DOCS.siteTitle || '文档';
    if (!currentMod()) {
      var dl = document.getElementById('docLabel');
      if (dl) dl.textContent = dict['doc.switch'] || '切换模组';
    }
    document.querySelectorAll('.copy-btn').forEach(function (b) {
      b.textContent = dict['btn.copy'] || '复制';
    });

    var label = document.getElementById('langLabel');
    var meta = langMeta(lang);
    if (label) label.textContent = meta ? meta.label : lang;

    document.querySelectorAll('.lang-option').forEach(function (opt) {
      opt.classList.toggle('active', opt.getAttribute('data-lang') === lang);
    });

    store(LANG_KEY, lang);
  }

  function initLangSelect() {
    var select = document.getElementById('langSelect');
    var btn = document.getElementById('langBtn');
    var menu = document.getElementById('langMenu');
    if (!select || !btn || !menu) return;

    LANGS.forEach(function (lang) {
      var opt = document.createElement('button');
      opt.className = 'lang-option';
      opt.type = 'button';
      opt.textContent = lang.label;
      opt.setAttribute('data-lang', lang.code);
      opt.addEventListener('click', function () {
        applyLang(lang.code);
        select.classList.remove('open');
      });
      menu.appendChild(opt);
    });

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      select.classList.toggle('open');
    });
    document.addEventListener('click', function () { select.classList.remove('open'); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') select.classList.remove('open');
    });

    var saved = read(LANG_KEY);
    applyLang(langMeta(saved) ? saved : 'zh');
  }

  /* ---------- 移动端那个「目录」按钮:图标保留,文字可翻译 ---------- */
  function initMenuButton() {
    var mb = document.getElementById('menuBtn');
    if (!mb) return;
    mb.textContent = '';
    var icon = document.createElement('i');
    icon.className = 'fa-solid fa-bars';
    var text = document.createElement('span');
    text.setAttribute('data-i18n', 'btn.menu');
    text.textContent = '目录';
    mb.appendChild(icon);
    mb.appendChild(document.createTextNode(' '));
    mb.appendChild(text);
  }

  /* ---------- 深色 / 浅色主题 ---------- */
  function applyTheme(theme) {
    currentTheme = theme;
    document.documentElement.setAttribute('data-theme', theme);

    var icon = document.querySelector('#themeBtn i');
    if (icon) icon.className = theme === 'dark' ? 'fa-solid fa-sun' : 'fa-solid fa-moon';

    var btn = document.getElementById('themeBtn');
    if (btn) btn.setAttribute('aria-label', theme === 'dark' ? '切换到浅色' : '切换到深色');
  }

  function initTheme() {
    var btn = document.getElementById('themeBtn');
    var saved = read(THEME_KEY);
    var prefersDark = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    applyTheme(saved === 'dark' || saved === 'light' ? saved : (prefersDark ? 'dark' : 'light'));

    if (btn) {
      btn.addEventListener('click', function () {
        var next = currentTheme === 'dark' ? 'light' : 'dark';
        applyTheme(next);
        store(THEME_KEY, next);
      });
    }
  }

  /* ---------- 代码块复制 ---------- */
  function initCopyButtons() {
    document.querySelectorAll('.copy-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var head = btn.closest('.code-head');
        var pre = head && head.nextElementSibling;
        if (!pre || !navigator.clipboard) return;

        navigator.clipboard.writeText(pre.innerText).then(function () {
          var dict = I18N[currentLang] || I18N.zh || {};
          btn.textContent = dict['btn.copied'] || '已复制';
          setTimeout(function () { btn.textContent = dict['btn.copy'] || '复制'; }, 1200);
        });
      });
    });
  }

  /* ---------- 分类展开 / 收起 ---------- */
  function initCollapse() {
    document.querySelectorAll('nav.sidebar .nav-group-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var section = btn.closest('.nav-section');
        if (section) section.classList.toggle('collapsed');
      });
    });
  }

  /* ---------- 当前小节高亮 ---------- */
  function initScrollSpy() {
    var links = Array.prototype.slice.call(document.querySelectorAll('nav.sidebar .nav-items a'));
    var sections = links
      .map(function (a) { return document.querySelector(a.getAttribute('href')); })
      .filter(Boolean);
    if (!sections.length) return;

    function onScroll() {
      var pos = window.scrollY + 90;
      var current = sections[0];
      sections.forEach(function (sec) {
        if (sec.offsetTop <= pos) current = sec;
      });
      links.forEach(function (a) {
        a.classList.toggle('active', current && a.getAttribute('href') === '#' + current.id);
      });
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  /* ---------- 移动端目录抽屉 ---------- */
  function initDrawer() {
    var sidebar = document.getElementById('sidebar');
    var backdrop = document.getElementById('backdrop');
    var menuBtn = document.getElementById('menuBtn');
    if (!sidebar || !menuBtn) return;

    function closeMenu() {
      sidebar.classList.remove('open');
      if (backdrop) backdrop.classList.remove('show');
    }

    menuBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      sidebar.classList.toggle('open');
      if (backdrop) backdrop.classList.toggle('show', sidebar.classList.contains('open'));
    });
    if (backdrop) backdrop.addEventListener('click', closeMenu);

    document.querySelectorAll('nav.sidebar a').forEach(function (a) {
      a.addEventListener('click', closeMenu);
    });
  }

  /* ---------- 初始化 ---------- */
  initTheme();
  initTopbar();
  renderNav();
  initMenuButton();
  initCopyButtons();
  snapshotOriginal();  // 必须在 renderNav 之后:侧栏是脚本生成的
  initLangSelect();
  initCollapse();
  initScrollSpy();
  initDrawer();
})();
