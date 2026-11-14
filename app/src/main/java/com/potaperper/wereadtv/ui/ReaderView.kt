package com.potaperper.wereadtv.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.potaperper.wereadtv.model.Book
import com.potaperper.wereadtv.model.Chapter
import com.potaperper.wereadtv.model.ChapterContent
import com.potaperper.wereadtv.util.Theme

/**
 * 阅读器：WebView + CSS 多栏分页（epub.js 同款机制）。
 * 每一栏即一页，浏览器保证只在行边界换栏，杜绝半行字；
 * 原书 CSS 的行距/负边距不再影响分页正确性。
 * 顶栏为浮层（不挤压正文）；目录书名独立置顶。
 */
class ReaderView(
    private val activity: MainActivity,
    private val book: Book,
    private val onBackToShelf: () -> Unit,
) : FrameLayout(activity) {

    companion object {
        private fun pageHtml(bg: Int, fg: Int) = """
<!DOCTYPE html>
<html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width, initial-scale=1'>
<style id='chapter-style'></style>
<style>
html,body{margin:0;padding:0;background:${Theme.toHex(bg)};height:100%;overflow:hidden;}
#viewport{position:fixed;inset:0;overflow:hidden;}
@font-face{font-family:'WC-Serif';src:url('https://wc-reader.local/fonts/noto_serif_sc.woff2');}
@font-face{font-family:'WC-Sans';src:url('https://wc-reader.local/fonts/noto_sans_sc.woff2');}
@font-face{font-family:'WC-Kai';src:url('https://wc-reader.local/fonts/lxgw_wenkai.woff2');}
@font-face{font-family:'WC-XiHei';src:url('https://wc-reader.local/fonts/lxgw_xihei.woff2');}
@font-face{font-family:'WC-Yozai';src:url('https://wc-reader.local/fonts/yozai.woff2');}
@font-face{font-family:'WC-Glow';src:url('https://wc-reader.local/fonts/glow_sans.woff2');}
@font-face{font-family:'WC-Smiley';src:url('https://wc-reader.local/fonts/smiley_sans.woff2');}
#pager{
  height:100%;box-sizing:border-box;
  color:${Theme.toHex(fg)};
  font-size:24px;line-height:1.8;
  column-fill:auto;
  transform:translateX(0);
}
#pager img{max-width:100%!important;max-height:calc(100vh - 80px)!important;height:auto!important;margin:0.5em auto;border-radius:4px;}
#pager a{color:#4a90d9;text-decoration:none;}
/* 脚注标记：纯上标彩色数字（无方框），注释内容通过长按 OK / 顶栏“注释”查看 */
#pager sup.wr-note{font-size:0.68em;line-height:1;color:#3a7bd5;vertical-align:super;margin:0 1px;font-weight:normal;}
/* 章尾注释块：默认隐藏，统一从注释面板查看 */
#pager p.ft,#pager div.ft{display:none!important;}
/* 连续滚动模式：取消多栏，按正常文档流纵向滚动 */
html.wr-scroll, html.wr-scroll body{height:auto!important;overflow-y:auto!important;overflow-x:hidden!important;}
html.wr-scroll #viewport{position:static!important;inset:auto!important;overflow:visible!important;}
html.wr-scroll #pager{columns:auto!important;column-width:auto!important;height:auto!important;width:auto!important;transform:none!important;transition:none!important;}
</style>
<script>
window.__page = 0;
window.__totalPages = 1;
window.__themeBg = '${Theme.toHex(bg)}';
window.__marginPx = 72;
window.__marginYPx = 44;
window.__marginTopPx = 44;
window.__marginBottomPx = 44;
window.__instant = false;
window.__scrollMode = false;
window.__autoRaf = 0;
var GAP_FACTOR = 2;  /* 栏间距 = 2×边距，视觉上即左右页边距 */

function pagerEl(){ return document.getElementById('pager'); }
/** 多栏布局：列宽 = 屏宽 - 2×边距；gap 恰好补齐使每页平移正好一个屏宽 */
function relayout(){
  if (window.__scrollMode) return;
  var pager = pagerEl();
  var w = window.innerWidth;
  var colw = w - 2 * window.__marginPx;
  var gap = 2 * window.__marginPx;
  pager.style.columnWidth = colw + 'px';
  pager.style.columnGap = gap + 'px';
  pager.style.paddingLeft = window.__marginPx + 'px';
  pager.style.paddingRight = window.__marginPx + 'px';
  pager.style.width = w + 'px';
  var unit = colw + gap;  /* = innerWidth */
  window.__totalPages = Math.max(1, Math.round((pager.scrollWidth - 2 * window.__marginPx) / unit + 0.5));
  console.log('PAGER_DBG ' + JSON.stringify({
    w: w, colw: colw, gap: gap, scrollW: pager.scrollWidth,
    total: window.__totalPages, page: window.__page,
    textLen: (pager.textContent || '').length
  }));
}
function updateHud(){
  var hud = document.getElementById('hud');
  if (hud) hud.textContent = (window.__page + 1) + ' / ' + window.__totalPages;
}
function goTo(p, instant){
  relayout();
  window.__page = Math.max(0, Math.min(p, window.__totalPages - 1));
  var pager = pagerEl();
  var unit = window.innerWidth;  /* colw + gap = innerWidth */
  /* 第 0 页的左边距已由 padding-left 提供，不额外平移 */
  var offset = -window.__page * unit;
  var smooth = (instant === true) ? false : !window.__instant;
  pager.style.transition = smooth ? 'transform .22s ease-out' : 'none';
  pager.style.transform = 'translateX(' + offset + 'px)';
  // 保险：任何情况下都不允许文档横向滚动（否则 fixed 视口会整体偏移）
  if (window.scrollX !== 0 || window.scrollY !== 0) window.scrollTo(0, 0);
  updateHud();
}
window.tryNext = function(){ relayout(); if (window.__page >= window.__totalPages - 1) return false; goTo(window.__page + 1); return true; };
window.tryPrev = function(){ relayout(); if (window.__page <= 0) return false; goTo(window.__page - 1); return true; };
window.gotoLast = function(){ relayout(); goTo(window.__totalPages - 1, true); };
window.gotoFraction = function(f){
  relayout();
  var p = Math.round((window.__totalPages - 1) * Math.max(0, Math.min(1, f)));
  goTo(p, true);
};
window.pageInfo = function(){ return window.__page + ',' + window.__totalPages; };
function textOffsetOf(root, node, offset){
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
  var acc = 0, n;
  while ((n = walker.nextNode())) {
    if (n === node) return acc + offset;
    acc += n.nodeValue.length;
  }
  return -1;
}
/* 记录当前页/当前滚动位置左上角第一个字符在整章文本中的偏移 */
window.capturePosition = function(){
  var pager = pagerEl();
  var off = -1;
  var percent = 0;
  try {
    if (window.__scrollMode) {
      var r0 = document.caretRangeFromPoint ? document.caretRangeFromPoint(window.__marginPx + 6, window.__marginTopPx + 10) : null;
      if (r0 && pager.contains(r0.startContainer)) {
        off = textOffsetOf(pager, r0.startContainer, r0.startOffset);
      }
      var max0 = document.body.scrollHeight - window.innerHeight;
      percent = max0 > 0 ? window.scrollY * 100 / max0 : 0;
    } else {
      var page = window.__page;
      percent = window.__totalPages > 1 ? page * 100 / (window.__totalPages - 1) : 0;
      var unit = window.innerWidth;
      var prevTr = pager.style.transition;
      pager.style.transition = 'none';
      pager.style.transform = 'translateX(' + (-page * unit) + 'px)';
      var x = window.__marginPx + 6;
      var y = window.__marginTopPx + 10;
      var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
      if (r && pager.contains(r.startContainer)) {
        off = textOffsetOf(pager, r.startContainer, r.startOffset);
      }
      pager.style.transition = prevTr;
    }
  } catch(e) {}
  return off + ',' + percent;
};
/* 按字符偏移恢复位置（排版变化后仍能回到同一段） */
window.restorePosition = function(offset, percent){
  var pager = pagerEl();
  if (window.__scrollMode) {
    var y = -1;
    if (offset >= 0) {
      try {
        var w0 = document.createTreeWalker(pager, NodeFilter.SHOW_TEXT, null, false);
        var a0 = 0, n0 = null, nn;
        while ((nn = w0.nextNode())) {
          if (a0 + nn.nodeValue.length >= offset) { n0 = nn; break; }
          a0 += nn.nodeValue.length;
        }
        if (n0) {
          var lo = Math.max(0, Math.min(offset - a0, n0.nodeValue.length));
          var rr = document.createRange();
          rr.setStart(n0, lo); rr.setEnd(n0, lo);
          y = rr.getBoundingClientRect().top + window.scrollY;
        }
      } catch(e) {}
    }
    if (y >= 0) {
      window.scrollTo(0, Math.max(0, y - window.__marginTopPx));
    } else if (percent > 0) {
      var mx = Math.max(0, document.body.scrollHeight - window.innerHeight);
      window.scrollTo(0, mx * percent / 100);
    } else {
      window.scrollTo(0, 0);
    }
    window.updateCharsPerScreen();
    return 0;
  }
  relayout();
  var page = 0;
  if (offset >= 0) {
    try {
      var walker = document.createTreeWalker(pager, NodeFilter.SHOW_TEXT, null, false);
      var acc = 0, node = null, n;
      while ((n = walker.nextNode())) {
        if (acc + n.nodeValue.length >= offset) { node = n; break; }
        acc += n.nodeValue.length;
      }
      if (node) {
        var localOff = Math.max(0, Math.min(offset - acc, node.nodeValue.length));
        var range = document.createRange();
        range.setStart(node, localOff);
        range.setEnd(node, localOff);
        var prevT = pager.style.transform;
        var prevTr = pager.style.transition;
        pager.style.transition = 'none';
        pager.style.transform = 'translateX(0)';
        var rect = range.getBoundingClientRect();
        pager.style.transform = prevT;
        pager.style.transition = prevTr;
        page = Math.floor((rect.left - window.__marginPx) / window.innerWidth + 0.001);
      }
    } catch(e) {}
  }
  if (page <= 0 && percent > 0) {
    page = Math.round((window.__totalPages - 1) * percent / 100);
  }
  page = Math.max(0, Math.min(page, window.__totalPages - 1));
  goTo(page, true);
  return page;
};
window.setZoom = function(fontPx, offset, percent){
  pagerEl().style.fontSize = fontPx + 'px';
  requestAnimationFrame(function(){ requestAnimationFrame(function(){
    restorePosition(offset, percent);
    window.updateCharsPerScreen();
  }); });
};
window.setFontFamily = function(family, bold, offset, percent){
  var st = document.getElementById('wr-font');
  if (!st) { st = document.createElement('style'); st.id = 'wr-font'; document.head.appendChild(st); }
  var css = '';
  if (family) css += '#pager, #pager *{font-family:' + family + ' !important;}';
  if (bold) css += '#pager, #pager *{font-weight:700 !important;}';
  st.textContent = css;
  pagerEl().style.fontFamily = family || '';
  requestAnimationFrame(function(){ requestAnimationFrame(function(){
    restorePosition(offset, percent);
    window.updateCharsPerScreen();
  }); });
};
window.setLayoutPrefs = function(lhRatio, marginPx, marginTopPx, marginBottomPx, instant, offset, percent){
  var pager = pagerEl();
  pager.style.lineHeight = lhRatio;
  window.__marginPx = marginPx;
  window.__marginTopPx = marginTopPx;
  window.__marginBottomPx = marginBottomPx;
  window.__marginYPx = marginTopPx;
  pager.style.paddingLeft = marginPx + 'px';
  pager.style.paddingRight = marginPx + 'px';
  pager.style.paddingTop = marginTopPx + 'px';
  pager.style.paddingBottom = marginBottomPx + 'px';
  window.__instant = instant;
  requestAnimationFrame(function(){ requestAnimationFrame(function(){
    restorePosition(offset, percent);
    window.updateCharsPerScreen();
  }); });
};
window.setTheme = function(bg, fg){
  window.__themeBg = bg;
  applyShellOverride();
  var pager = pagerEl();
  if (pager) {
    pager.style.color = fg;
    pager.style.setProperty('background-color', bg, 'important');
  }
};
window.updateCharsPerScreen = function(){
  var p = pagerEl();
  if (!p) return;
  var t = (p.innerText || '').length;
  var h = p.scrollHeight || 1;
  window.__charsPerScreen = Math.max(1, t * window.innerHeight / h);
};
window.setScrollMode = function(on){
  window.__scrollMode = !!on;
  document.documentElement.classList.toggle('wr-scroll', !!on);
  if (!on) window.stopAutoScroll();
  applyShellOverride();
  if (!on) {
    relayout();
    goTo(window.__page || 0, true);
  } else {
    window.scrollTo(0, 0);
    window.updateCharsPerScreen();
  }
};
window.startAutoScroll = function(basePx, targetCps, adaptive){
  window.stopAutoScroll();
  if (!window.__scrollMode) return;
  window.__autoBasePx = basePx;
  window.__autoCps = targetCps;
  window.__autoAdaptive = !!adaptive;
  window.updateCharsPerScreen();
  var maxStart = document.body.scrollHeight - window.innerHeight;
  if (maxStart <= 2) {
    try { AndroidBridge.onScrollChapterEnd(); } catch(e){}
    return;
  }
  var last = performance.now();
  var acc = 0;
  var lastCalc = 0;
  var px = basePx;
  function step(t){
    if (!window.__scrollMode || !window.__autoBasePx) return;
    var dt = Math.min(0.1, (t - last) / 1000);
    last = t;
    if (window.__autoAdaptive) {
      if (t - lastCalc > 800) {
        lastCalc = t;
        px = window.__autoCps / Math.max(1, window.__charsPerScreen) * window.innerHeight;
      }
    } else {
      px = window.__autoBasePx;
    }
    acc += px * dt;
    if (acc >= 1) {
      var d = Math.floor(acc);
      acc -= d;
      window.scrollBy(0, d);
    }
    var max = document.body.scrollHeight - window.innerHeight;
    if (max > 0 && window.scrollY >= max - 2) {
      window.stopAutoScroll();
      try { AndroidBridge.onScrollChapterEnd(); } catch(e){}
      return;
    }
    window.__autoRaf = requestAnimationFrame(step);
  }
  window.__autoRaf = requestAnimationFrame(step);
};
window.stopAutoScroll = function(){
  if (window.__autoRaf) { cancelAnimationFrame(window.__autoRaf); window.__autoRaf = 0; }
};
window.scrollPage = function(dir){
  window.scrollBy(0, dir * window.innerHeight * 0.85);
};
window.scrollPercent = function(){
  var max = Math.max(0, document.body.scrollHeight - window.innerHeight);
  return max > 0 ? Math.min(100, window.scrollY * 100 / max) : 0;
};
window.loadChapterContent = function(bodyHtml, styleHtml){
  window.__rawBody = bodyHtml;
  window.__rawStyle = styleHtml;
  renderCurrent();
};
function renderCurrent(){
  var pager = pagerEl();
  pager.style.visibility = 'hidden';
  var style = document.getElementById('chapter-style');
  if (!style) { style = document.createElement('style'); style.id = 'chapter-style'; document.head.appendChild(style); }
  style.textContent = window.__rawStyle || '';
  pager.innerHTML = window.__rawBody || '';
  resolveAssets(pager);
  extractNotes(pager);
  applyShellOverride();
  window.__page = 0;
  requestAnimationFrame(function(){ requestAnimationFrame(function(){
    if (window.__scrollMode) {
      window.scrollTo(0, 0);
      window.updateCharsPerScreen();
      window.__totalPages = 1;
      pager.style.visibility = 'visible';
      try { AndroidBridge.onRendered(1); } catch(e){}
      return;
    }
    goTo(0, true);
    pager.style.visibility = 'visible';
    try { AndroidBridge.onRendered(window.__totalPages); } catch(e){}
  });});
}
/** 提取脚注：① img.qqreader-footnote（行内图片注释）② a.fr + p.ft（章尾锚点注释） */
function extractNotes(root){
  var imgs = root.querySelectorAll('img');
  var n = 0;
  for (var i = 0; i < imgs.length; i++) {
    var im = imgs[i];
    var cls = im.getAttribute('class') || '';
    var alt = im.getAttribute('alt') || '';
    var src = im.getAttribute('src') || '';
    var isFootnote = cls.indexOf('footnote') >= 0 || (alt.length > 30 && src.indexOf('note') >= 0);
    if (isFootnote && alt) {
      n++;
      var sup = document.createElement('sup');
      sup.className = 'wr-note';
      sup.textContent = '' + n;
      im.parentNode.replaceChild(sup, im);
    } else {
      im.addEventListener('error', function(){ this.style.display = 'none'; });
      if (im.complete && im.naturalWidth === 0) im.style.display = 'none';
    }
  }
  // 章尾注释块：隐藏，正文内锚点换成上标数字
  var fts = root.querySelectorAll('p.ft, div.ft');
  for (var f = 0; f < fts.length; f++) {
    var ft = fts[f];
    var a = ft.querySelector('a');
    var num = a ? (a.textContent || '').replace(/[^0-9]/g, '') : '';
    ft.style.display = 'none';
    if (!num) num = '' + (f + 1);
    ft.setAttribute('data-wr-note', num);
  }
  var frs = root.querySelectorAll('a.fr');
  for (var j = 0; j < frs.length; j++) {
    var fr = frs[j];
    var t = (fr.textContent || '').replace(/[^0-9]/g, '') || (j + 1);
    var s2 = document.createElement('sup');
    s2.className = 'wr-note';
    s2.textContent = '' + t;
    fr.parentNode.replaceChild(s2, fr);
  }
  // 兜底：1.5 秒后仍未加载成功的图片一律隐藏；
  // 若整章既无文字也无可用图片，显示提示而不是留白屏
  setTimeout(function(){
    var rest = root.querySelectorAll('img');
    var hasVisibleImg = false;
    for (var k = 0; k < rest.length; k++) {
      var r = rest[k];
      if (!r.complete || r.naturalWidth === 0) { r.style.display = 'none'; }
      else if (r.style.display !== 'none') { hasVisibleImg = true; }
    }
    if (!hasVisibleImg && (root.textContent || '').trim().length === 0) {
      root.innerHTML = '<p style="text-align:center;opacity:.6;margin-top:38vh;">本章为图片内容，加载失败</p>';
    }
    if (!window.__scrollMode) goTo(window.__page, true);
  }, 1500);
}
function applyShellOverride(){
  var bg = window.__themeBg || '#161616';
  var de = document.documentElement;
  de.style.setProperty('background', bg, 'important');
  de.style.setProperty('width', '100%', 'important');
  var b = document.body;
  b.style.setProperty('background', bg, 'important');
  b.style.setProperty('margin', '0', 'important');
  b.style.setProperty('padding', '0', 'important');
  b.style.setProperty('width', '100%', 'important');
  var pager = pagerEl();
  if (pager) pager.style.setProperty('background-color', bg, 'important');
  // 章节自带 CSS 可能给内容容器铺白底：统一强制透明，跟随主题
  var th = document.getElementById('wr-theme-bg');
  if (!th) { th = document.createElement('style'); th.id = 'wr-theme-bg'; document.head.appendChild(th); }
  th.textContent = '#pager, #pager *:not(img){background-color:transparent !important;}';
  if (window.__scrollMode) {
    de.style.setProperty('overflow-y', 'auto', 'important');
    de.style.setProperty('overflow-x', 'hidden', 'important');
    de.style.setProperty('height', 'auto', 'important');
    b.style.setProperty('overflow-y', 'visible', 'important');
    b.style.setProperty('height', 'auto', 'important');
  } else {
    de.style.setProperty('overflow', 'hidden', 'important');
    de.style.setProperty('height', '100%', 'important');
    b.style.setProperty('overflow', 'hidden', 'important');
    b.style.setProperty('height', '100%', 'important');
    // 锁定滚动：任何横向溢出都不允许滚动页面（否则 fixed 定位的视口会整体偏移）
    window.scrollTo(0, 0);
  }
}
function resolveAssets(root){
  var base = 'https://res.weread.qq.com/wrepub/';
  var tags = root.querySelectorAll('img');
  for (var i = 0; i < tags.length; i++) fixSrc(tags[i], base);
  var links = root.querySelectorAll('link[href]');
  for (var i = 0; i < links.length; i++) { links[i].parentNode.removeChild(links[i]); }
}
function fixSrc(el, base){
  var src = el.getAttribute('src') || '';
  if (!src || src.indexOf('http') === 0 || src.indexOf('data:') === 0) return;
  if (src.indexOf('/') === 0) { el.setAttribute('src', 'https://weread.qq.com' + src); return; }
  el.setAttribute('src', base + src.split('/').pop());
}
</script>
</head><body>
<div id='viewport'><div id='pager'></div></div>
</body></html>
""".trimIndent()
    }

    private val webView: WebView
    private val topBar: FrameLayout
    private val topBarHint: TextView
    private val titleText: TextView
    private val hudTopLeft: TextView
    private val hudTopRight: TextView
    private val hudBottomLeft: TextView
    private val hudBottomRight: TextView
    private val fastJumpBanner: TextView
    private val catalogOverlay: FrameLayout
    private val catalogList: ListView
    private val catalogAdapter = CatalogAdapter()
    private val catalogTitle: TextView
    private val notesOverlay: FrameLayout
    private val notesList: ListView
    private val notesAdapter = NotesAdapter()
    private val notesDetail: FrameLayout
    private val notesDetailText: TextView
    private val notesScroll: ScrollView
    private var notes: List<Pair<Int, String>> = emptyList()
    private val loadingText: TextView
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var chapters: List<Chapter> = emptyList()
    private var currentChapterIdx = 0
    private var contentFormat = "epub"
    private var pageLoaded = false
    private var pendingContent: Pair<String, String>? = null

    private var loadSeq = 0
    fun currentLoadSeq(): Int = loadSeq

    /** 跨章返回时：加载上一章后自动跳到该章最后一页 */
    private var pendingLastPage = false

    /** 打开章节后要恢复的字符偏移/章节百分比 */
    private var pendingOffset = -1
    private var pendingPercent = 0f

    /** 最近一次捕获的阅读位置（用于退出时同步保存） */
    private var lastOffset = -1
    private var lastPercent = 0f
    private var lastLocalSaveAt = 0L

    /** 目录快速跳跃模式（长按方向键触发，每次 100 章） */
    private var catalogFastMode = false
    private var catalogHoldAt = 0L
    private var catalogFastLastAt = 0L

    /** 四角信息的时钟 */
    private val clockRunnable = object : Runnable {
        override fun run() {
            refreshHud()
            mainHandler.postDelayed(this, 30_000)
        }
    }

    /** 自动滚动时定期保存本地进度（防止进程被系统回收） */
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            if (Theme.autoScroll(activity) && currentChapter() != null) {
                saveLocalPosition(force = true)
            }
            mainHandler.postDelayed(this, 20_000)
        }
    }

    /** 顶栏显示时刻：用于双击返回判定 */
    private var topBarShownAt = 0L

    /** 长按跳章防重复 */
    private var longPressFired = false

    /** 顶栏三个按钮，用于循环移焦点 */
    private var topBarFocusables: List<TextView> = emptyList()

    private fun moveTopBarFocus(dir: Int) {
        val list = topBarFocusables
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.hasFocus() }
        val next = when {
            idx < 0 -> 0
            else -> (idx + dir + list.size) % list.size
        }
        list[next].requestFocus()
    }

    /** 顶栏按钮点击后，吞掉这一次按键的 UP，避免漏进阅读页逻辑触发长按 */
    private var swallowNextUp = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true
        if (swallowNextUp && action == KeyEvent.ACTION_UP) {
            swallowNextUp = false
            return true
        }

        if (notesOverlay.visibility == VISIBLE) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { dismissNotes(); return true }
                if (notesDetail.visibility == VISIBLE) {
                    // 注释详情：上下键翻动文本
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            notesScroll.smoothScrollBy(0, -notesScrollStep()); return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            notesScroll.smoothScrollBy(0, notesScrollStep()); return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (catalogOverlay.visibility == VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                if (catalogFastMode) exitCatalogFastMode() else toggleCatalog(false)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (action == KeyEvent.ACTION_DOWN) {
                    handleCatalogDirection(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                } else {
                    catalogHoldAt = 0L
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (topBar.visibility == VISIBLE) {
            if (action == KeyEvent.ACTION_UP) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { exitReader(); return true }
                // 顶栏内自行循环移动焦点，避免焦点逃逸到 WebView 导致按键失控
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                    moveTopBarFocus(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveTopBarFocus(+1); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val f = topBar.findFocus()
                    swallowNextUp = true
                    if (f is TextView) f.performClick() else hideTopBar()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        val now = System.currentTimeMillis()
        val vertical = Theme.verticalPaging(activity)
        val isNext = event.keyCode in (if (vertical) intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN) else intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN))
        val isPrev = event.keyCode in (if (vertical) intArrayOf(KeyEvent.KEYCODE_DPAD_UP) else intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP))
        val isCenter = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER

        // ---------- 中心键：短按看目录，长按看注释 ----------
        if (isCenter) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (!centerKeyDown) {
                    centerKeyDown = true
                    centerDownAt = now
                    centerHold = 1
                    longPressFired = false
                } else {
                    centerHold++
                }
                if (centerHold >= 5 && !longPressFired) {
                    longPressFired = true
                    requestNotes()
                    resetTap()
                }
                return true
            }
            centerKeyDown = false
            if (longPressFired) return true
            val dur = now - centerDownAt
            val rapid = registerTap("center", now)
            if (dur >= 600 || rapid >= 5) {
                requestNotes()
                resetTap()
            } else {
                toggleCatalog(true)
            }
            return true
        }

        // ---------- 连续滚动模式：方向键手动滚动，暂停自动滚动 ----------
        if (Theme.autoScroll(activity)) {
            val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            if (down || up) {
                if (action == KeyEvent.ACTION_DOWN) {
                    stopAutoScroll()
                    webView.evaluateJavascript(
                        "window.scrollPage(${if (down) 1 else -1})", null,
                    )
                    mainHandler.postDelayed({ refreshHud() }, 420)
                }
                return true
            }
        }

        // ---------- 方向键 ----------
        if (!isNext && !isPrev) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> { if (action == KeyEvent.ACTION_DOWN) activity.showThemePanel(); true }
                KeyEvent.KEYCODE_BACK -> {
                    if (action == KeyEvent.ACTION_DOWN) { topBarShownAt = now; showTopBar() }
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }

        val dir = if (isNext) 1 else -1
        val jumpMode = Theme.longPressJump(activity)
        val keyName = if (isNext) "next" else "prev"

        if (!jumpMode) {
            // 连续快进模式：按下即翻页（长按随 repeat 持续翻页）
            if (action == KeyEvent.ACTION_DOWN) nextPageOrChapter(dir)
            return true
        }

        if (action == KeyEvent.ACTION_DOWN) {
            // 关键：用"上一个 DOWN 后有没有等到 UP"判断是否仍按着。
            // 部分遥控器长按会连发 repeatCount=0 的 DOWN（中间无 UP），
            // 不能靠时间戳（会被不断刷新），只能靠这个状态。
            if (!dirKeyDown) {
                dirKeyDown = true
                dirDownAt = now
                dirHold = 1
                longPressFired = false
            } else {
                dirHold++
            }
            return when {
                dirHold >= 5 -> {
                    if (!longPressFired) {
                        longPressFired = true
                        jumpChapter(dir)
                        resetTap()
                    }
                    true
                }
                else -> true
            }
        }

        dirKeyDown = false
        if (longPressFired) return true
        val dur = now - dirDownAt
        val rapid = registerTap(keyName, now)
        if (dur >= 600 || rapid >= 5) {
            jumpChapter(dir)
            resetTap()
        } else {
            nextPageOrChapter(dir)
        }
        return true
    }

    /** 按下状态（用于识别"长按连发 DOWN"的遥控器） */
    private var dirKeyDown = false
    private var dirHold = 0
    private var centerKeyDown = false
    private var centerHold = 0

    /** 中心键/方向键按下时刻 */
    private var centerDownAt = 0L
    private var dirDownAt = 0L

    /** 快速连按统计：同键 500ms 内的连续抬起计数 */
    private var tapKeyName = ""
    private var tapCount = 0
    private var tapLastAt = 0L

    private fun registerTap(name: String, now: Long): Int {
        if (name == tapKeyName && now - tapLastAt < 400) {
            tapCount++
        } else {
            tapKeyName = name
            tapCount = 1
        }
        tapLastAt = now
        return tapCount
    }

    private fun resetTap() {
        tapCount = 0
        tapKeyName = ""
        tapLastAt = 0L
    }

    init {
        val bg = Theme.bgColor(activity)
        setBackgroundColor(bg)
        val barFg = Theme.fgColor(bg)

        // ---- 顶栏（浮层，不挤压正文）----
        titleText = TextView(activity).apply {
            textSize = 18f
            setTextColor(barFg)
            maxLines = 1
        }
        topBarHint = TextView(activity).apply {
            text = "提示：再按一次返回键直接回书架"
            textSize = 13f
            setTextColor(Theme.secondaryColor(bg))
            setPadding(dip(24), 0, dip(24), dip(6))
            visibility = GONE
        }
        val topBarButtons = listOf(
            barButton("返回阅读", barFg) { hideTopBar() },
            barButton("目录", barFg) { toggleCatalog(true) },
            barButton("注释", barFg) { requestNotes() },
            barButton("滚动", barFg) { toggleAutoScroll() },
            barButton("字号", barFg) { activity.cycleFontSize() },
            barButton("设置", barFg) { activity.showThemePanel() },
        )
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = ""
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            topBarButtons.forEach { addView(it) }
        }
        topBarFocusables = topBarButtons
        topBar = FrameLayout(activity).apply {
            setBackgroundColor(if (Theme.isDark(bg)) 0xF2181818.toInt() else 0xF2F2EDE2.toInt())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBarHint)
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dip(24), dip(8), dip(24), dip(10))
                    addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(btnRow)
                })
            })
            visibility = GONE
        }

        // ---- WebView ----
        webView = WebView(activity).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    applyZoom()
                    applyLayoutPrefs()
                    applyFont()
                    webView.evaluateJavascript(
                        "window.setScrollMode(${Theme.autoScroll(activity)})", null,
                    )
                    pendingContent?.let { (b, s) -> deliver(b, s) }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (!u.startsWith("https://wc-reader.local/fonts/")) return null
                    val name = u.substringAfterLast('/')
                    return try {
                        val stream = activity.assets.open("fonts/$name")
                        val mime = when {
                            name.endsWith(".woff2") -> "font/woff2"
                            name.endsWith(".woff") -> "font/woff"
                            name.endsWith(".otf") -> "font/otf"
                            else -> "font/ttf"
                        }
                        android.webkit.WebResourceResponse(
                            mime, null, stream,
                        ).apply {
                            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let { android.util.Log.i("ReaderJS", it.message()) }
                    return true
                }
            }
        }

        loadingText = TextView(activity).apply {
            text = "加载中..."
            textSize = 20f
            setTextColor(Theme.secondaryColor(bg))
            gravity = Gravity.CENTER
            visibility = GONE
        }

        val contentWrap = FrameLayout(activity).apply {
            addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(loadingText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(contentWrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- 四角信息（时间 / 章节 / 书名 / 进度）----
        fun hudText(g: Int): TextView = TextView(activity).apply {
            textSize = 13f
            setTextColor(Theme.secondaryColor(bg))
            alpha = 0.72f
            gravity = g
            setPadding(dip(30), dip(16), dip(30), dip(16))
            isFocusable = false
            isClickable = false
        }
        hudTopLeft = hudText(Gravity.START)
        hudTopRight = hudText(Gravity.END)
        hudBottomLeft = hudText(Gravity.START)
        hudBottomRight = hudText(Gravity.END)
        val hudLayer = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
            addView(hudTopLeft, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(hudTopRight, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            addView(hudBottomLeft, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            addView(hudBottomRight, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }
        addView(hudLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshHudVisibility()
        mainHandler.postDelayed(clockRunnable, 30_000)
        mainHandler.postDelayed(autoSaveRunnable, 20_000)

        // ---- 目录浮层（书名独立置顶，不随章节滚动）----
        val catBg = if (Theme.isDark(bg)) 0xF0141414.toInt() else 0xF0FAF7F0.toInt()
        catalogTitle = TextView(activity).apply {
            text = book.title
            textSize = 20f
            setTextColor(Theme.accentColor(bg))
            setPadding(dip(24), dip(20), dip(24), dip(12))
        }
        catalogList = ListView(activity).apply {
            adapter = catalogAdapter
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dip(16), 0, dip(16), dip(24))
        }
        catalogList.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                toggleCatalog(false)
                loadChapter(position, 0)
            }
        fastJumpBanner = TextView(activity).apply {
            text = "长按 ↑↓ 10 秒：快速跳跃（100 章/次）"
            textSize = 13f
            setTextColor(Theme.secondaryColor(bg))
            setPadding(dip(24), dip(10), dip(24), dip(16))
        }
        catalogOverlay = FrameLayout(activity).apply {
            visibility = GONE
            setBackgroundColor(0x99000000.toInt())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(catBg)
                addView(catalogTitle)
                addView(catalogList, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ))
                addView(fastJumpBanner)
            }, LayoutParams(dip(620), LayoutParams.MATCH_PARENT, Gravity.START))
            setOnClickListener { toggleCatalog(false) }
        }

        addView(catalogOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- 注释浮层（长按 OK 打开）：书单 + 详情两层 ----
        val notesBg = if (Theme.isDark(bg)) 0xF0141414.toInt() else 0xF0FAF7F0.toInt()
        notesList = ListView(activity).apply {
            adapter = notesAdapter
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dip(16), 0, dip(16), dip(24))
        }
        notesList.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                notes.getOrNull(position)?.let { showNoteDetail(it.second) }
            }
        val notesTitle = TextView(activity).apply {
            text = "本章注释"
            textSize = 20f
            setTextColor(Theme.accentColor(bg))
            setPadding(dip(24), dip(20), dip(24), dip(12))
        }
        val notesHint = TextView(activity).apply {
            text = "OK 查看 · 返回键关闭"
            textSize = 13f
            setTextColor(Theme.secondaryColor(bg))
            setPadding(dip(24), dip(4), dip(24), dip(8))
        }
        notesDetailText = TextView(activity).apply {
            textSize = 17f
            setTextColor(Theme.fgColor(bg))
            setLineSpacing(0f, 1.6f)
            setPadding(dip(36), dip(28), dip(36), dip(28))
            gravity = Gravity.START
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        notesScroll = ScrollView(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(notesBg)
            setPadding(dip(8), dip(8), dip(8), dip(8))
            addView(notesDetailText)
        }
        notesDetail = FrameLayout(activity).apply {
            visibility = GONE
            setBackgroundColor(0xCC000000.toInt())
            // 居中卡片：屏幕 72% × 62%，不会顶到屏幕边缘被返回栏遮挡
            addView(notesScroll, LayoutParams(
                (screenW * 0.72f).toInt(), (screenH * 0.62f).toInt(), Gravity.CENTER,
            ))
        }
        notesOverlay = FrameLayout(activity).apply {
            visibility = GONE
            setBackgroundColor(0x99000000.toInt())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(notesBg)
                addView(notesTitle)
                addView(notesHint)
                addView(notesList, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ))
            }, LayoutParams((screenW * 0.52f).toInt(), LayoutParams.MATCH_PARENT, Gravity.START))
            addView(notesDetail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(notesOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        webView.loadDataWithBaseURL(
            "https://weread.qq.com/", pageHtml(bg, Theme.readerTextColor(activity)), "text/html", "utf-8", null,
        )
    }

    /** 胶囊按钮：透明底 + 焦点强调色填充，与书架/设置风格统一 */
    private fun barButton(text: String, fgColor: Int, tag: String? = null, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            if (tag != null) this.tag = tag
            isFocusable = true
            isFocusableInTouchMode = true
            textSize = 16f
            setTextColor(fgColor)
            setPadding(dip(22), dip(9), dip(22), dip(9))
            background = GradientDrawable().apply {
                cornerRadius = dip(20).toFloat()
                setColor(0x14FFFFFF)
                setStroke(dip(1), 0x33FFFFFF)
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f).setDuration(110).start()
                v.background = GradientDrawable().apply {
                    cornerRadius = dip(20).toFloat()
                    if (hasFocus) {
                        setColor(0xFF4A90D9.toInt())
                        setStroke(dip(1), 0x66FFFFFF)
                    } else {
                        setColor(0x14FFFFFF)
                        setStroke(dip(1), 0x33FFFFFF)
                    }
                }
                (v as TextView).setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else fgColor)
            }
        }

    // ---------- 数据 ----------

    fun setChapters(list: List<Chapter>) {
        chapters = list
        catalogAdapter.notifyDataSetChanged()
    }

    fun openAt(chapterUid: Long, chapterOffset: Int, percent: Float = 0f) {
        val idx = chapters.indexOfFirst { it.chapterUid == chapterUid }
        loadChapter(if (idx >= 0) idx else 0, chapterOffset, percent)
    }

    fun nextChapterUid(): Long? =
        chapters.getOrNull(currentChapterIdx + 1)?.chapterUid

    fun showChapterContent(content: ChapterContent, seq: Int) {
        if (seq != loadSeq) return
        contentFormat = content.format
        notes = extractNotesFromHtml(content.html)
        notesAdapter.notifyDataSetChanged()
        val body = extractBody(content.html)
        pendingContent = body to content.style
        if (pageLoaded) deliver(body, content.style)
    }

    /** 从章节 HTML 中提取脚注：
     *  ① img.qqreader-footnote 的 alt 文本（《理想国》这类）
     *  ② p.ft 章尾注释块（《与拉玛相会》这类，正文用 a.fr 锚点引用）
     */
    private fun extractNotesFromHtml(html: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        // ② 章尾注释块
        val ftRegex = Regex(
            "<p[^>]*class=\"[^\"]*\\bft\\b[^\"]*\"[^>]*>(.*?)</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in ftRegex.findAll(html)) {
            val inner = m.groupValues[1]
            val marker = Regex("\\[(\\d+)\\]").find(inner)?.groupValues?.get(1) ?: ""
            val text = inner
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\[\\d+\\]"), "")
                .trim()
            if (text.isNotEmpty()) out.add(out.size to text)
        }
        // ① 行内图片注释（alt 文本）
        val imgRegex = Regex("<img[^>]*qqreader-footnote[^>]*>", RegexOption.IGNORE_CASE)
        for (m in imgRegex.findAll(html)) {
            val alt = Regex("alt=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1) ?: ""
            if (alt.isNotEmpty()) out.add(out.size to alt)
        }
        return out
    }

    private fun extractBody(html: String): String {
        var h = html
        val xmlEnd = h.indexOf("?>")
        if (h.trimStart().startsWith("<?xml") && xmlEnd >= 0) h = h.substring(xmlEnd + 2)
        val lower = h.lowercase()
        val bodyStart = lower.indexOf("<body")
        if (bodyStart < 0) return h
        val contentStart = lower.indexOf(">", bodyStart) + 1
        val bodyEnd = lower.lastIndexOf("</body>")
        if (contentStart <= 0 || bodyEnd <= contentStart) return h
        return h.substring(contentStart, bodyEnd)
    }

    private fun deliver(body: String, style: String) {
        pendingContent = null
        loadingText.visibility = GONE
        webView.evaluateJavascript(
            "window.loadChapterContent(${jsStr(body)}, ${jsStr(style)})", null,
        )
    }

    fun showChapterError(msg: String) {
        loadingText.visibility = VISIBLE
        loadingText.text = "加载失败: $msg"
    }

    fun setLoading(loading: Boolean) {
        loadingText.visibility = if (loading) VISIBLE else GONE
        if (loading) loadingText.text = "加载中..."
    }

    fun loadChapter(idx: Int, resumeOffset: Int, resumePercent: Float = 0f) {
        if (idx < 0 || idx >= chapters.size) return
        if (currentChapterIdx != idx && chapters.isNotEmpty()) saveProgress()
        currentChapterIdx = idx
        pendingOffset = if (resumeOffset > 0) resumeOffset else -1
        pendingPercent = resumePercent
        lastOffset = pendingOffset
        lastPercent = pendingPercent
        loadSeq++
        setLoading(true)
        titleText.text = "${book.title} · ${chapters[idx].title}"
        activity.loadChapterContent(chapters[idx].chapterUid, loadSeq)
    }

    fun onChapterLoadFinished() {
        chapterLoading = false
    }
    private var chapterLoading = false

    fun currentChapter(): Chapter? = chapters.getOrNull(currentChapterIdx)

    /** 捕获当前位置（字符偏移 + 章节百分比） */
    private fun capturePosition(onResult: (Int, Float) -> Unit) {
        webView.evaluateJavascript("window.capturePosition()") { raw ->
            val t = raw?.trim()?.trim('"') ?: ""
            val parts = t.split(",")
            val off = parts.getOrNull(0)?.trim()?.toFloatOrNull()?.toInt() ?: -1
            val pct = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f
            lastOffset = off
            lastPercent = pct
            onResult(off, pct)
        }
    }

    fun applyZoom() {
        capturePosition { off, pct ->
            webView.evaluateJavascript(
                "window.setZoom(${activity.fontSizeSp}, $off, $pct)", null,
            )
        }
        mainHandler.postDelayed({ refreshHud() }, 350)
    }

    /** 应用阅读字体与加粗（系统/思源宋体/霞鹜文楷/霞鹜新晰黑/等宽） */
    fun applyFont() {
        val family = when (Theme.readerFont(activity)) {
            "serif" -> "'WC-Serif', serif"
            "sans" -> "'WC-Sans', sans-serif"
            "kai" -> "'WC-Kai', serif"
            "xihei" -> "'WC-XiHei', sans-serif"
            "yozai" -> "'WC-Yozai', serif"
            "glow" -> "'WC-Glow', sans-serif"
            "smiley" -> "'WC-Smiley', sans-serif"
            "mono" -> "monospace"
            else -> ""
        }
        val bold = Theme.readerBold(activity)
        capturePosition { off, pct ->
            webView.evaluateJavascript(
                "window.setFontFamily(${jsStr(family)}, $bold, $off, $pct)", null,
            )
        }
    }

    fun applyTheme() {
        val bg = Theme.bgColor(activity)
        val fg = Theme.readerTextColor(activity)
        setBackgroundColor(bg)
        topBar.setBackgroundColor(if (Theme.isDark(bg)) 0xF2181818.toInt() else 0xF2F2EDE2.toInt())
        titleText.setTextColor(Theme.fgColor(bg))
        val sec = Theme.secondaryColor(bg)
        hudTopLeft.setTextColor(sec)
        hudTopRight.setTextColor(sec)
        hudBottomLeft.setTextColor(sec)
        hudBottomRight.setTextColor(sec)
        updateHudStyle()
        webView.evaluateJavascript(
            "window.setTheme('${Theme.toHex(bg)}', '${Theme.toHex(fg)}')", null,
        )
        refreshHudVisibility()
    }

    fun applyLayoutPrefs() {
        val band = hudBandPx()
        val topPad = Theme.marginTopPx(activity) +
            (if (topHudVisible()) band else 0)
        val botPad = Theme.marginBottomPx(activity) +
            (if (bottomHudVisible()) band else 0)
        // 四角信息与正文左右对齐
        updateHudStyle()
        val hInset = Theme.marginPx(activity)
        hudTopLeft.setPadding(hInset, dip(10), hInset, dip(4))
        hudTopRight.setPadding(hInset, dip(10), hInset, dip(4))
        hudBottomLeft.setPadding(hInset, dip(4), hInset, dip(10))
        hudBottomRight.setPadding(hInset, dip(4), hInset, dip(10))
        capturePosition { off, pct ->
            webView.evaluateJavascript(
                "window.setLayoutPrefs(${Theme.lineHeight(activity)}, ${Theme.marginPx(activity)}, $topPad, $botPad, ${Theme.instantPage(activity)}, $off, $pct)",
                null,
            )
        }
    }

    private fun topHudVisible(): Boolean =
        Theme.cornerMode(activity, 0) != Theme.CORNER_NONE ||
            Theme.cornerMode(activity, 1) != Theme.CORNER_NONE

    private fun bottomHudVisible(): Boolean =
        Theme.cornerMode(activity, 2) != Theme.CORNER_NONE ||
            Theme.cornerMode(activity, 3) != Theme.CORNER_NONE

    private fun hudBandPx(): Int =
        hudTopLeft.height.takeIf { it > 0 } ?: dip(44)

    /** 自动滚动时正文会从四角信息下面滑过，给角标加一层同色底避免重叠 */
    private fun updateHudStyle() {
        val bg = Theme.bgColor(activity)
        val showChip = Theme.autoScroll(activity)
        fun chip(): GradientDrawable? = if (!showChip) null else GradientDrawable().apply {
            setColor(if (Theme.isDark(bg)) 0xF0181818.toInt() else 0xF0F2EDE2.toInt())
            cornerRadius = dip(10).toFloat()
        }
        hudTopLeft.background = chip()
        hudTopRight.background = chip()
        hudBottomLeft.background = chip()
        hudBottomRight.background = chip()
    }

    // ---------- 自动滚动 ----------

    /** 应用/关闭连续滚动模式 */
    fun applyReaderMode(announce: Boolean = true) {
        val on = Theme.autoScroll(activity)
        updateHudStyle()
        capturePosition { off, pct ->
            webView.evaluateJavascript("window.setScrollMode($on)", null)
            mainHandler.postDelayed({
                webView.evaluateJavascript(
                    "window.restorePosition($off, $pct)",
                ) {
                    if (on) startAutoScroll()
                    refreshHud()
                }
            }, 350)
        }
        if (on && announce) activity.showToast("自动滚动已开启，按方向键可暂停")
    }

    private fun startAutoScroll() {
        if (!Theme.autoScroll(activity)) return
        val level = Theme.scrollSpeed(activity)
        val adaptive = Theme.scrollAdaptive(activity)
        val basePx = 5f + level * 8f
        val cps = 4f + level * 2.5f
        webView.evaluateJavascript(
            "window.startAutoScroll($basePx, $cps, $adaptive)", null,
        )
    }

    private fun stopAutoScroll() {
        webView.evaluateJavascript("window.stopAutoScroll()", null)
    }

    /** 滚动速度/方式变化后重新启动（从当前位置继续） */
    fun applyAutoScrollSpeed() {
        if (!Theme.autoScroll(activity)) return
        startAutoScroll()
    }

    /** 顶栏「滚动」按钮：开始/暂停自动滚动 */
    private fun toggleAutoScroll() {
        if (!Theme.autoScroll(activity)) {
            activity.showToast("请先在 设置 → 功能 中开启自动滚动")
            return
        }
        webView.evaluateJavascript("window.__autoRaf ? 1 : 0") { running ->
            val isRunning = running?.contains("1") == true
            if (isRunning) {
                stopAutoScroll()
                activity.showToast("自动滚动已暂停")
            } else {
                startAutoScroll()
                activity.showToast("自动滚动已继续")
            }
        }
    }

    // ---------- 四角信息 ----------

    private fun clockText(): String = java.text.SimpleDateFormat(
        "HH:mm", java.util.Locale.getDefault(),
    ).format(java.util.Date())

    fun refreshHudVisibility() {
        refreshHud()
    }

    /** 向 WebView 询问当前页/总页数（或滚动百分比），刷新四角信息 */
    private fun refreshHud() {
        if (Theme.autoScroll(activity)) {
            webView.evaluateJavascript("window.scrollPercent()") { info ->
                val pct = info?.trim()?.trim('"')?.toFloatOrNull() ?: 0f
                updateHud(0, 100, pct)
            }
            return
        }
        webView.evaluateJavascript("window.pageInfo()") { info ->
            val raw = info?.trim()?.trim('"') ?: return@evaluateJavascript
            val parts = raw.split(",")
            if (parts.size == 2) {
                val page = parts[0].trim().toIntOrNull() ?: 0
                val total = parts[1].trim().toIntOrNull() ?: 1
                updateHud(page, total)
            }
        }
    }

    private fun updateHud(page: Int, total: Int, scrollPct: Float = -1f) {
        setCorner(hudTopLeft, 0, page, total, scrollPct)
        setCorner(hudTopRight, 1, page, total, scrollPct)
        setCorner(hudBottomLeft, 2, page, total, scrollPct)
        setCorner(hudBottomRight, 3, page, total, scrollPct)
    }

    private fun setCorner(v: TextView, corner: Int, page: Int, total: Int, scrollPct: Float) {
        val chTotal = chapters.size.coerceAtLeast(1)
        val pages = total.coerceAtLeast(1)
        val chapterPct = if (scrollPct >= 0f) {
            scrollPct.toInt().coerceIn(0, 100)
        } else {
            ((page + 1) * 100 / pages).coerceIn(0, 100)
        }
        val frac = if (scrollPct >= 0f) scrollPct / 100f else (page + 1f) / pages
        val overall = ((currentChapterIdx + frac) / chTotal * 100f)
            .toInt().coerceIn(0, 100)
        val text = when (Theme.cornerMode(activity, corner)) {
            Theme.CORNER_TIME -> clockText()
            Theme.CORNER_CHAPTER -> "第 ${currentChapterIdx + 1} / $chTotal 章"
            Theme.CORNER_CHAPTER_NAME -> currentChapter()?.title ?: ""
            Theme.CORNER_PAGES ->
                if (scrollPct >= 0f) "本章 $chapterPct%" else "第 ${page + 1} / $pages 页"
            Theme.CORNER_CHAPTER_PERCENT -> "本章 $chapterPct%"
            Theme.CORNER_BOOK_PERCENT -> "全书 $overall%"
            Theme.CORNER_BOOK -> book.title
            else -> ""
        }
        v.text = text
        v.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    // ---------- 目录快速跳跃 ----------

    private fun handleCatalogDirection(down: Boolean) {
        val now = System.currentTimeMillis()
        val step = if (down) 1 else -1
        if (catalogHoldAt == 0L) catalogHoldAt = now
        if (!catalogFastMode && now - catalogHoldAt >= Theme.fastJumpSec(activity) * 1000L) {
            enterCatalogFastMode()
        }
        if (catalogFastMode) {
            if (now - catalogFastLastAt < 150) return
            catalogFastLastAt = now
            moveCatalogSelection(step * Theme.fastJumpChapters(activity))
        } else {
            if (now - catalogFastLastAt < 60) return
            catalogFastLastAt = now
            moveCatalogSelection(step)
        }
    }

    private fun moveCatalogSelection(delta: Int) {
        if (chapters.isEmpty()) return
        val cur = catalogList.selectedItemPosition.takeIf { it >= 0 } ?: currentChapterIdx
        val next = (cur + delta).coerceIn(0, chapters.size - 1)
        catalogList.setSelection(next)
        catalogList.requestFocus()
    }

    private fun enterCatalogFastMode() {
        catalogFastMode = true
        fastJumpBanner.text =
            "快速跳跃：每次 ${Theme.fastJumpChapters(activity)} 章 · 返回键退出"
        fastJumpBanner.setTextColor(Theme.accentColor(Theme.bgColor(activity)))
        activity.showToast(
            "快速跳跃模式：每次 ${Theme.fastJumpChapters(activity)} 章，返回键退出",
        )
    }

    private fun exitCatalogFastMode() {
        catalogFastMode = false
        resetFastBanner()
        activity.showToast("已退出快速跳跃")
    }

    private fun resetFastBanner() {
        fastJumpBanner.text =
            "长按 ↑↓ ${Theme.fastJumpSec(activity)} 秒：快速跳跃（${Theme.fastJumpChapters(activity)} 章/次）"
        fastJumpBanner.setTextColor(Theme.secondaryColor(Theme.bgColor(activity)))
    }

    // ---------- 遥控器 ----------

    private fun jumpChapter(dir: Int, toast: Boolean = true) {
        val target = currentChapterIdx + dir
        if (target in chapters.indices) {
            loadChapter(target, 0)
            if (toast) {
                val t = chapters.getOrNull(target)?.title ?: ""
                val prefix = if (dir > 0) "已跳到下一章" else "已跳到上一章"
                activity.showToast(if (t.isNotEmpty()) "$prefix：$t" else prefix)
            }
        } else if (toast) {
            activity.showToast(if (dir > 0) "已经是最后一章" else "已经是第一章")
        }
    }

    /** 翻页；章内边界继续按则跨章 */
    private fun nextPageOrChapter(dir: Int) {
        val probe = if (dir > 0) "window.tryNext()" else "window.tryPrev()"
        webView.evaluateJavascript(probe) { moved ->
            mainHandler.post {
                val isTrue = moved?.contains("true") == true
                if (!isTrue) {
                    // 向前翻到章首：加载上一章后跳到其最后一页，而不是上一章开头
                    if (dir < 0 && currentChapterIdx > 0) pendingLastPage = true
                    jumpChapter(dir, toast = false)
                } else {
                    checkChapterEnd()
                }
            }
        }
    }

    private fun checkChapterEnd() {
        webView.evaluateJavascript("window.pageInfo()") { info ->
            val raw = info?.trim()?.trim('"') ?: return@evaluateJavascript
            val parts = raw.split(",")
            if (parts.size == 2) {
                val page = parts[0].trim().toIntOrNull() ?: 0
                val total = parts[1].trim().toIntOrNull() ?: 1
                updateHud(page, total)
                saveLocalPosition()
                if (total > 0 && page >= total - 1) saveProgress()
            }
        }
    }

    /** 翻页时记录本地位置（节流），退出/换章时走 saveProgress */
    private fun saveLocalPosition(force: Boolean = false) {
        val chapter = currentChapter() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastLocalSaveAt < 900) return
        lastLocalSaveAt = now
        val chUid = chapter.chapterUid
        capturePosition { off, pct ->
            com.potaperper.wereadtv.util.LocalProgress.save(
                activity, book.bookId, chUid, off, pct,
            )
        }
    }

    private fun showTopBar() {
        stopAutoScroll()
        topBar.visibility = VISIBLE
        topBarHint.visibility = VISIBLE
        topBar.findFocus()?.clearFocus()
        // 布局可能尚未完成，延迟聚焦第一个按钮（返回阅读）
        topBar.post { topBarFocusables.firstOrNull()?.requestFocus() }
    }

    private fun hideTopBar() {
        topBarHint.visibility = GONE
        topBar.visibility = GONE
        webView.requestFocus()
        if (Theme.autoScroll(activity)) mainHandler.postDelayed({ startAutoScroll() }, 250)
    }

    private fun exitReader() {
        saveProgress()
        onBackToShelf()
    }

    fun saveProgress() {
        val chapter = currentChapter() ?: return
        val chUid = chapter.chapterUid
        // 本地进度：精确到章节内段落，供本应用恢复
        val off = lastOffset
        val pct = lastPercent
        com.potaperper.wereadtv.util.LocalProgress.save(
            activity, book.bookId, chUid, off, pct,
        )
        // 云端进度保持与官方 Web 端一致的字段语义：只更新章节，偏移/百分比传 0
        // （传自定义偏移会导致手机端无法定位，甚至回到章首）
        activity.reportProgress(chUid, 0, 0, contentFormat)
        // 再异步精确捕获一次，修正本地缓存
        capturePosition { o2, p2 ->
            com.potaperper.wereadtv.util.LocalProgress.save(
                activity, book.bookId, chUid, o2, p2,
            )
        }
    }

    // ---------- 目录 ----------

    private fun toggleCatalog(show: Boolean) {
        if (show) stopAutoScroll() else exitCatalogFastModeSilently()
        catalogOverlay.visibility = if (show) VISIBLE else GONE
        if (show) {
            resetFastBanner()
            catalogList.post {
                catalogList.setSelection(currentChapterIdx)
                catalogList.requestFocus()
            }
        } else {
            webView.requestFocus()
            if (Theme.autoScroll(activity)) mainHandler.postDelayed({ startAutoScroll() }, 250)
        }
    }

    private fun exitCatalogFastModeSilently() {
        catalogFastMode = false
        catalogHoldAt = 0L
        resetFastBanner()
    }

    inner class CatalogAdapter : BaseAdapter() {
        override fun getCount(): Int = chapters.size
        override fun getItem(position: Int): Chapter = chapters[position]
        override fun getItemId(position: Int): Long = chapters[position].chapterUid

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 16f
                setPadding(dip(20), dip(14), dip(20), dip(14))
            }
            val chapter = chapters[position]
            tv.text = "　".repeat((chapter.level - 1).coerceAtLeast(0)) + chapter.title
            val bg = Theme.bgColor(activity)
            tv.setTextColor(if (position == currentChapterIdx) Theme.accentColor(bg) else Theme.fgColor(bg))
            return tv
        }
    }

    // ---------- 注释 ----------

    private fun requestNotes() {
        if (notes.isEmpty()) {
            activity.showToast("本章没有注释")
            return
        }
        hideTopBar()  // 收起顶栏，避免遮挡注释浮窗
        stopAutoScroll()
        notesDetail.visibility = GONE
        notesOverlay.visibility = VISIBLE
        notesList.post {
            notesList.setSelection(0)
            notesList.requestFocus()
        }
    }

    private fun showNoteDetail(text: String) {
        notesDetailText.text = text
        notesDetail.visibility = VISIBLE
        notesScroll.post { notesScroll.scrollTo(0, 0) }
        notesScroll.requestFocus()
    }

    private fun notesScrollStep(): Int =
        (notesScroll.height * 0.8f).toInt().coerceAtLeast(dip(120))

    private fun dismissNotes() {
        if (notesDetail.visibility == VISIBLE) {
            notesDetail.visibility = GONE
            notesList.requestFocus()
        } else {
            notesOverlay.visibility = GONE
            webView.requestFocus()
            if (Theme.autoScroll(activity)) mainHandler.postDelayed({ startAutoScroll() }, 250)
        }
    }

    inner class NotesAdapter : BaseAdapter() {
        override fun getCount(): Int = notes.size
        override fun getItem(position: Int): Pair<Int, String> = notes[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 15f
                setPadding(dip(20), dip(14), dip(20), dip(14))
            }
            val (id, text) = notes[position]
            tv.text = "${id + 1}. ${text.take(40)}${if (text.length > 40) "…" else ""}"
            tv.setTextColor(Theme.fgColor(Theme.bgColor(activity)))
            return tv
        }
    }

    // ---------- JS 桥 ----------

    inner class Bridge {
        @JavascriptInterface
        fun onRendered(totalPages: Int) {
            mainHandler.post {
                loadingText.visibility = GONE
                when {
                    Theme.autoScroll(activity) -> {
                        // 滚动模式：按待恢复位置定位，然后继续自动滚动
                        val off = pendingOffset
                        val pct = pendingPercent
                        pendingOffset = -1
                        pendingPercent = 0f
                        webView.evaluateJavascript(
                            "window.restorePosition($off, $pct)",
                        ) {
                            if (off < 0 && pct <= 0f) {
                                lastOffset = -1
                                lastPercent = 0f
                            }
                            mainHandler.postDelayed({ startAutoScroll() }, 250)
                            refreshHud()
                        }
                    }
                    pendingLastPage -> {
                        pendingLastPage = false
                        webView.evaluateJavascript("window.gotoLast()") { refreshHud() }
                    }
                    pendingOffset >= 0 || pendingPercent > 0f -> {
                        val off = pendingOffset
                        val pct = pendingPercent
                        pendingOffset = -1
                        pendingPercent = 0f
                        webView.evaluateJavascript(
                            "window.restorePosition($off, $pct)",
                        ) {
                            lastOffset = off
                            lastPercent = pct
                            refreshHud()
                        }
                    }
                    else -> refreshHud()
                }
            }
        }

        @JavascriptInterface
        fun onScrollChapterEnd() {
            mainHandler.post {
                if (!Theme.autoScroll(activity)) return@post
                val target = currentChapterIdx + 1
                if (target in chapters.indices) {
                    loadChapter(target, 0, 0f)
                } else {
                    activity.showToast("已经是最后一章")
                }
            }
        }
    }

    // ---------- 工具 ----------

    private fun jsStr(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 资源释放 ----------

    private var released = false

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(clockRunnable)
        mainHandler.removeCallbacks(autoSaveRunnable)
        webView.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
    }
}
