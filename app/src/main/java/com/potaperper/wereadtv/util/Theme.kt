package com.potaperper.wereadtv.util

import android.content.Context
import android.graphics.Color

/**
 * 主题管理：背景色（预设+自定义）、根据背景自动计算前景色。
 * 参考主流开源阅读器的配色预设。
 */
object Theme {
    const val DEFAULT_BG = 0xFF161616.toInt()

    /** 预设背景色：浅色 + 深色，最后一个可替换为用户保存的自定义色 */
    val PRESETS = linkedMapOf(
        "纸白" to 0xFFFFFFFF.toInt(),
        "米黄" to 0xFFF5ECD8.toInt(),
        "护眼绿" to 0xFFCCE8CF.toInt(),
        "夜灰" to 0xFF2B2B2B.toInt(),
        "纯黑" to 0xFF000000.toInt(),
    )

    /** 用户保存的自定义颜色（替代原来固定的「墨黑」格） */
    fun customColor(ctx: Context): Int = prefs(ctx).getInt("customBgColor", 0xFF141414.toInt())
    fun setCustomColor(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("customBgColor", v).apply()
    }

    // ---------- 阅读字体 ----------

    /** 字体选项：key → 显示名 */
    val FONTS = listOf(
        "system" to "系统默认",
        "serif" to "思源宋体",
        "sans" to "思源黑体",
        "kai" to "霞鹜文楷",
        "xihei" to "霞鹜新晰黑",
        "yozai" to "悠哉字体",
        "glow" to "未来荧黑",
        "smiley" to "得意黑",
        "mono" to "等宽字体",
    )

    fun readerFont(ctx: Context): String = prefs(ctx).getString("readerFont", "system") ?: "system"
    fun setReaderFont(ctx: Context, v: String) {
        prefs(ctx).edit().putString("readerFont", v).apply()
    }

    /** 正文加粗 */
    fun readerBold(ctx: Context): Boolean = prefs(ctx).getBoolean("readerBold", false)
    fun setReaderBold(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("readerBold", v).apply()
    }

    // ---------- 阅读偏好 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("weread", Context.MODE_PRIVATE)

    fun lineHeight(ctx: Context): Float = prefs(ctx).getFloat("lineHeight", 1.8f)
    fun setLineHeight(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("lineHeight", v).apply() }

    fun marginPx(ctx: Context): Int = prefs(ctx).getInt("marginPx", 72)
    fun setMarginPx(ctx: Context, v: Int) { prefs(ctx).edit().putInt("marginPx", v).apply() }

    /** 上下边距（旧字段，保留兼容） */
    fun marginYPx(ctx: Context): Int = prefs(ctx).getInt("marginYPx", 44)
    fun setMarginYPx(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("marginYPx", v).apply()
    }

    /** 上边距（px），旧版本没有该字段时回落到 marginYPx */
    fun marginTopPx(ctx: Context): Int =
        prefs(ctx).getInt("marginTopPx", prefs(ctx).getInt("marginYPx", 44))
    fun setMarginTopPx(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("marginTopPx", v.coerceIn(16, 200)).apply()
    }

    /** 下边距（px） */
    fun marginBottomPx(ctx: Context): Int =
        prefs(ctx).getInt("marginBottomPx", prefs(ctx).getInt("marginYPx", 44))
    fun setMarginBottomPx(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("marginBottomPx", v.coerceIn(16, 200)).apply()
    }

    // ---------- 自动滚动 ----------

    fun autoScroll(ctx: Context): Boolean = prefs(ctx).getBoolean("autoScroll", false)
    fun setAutoScroll(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("autoScroll", v).apply()
    }

    /** 滚动速度 1-10 */
    fun scrollSpeed(ctx: Context): Int = prefs(ctx).getInt("scrollSpeed", 3)
    fun setScrollSpeed(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("scrollSpeed", v.coerceIn(1, 10)).apply()
    }

    /** true=按可见字数自适应（阅读速度恒定），false=匀速像素滚动 */
    fun scrollAdaptive(ctx: Context): Boolean = prefs(ctx).getBoolean("scrollAdaptive", true)
    fun setScrollAdaptive(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("scrollAdaptive", v).apply()
    }

    /** 翻页方式：true=瞬时跳页（跨越式），false=平滑滚动 */
    fun instantPage(ctx: Context): Boolean = prefs(ctx).getBoolean("instantPage", false)
    fun setInstantPage(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("instantPage", v).apply() }

    /** 方向映射：true=上下键翻页（左右移动焦点语义保留给目录），false=左右键翻页 */
    fun verticalPaging(ctx: Context): Boolean = prefs(ctx).getBoolean("verticalPaging", false)
    fun setVerticalPaging(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("verticalPaging", v).apply() }

    /** 长按方向键行为：true=跳到上/下一章；false=保持连续快进翻页 */
    fun longPressJump(ctx: Context): Boolean = prefs(ctx).getBoolean("longPressJump", true)
    fun setLongPressJump(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("longPressJump", v).apply() }

    /** 正文文字颜色覆盖：-1 表示跟随背景自动 */
    fun textColorOverride(ctx: Context): Int = prefs(ctx).getInt("textColorOverride", -1)
    fun setTextColorOverride(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("textColorOverride", v).apply()
    }

    /** 阅读页四角信息显示开关 */
    fun showCornerInfo(ctx: Context): Boolean = prefs(ctx).getBoolean("cornerInfo", true)
    fun setShowCornerInfo(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("cornerInfo", v).apply()
    }

    // ---------- 四角信息内容 ----------

    const val CORNER_TIME = "time"
    const val CORNER_CHAPTER = "chapter"
    const val CORNER_CHAPTER_NAME = "chapterName"
    const val CORNER_PAGES = "pages"
    const val CORNER_CHAPTER_PERCENT = "chapterPercent"
    const val CORNER_BOOK_PERCENT = "bookPercent"
    const val CORNER_BOOK = "book"
    const val CORNER_NONE = "none"

    val CORNER_MODES = listOf(
        CORNER_TIME to "时间",
        CORNER_CHAPTER to "章节号",
        CORNER_CHAPTER_NAME to "章节名",
        CORNER_PAGES to "当前页/本章节页",
        CORNER_CHAPTER_PERCENT to "本章进度",
        CORNER_BOOK_PERCENT to "全书进度",
        CORNER_BOOK to "书名",
        CORNER_NONE to "无",
    )

    /** corner: 0=左上 1=右上 2=左下 3=右下 */
    fun cornerMode(ctx: Context, corner: Int): String {
        val def = when (corner) {
            0 -> CORNER_TIME
            1 -> CORNER_CHAPTER
            2 -> CORNER_BOOK
            else -> CORNER_BOOK_PERCENT
        }
        return prefs(ctx).getString("corner_$corner", def) ?: def
    }

    fun setCornerMode(ctx: Context, corner: Int, mode: String) {
        prefs(ctx).edit().putString("corner_$corner", mode).apply()
    }

    // ---------- 额外功能 ----------

    /** 目录长按多久进入快速跳跃（秒） */
    fun fastJumpSec(ctx: Context): Int = prefs(ctx).getInt("fastJumpSec", 10)
    fun setFastJumpSec(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("fastJumpSec", v.coerceIn(2, 30)).apply()
    }

    /** 快速跳跃模式下每次跳过的章节数 */
    fun fastJumpChapters(ctx: Context): Int = prefs(ctx).getInt("fastJumpChapters", 100)
    fun setFastJumpChapters(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("fastJumpChapters", v.coerceIn(10, 1000)).apply()
    }

    /** 正文深浅（30-100，100 = 原色） */
    fun textBrightness(ctx: Context): Int = prefs(ctx).getInt("textBrightness", 100)
    fun setTextBrightness(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("textBrightness", v.coerceIn(30, 100)).apply()
    }

    /** 暗黑模式（纯黑背景 + 浅灰字） */
    fun darkMode(ctx: Context): Boolean = prefs(ctx).getBoolean("darkMode", false)
    fun setDarkMode(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("darkMode", v).apply()
    }

    /** 启动时自动检查更新 */
    fun autoCheckUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean("autoCheckUpdate", true)
    fun setAutoCheckUpdate(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("autoCheckUpdate", v).apply()
    }

    /** 已忽略的版本（不再提示该版本） */
    fun ignoredUpdateVersion(ctx: Context): String =
        prefs(ctx).getString("ignoredUpdateVersion", "") ?: ""
    fun setIgnoredUpdateVersion(ctx: Context, v: String) {
        prefs(ctx).edit().putString("ignoredUpdateVersion", v).apply()
    }

    // ---------- 更新下载线路 ----------

    /** auto = 自动使用上次测速最快的线路；否则为具体线路 id */
    fun updateSource(ctx: Context): String =
        prefs(ctx).getString("updateSource", "auto") ?: "auto"
    fun setUpdateSource(ctx: Context, v: String) {
        prefs(ctx).edit().putString("updateSource", v).apply()
    }

    fun updateFastestSource(ctx: Context): String =
        prefs(ctx).getString("updateFastestSource", "") ?: ""
    fun setUpdateFastestSource(ctx: Context, v: String) {
        prefs(ctx).edit().putString("updateFastestSource", v).apply()
    }

    fun updateFastestLabel(ctx: Context): String =
        prefs(ctx).getString("updateFastestLabel", "") ?: ""
    fun setUpdateFastestLabel(ctx: Context, v: String) {
        prefs(ctx).edit().putString("updateFastestLabel", v).apply()
    }

    fun updateFastestSpeed(ctx: Context): Int = prefs(ctx).getInt("updateFastestSpeed", 0)
    fun setUpdateFastestSpeed(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("updateFastestSpeed", v).apply()
    }

    fun updateFastestTs(ctx: Context): Long = prefs(ctx).getLong("updateFastestTs", 0L)
    fun setUpdateFastestTs(ctx: Context, v: Long) {
        prefs(ctx).edit().putLong("updateFastestTs", v).apply()
    }

    /** 阅读正文颜色：暗黑模式固定浅灰，否则自动前景色 + 浓淡 */
    fun readerTextColor(ctx: Context): Int {
        if (darkMode(ctx)) return 0xFFBFBFBF.toInt()
        val bg = savedBgColor(ctx)
        return applyBrightness(fgColor(bg), bg, textBrightness(ctx))
    }

    /** 按百分比把文字颜色向背景混合：100 = 原色，30 = 更接近背景 */
    fun applyBrightness(fg: Int, bg: Int, percent: Int): Int {
        val t = percent.coerceIn(0, 100) / 100f
        val r = (Color.red(fg) * t + Color.red(bg) * (1 - t)).toInt()
        val g = (Color.green(fg) * t + Color.green(bg) * (1 - t)).toInt()
        val b = (Color.blue(fg) * t + Color.blue(bg) * (1 - t)).toInt()
        return Color.rgb(r, g, b)
    }

    /** 阅读字号（px），与 MainActivity.fontSizeSteps 配套 */
    fun fontPx(ctx: Context): Float = prefs(ctx).getFloat("fontSize", 22f)
    fun setFontPx(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("fontSize", v).apply() }

    /** 用户保存的背景色（不受暗黑模式影响） */
    fun savedBgColor(ctx: Context): Int =
        ctx.getSharedPreferences("weread", Context.MODE_PRIVATE)
            .getInt("bgColor", DEFAULT_BG)

    /** 实际生效的背景色：暗黑模式开启时为纯黑 */
    fun bgColor(ctx: Context): Int =
        if (darkMode(ctx)) 0xFF000000.toInt() else savedBgColor(ctx)

    fun setBgColor(ctx: Context, color: Int) {
        ctx.getSharedPreferences("weread", Context.MODE_PRIVATE)
            .edit().putInt("bgColor", color).apply()
    }

    fun isDark(color: Int): Boolean {
        val lum = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
        return lum < 128
    }

    /** 根据背景亮度自动选择正文颜色 */
    fun fgColor(bg: Int): Int = if (isDark(bg)) 0xFFD8D8D8.toInt() else 0xFF333333.toInt()

    fun secondaryColor(bg: Int): Int = if (isDark(bg)) 0xFF9A9A9A.toInt() else 0xFF666666.toInt()

    fun accentColor(bg: Int): Int = if (isDark(bg)) 0xFF4CAF50.toInt() else 0xFF2E7D32.toInt()

    fun toHex(color: Int): String = "#%06X".format(0xFFFFFF and color)
}
