package com.potaperper.wereadtv.util

import android.content.Context
import android.graphics.Color

/**
 * 主题管理：背景色（预设+自定义）、根据背景自动计算前景色。
 * 参考主流开源阅读器的配色预设。
 */
object Theme {
    const val DEFAULT_BG = 0xFF161616.toInt()

    /** 预设背景色：3 个浅色 + 3 个深色，按阅读器习惯排序 */
    val PRESETS = linkedMapOf(
        "纸白" to 0xFFFFFFFF.toInt(),
        "米黄" to 0xFFF5ECD8.toInt(),
        "护眼绿" to 0xFFCCE8CF.toInt(),
        "夜灰" to 0xFF2B2B2B.toInt(),
        "墨黑" to 0xFF141414.toInt(),
        "纯黑" to 0xFF000000.toInt(),
    )

    // ---------- 阅读偏好 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("weread", Context.MODE_PRIVATE)

    fun lineHeight(ctx: Context): Float = prefs(ctx).getFloat("lineHeight", 1.8f)
    fun setLineHeight(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("lineHeight", v).apply() }

    fun marginPx(ctx: Context): Int = prefs(ctx).getInt("marginPx", 72)
    fun setMarginPx(ctx: Context, v: Int) { prefs(ctx).edit().putInt("marginPx", v).apply() }

    /** 上下边距（px），取值 20-160 */
    fun marginYPx(ctx: Context): Int = prefs(ctx).getInt("marginYPx", 44)
    fun setMarginYPx(ctx: Context, v: Int) { prefs(ctx).edit().putInt("marginYPx", v).apply() }

    /** 翻页方式：true=瞬时跳页（跨越式），false=平滑滚动 */
    fun instantPage(ctx: Context): Boolean = prefs(ctx).getBoolean("instantPage", false)
    fun setInstantPage(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("instantPage", v).apply() }

    /** 方向映射：true=上下键翻页（左右移动焦点语义保留给目录），false=左右键翻页 */
    fun verticalPaging(ctx: Context): Boolean = prefs(ctx).getBoolean("verticalPaging", false)
    fun setVerticalPaging(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("verticalPaging", v).apply() }

    /** 长按方向键行为：true=跳到上/下一章；false=保持连续快进翻页 */
    fun longPressJump(ctx: Context): Boolean = prefs(ctx).getBoolean("longPressJump", true)
    fun setLongPressJump(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("longPressJump", v).apply() }

    /** 阅读字号（px），与 MainActivity.fontSizeSteps 配套 */
    fun fontPx(ctx: Context): Float = prefs(ctx).getFloat("fontSize", 22f)
    fun setFontPx(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("fontSize", v).apply() }

    fun bgColor(ctx: Context): Int =
        ctx.getSharedPreferences("weread", Context.MODE_PRIVATE)
            .getInt("bgColor", DEFAULT_BG)

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
