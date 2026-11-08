package com.potaperper.wereadtv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.potaperper.wereadtv.util.Theme

/**
 * 设置面板：左侧栏（背景/排版/账号）+ 右侧内容区，浮层叠加不挤压底层。
 * 全遥控器操作：上下切换左栏项，OK 进入右侧，左右调值，返回退出。
 */
class ThemePanel(
    private val activity: MainActivity,
    private val initialTab: Int = 0,
    private val onChanged: (Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(activity) {

    companion object {
        private const val TAB_BG = 0
        private const val TAB_LAYOUT = 1
        private const val TAB_ACCOUNT = 2
    }

    private var tab = initialTab
    private lateinit var navBg: TextView
    private lateinit var navLayout: TextView
    private lateinit var navAccount: TextView
    private lateinit var contentBg: ViewGroup
    private lateinit var contentLayout: ViewGroup
    private lateinit var contentAccount: ViewGroup

    // ---- 排版控件 ----
    private lateinit var fontSizeLabel: TextView
    private lateinit var lineHeightLabel: TextView
    private lateinit var marginLabel: TextView
    private lateinit var marginYLabel: TextView
    private lateinit var pageModeLabel: TextView
    private lateinit var dirMapLabel: TextView
    private lateinit var longPressLabel: TextView

    /** 左栏导航焦点 */
    private var navIndex = 0

    /** 右侧内容是否持有焦点 */
    private var contentFocused = false

    /** 内容区焦点序号（按页签不同含义不同） */
    private var itemIndex = 0

    private val navItems: List<TextView>
        get() = listOf(navBg, navLayout, navAccount)

    init {
        setBackgroundColor(0xD8000000.toInt())  // 半透明全屏遮罩，底层内容仍隐约可见
        clipChildren = false
        clipToPadding = false

        // ---- 左栏 ----
        navBg = navItem("背景", TAB_BG)
        navLayout = navItem("排版", TAB_LAYOUT)
        navAccount = navItem("账号", TAB_ACCOUNT)

        val navTitle = TextView(activity).apply {
            text = "设置"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dip(24), dip(28), dip(24), dip(16))
        }
        val navCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(navTitle)
            addView(navBg)
            addView(navLayout)
            addView(navAccount)
        }
        val navPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2151515.toInt())
            addView(navCol)
            addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        }

        // ---- 右侧内容 ----
        contentBg = buildBgContent()
        contentLayout = buildLayoutContent()
        contentAccount = buildAccountContent()

        val contentPanel = FrameLayout(activity).apply {
            setBackgroundColor(0xF5111111.toInt())
            setPadding(dip(36), dip(24), dip(36), dip(24))
            addView(contentBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentAccount, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(navPanel, LinearLayout.LayoutParams(dip(240), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(contentPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        addView(root, LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt(),
            Gravity.CENTER,
        ))

        showTab(initialTab)
        post {
            if (initialTab == TAB_ACCOUNT) navAccount.requestFocus() else navBg.requestFocus()
        }
    }

    private fun navItem(text: String, index: Int): TextView = TextView(activity).apply {
        this.text = text
        textSize = 18f
        setPadding(dip(24), dip(16), dip(24), dip(16))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) { showTab(index); contentFocused = false }
            refreshNavStyles()
        }
        setOnClickListener { requestFocus() }
    }

    /** 当前页签常驻绿框；聚焦时再叠加高亮与箭头 */
    private fun refreshNavStyles() {
        val items = listOf(navBg to TAB_BG, navLayout to TAB_LAYOUT, navAccount to TAB_ACCOUNT)
        for ((v, idx) in items) {
            val focused = v.hasFocus()
            val active = tab == idx
            val name = when (idx) {
                TAB_BG -> "背景"
                TAB_LAYOUT -> "排版"
                else -> "账号"
            }
            v.text = if (focused) "▶ $name" else name
            v.setTextColor(
                when {
                    focused -> 0xFFFFFFFF.toInt()
                    active -> 0xFF7ED694.toInt()
                    else -> 0xFFAAAAAA.toInt()
                },
            )
            v.background = GradientDrawable().apply {
                cornerRadius = dip(8).toFloat()
                setColor(if (focused) 0x404CAF50.toInt() else 0x00000000)
                setStroke(if (active || focused) dip(2) else 0, if (focused) 0xFFFFFFFF.toInt() else 0xFF4CAF50.toInt())
            }
        }
    }

    private fun showTab(index: Int) {
        tab = index
        contentBg.visibility = if (index == TAB_BG) VISIBLE else GONE
        contentLayout.visibility = if (index == TAB_LAYOUT) VISIBLE else GONE
        contentAccount.visibility = if (index == TAB_ACCOUNT) VISIBLE else GONE
        itemIndex = 0
        refreshNavStyles()
    }

    // ---------- 背景内容 ----------

    private fun buildBgContent(): ViewGroup {
        val title = sectionTitle("背景颜色")
        val swatchRow = LinearLayout(activity).apply {
            tag = "swatchRow"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // 左右留出放大余量，避免首个/末个色块聚焦放大时被裁切
            setPadding(dip(28), dip(10), dip(28), dip(6))
            clipChildren = false
            clipToPadding = false
            var i = 0
            for ((name, color) in Theme.PRESETS) {
                val idx = i
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(dip(88), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dip(2) }
                    addView(FrameLayout(activity).apply {
                        tag = "swatch_focusable"
                        background = GradientDrawable().apply {
                            setColor(color)
                            cornerRadius = dip(12).toFloat()
                            setStroke(dip(1), 0x66FFFFFF)
                        }
                        layoutParams = LinearLayout.LayoutParams(dip(64), dip(64))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.animate().scaleX(if (hasFocus) 1.10f else 1f)
                                .scaleY(if (hasFocus) 1.10f else 1f).setDuration(100).start()
                        }
                        setOnClickListener { applyColor(color) }
                    })
                    addView(TextView(activity).apply {
                        text = name
                        textSize = 12f
                        setTextColor(0xFFAAAAAA.toInt())
                        gravity = Gravity.CENTER
                    })
                }
                addView(cell)
                i++
            }
        }

        val customTitle = sectionTitle("自定义颜色（2D 色盘：上下左右调色，OK 应用）")
        val palette = Palette2DView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dip(560), dip(300))
            onColorPicked = { applyColor(it) }
            isFocusable = true
            isFocusableInTouchMode = true
        }

        return ScrollView(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(title)
                addView(swatchRow)
                addView(space(dip(18)))
                addView(customTitle)
                addView(palette)
            })
        }
    }

    // ---------- 排版内容 ----------

    private fun buildLayoutContent(): ViewGroup {
        fontSizeLabel = prefRow("字号")
        lineHeightLabel = prefRow("行距")
        marginLabel = prefRow("左右边距")
        marginYLabel = prefRow("上下边距")
        pageModeLabel = prefRow("翻页方式")
        dirMapLabel = prefRow("翻页方向")
        longPressLabel = prefRow("长按方向键")
        refreshLayoutLabels()

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("排版调整（聚焦后左右键调整）"))
            addView(space(dip(10)))
            addView(fontSizeLabel)
            addView(lineHeightLabel)
            addView(marginLabel)
            addView(marginYLabel)
            addView(pageModeLabel)
            addView(dirMapLabel)
            addView(longPressLabel)
            addView(space(dip(12)))
            addView(TextView(activity).apply {
                text = "上下键切换选项；更改立即生效"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
            })
        }
        return ScrollView(activity).apply { addView(col) }
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFF888888.toInt())
        setPadding(0, 0, 0, dip(12))
    }

    private fun prefRow(name: String): TextView = TextView(activity).apply {
        textSize = 19f
        setPadding(dip(20), dip(18), dip(20), dip(18))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { v, hasFocus -> stylePrefRow(v as TextView, name, hasFocus) }
    }

    private fun stylePrefRow(v: TextView, name: String, focused: Boolean) {
        val value = when (name) {
            "字号" -> "${activity.fontSizeSp.toInt()} px"
            "行距" -> "${"%.1f".format(Theme.lineHeight(activity))} 倍"
            "左右边距" -> "${Theme.marginPx(activity)} px"
            "上下边距" -> "${Theme.marginYPx(activity)} px"
            "翻页方式" -> if (Theme.instantPage(activity)) "瞬时跳页" else "平滑滚动"
            "长按方向键" -> if (Theme.longPressJump(activity)) "跳到上/下一章" else "连续快进"
            else -> if (Theme.verticalPaging(activity)) "上下键翻页" else "左右键翻页"
        }
        v.text = "$name　　　$value"
        v.setTextColor(if (focused) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = dip(10).toFloat()
            setColor(if (focused) 0x304CAF50.toInt() else 0x00000000)
            setStroke(if (focused) dip(2) else 0, if (focused) 0xFF4CAF50.toInt() else 0)
        }
    }

    private fun refreshLayoutLabels() {
        stylePrefRow(fontSizeLabel, "字号", fontSizeLabel.hasFocus())
        stylePrefRow(lineHeightLabel, "行距", lineHeightLabel.hasFocus())
        stylePrefRow(marginLabel, "左右边距", marginLabel.hasFocus())
        stylePrefRow(marginYLabel, "上下边距", marginYLabel.hasFocus())
        stylePrefRow(pageModeLabel, "翻页方式", pageModeLabel.hasFocus())
        stylePrefRow(dirMapLabel, "翻页方向", dirMapLabel.hasFocus())
        stylePrefRow(longPressLabel, "长按方向键", longPressLabel.hasFocus())
    }

    // ---------- 账号内容 ----------

    private fun buildAccountContent(): ViewGroup {
        val vid = activity.accountVid()
        val info = TextView(activity).apply {
            text = "当前账号\n\nUID：${if (vid.isNotEmpty()) vid else "未登录"}"
            textSize = 18f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(dip(4), 0, 0, dip(20))
        }
        val refreshBtn = actionButton("刷新书架") { activity.refreshShelf(); onDismiss() }
        val logoutBtn = actionButton("退出登录") { activity.logout() }

        return ScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(sectionTitle("账号管理"))
                addView(info)
                addView(refreshBtn)
                addView(space(dip(10)))
                addView(logoutBtn)
            })
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        textSize = 17f
        setTextColor(0xFFEEEEEE.toInt())
        setPadding(dip(24), dip(14), dip(24), dip(14))
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        background = GradientDrawable().apply {
            cornerRadius = dip(10f).toFloat()
            setStroke(dip(2), 0xFF666666.toInt())
        }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, hasFocus ->
            background = GradientDrawable().apply {
                cornerRadius = dip(10f).toFloat()
                setStroke(dip(3), if (hasFocus) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
                setColor(if (hasFocus) 0x50888888.toInt() else 0x00000000)
            }
        }
    }

    // ---------- 焦点/按键 ----------

    /** 内容区可聚焦元素（按页签） */
    private val contentFocusables: List<View>
        get() = when (tab) {
            TAB_BG -> {
                val out = mutableListOf<View>()
                contentBg.findViewsWithTag(out, "swatch_focusable")
                val palette = contentBg.findViewWithTag<Palette2DView>("palette")
                if (palette != null) out.add(palette)
                out
            }
            TAB_LAYOUT -> listOf(fontSizeLabel, lineHeightLabel, marginLabel, marginYLabel, pageModeLabel, dirMapLabel, longPressLabel)
            else -> contentAccount.findAllFocusableViews()
        }

    private fun ViewGroup.findAllFocusableViews(): List<View> {
        val out = mutableListOf<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isFocusable) out.add(c)
            if (c is ViewGroup) out.addAll(c.findAllFocusableViews())
        }
        return out
    }

    private fun ViewGroup.findViewsWithTag(out: MutableList<View>, tag: String) {
        if (this.tag == tag) { out.add(this); return }
        // 收集带 tag 的可聚焦子项（swatch 的外层 cell 标了 tag）
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.tag == tag && c.isFocusable) { out.add(c); continue }
            if (c is ViewGroup) c.findViewsWithTag(out, tag)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { onDismiss(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                if (tab == TAB_LAYOUT) {
                    if (consumeAdjust(dir)) return true
                } else if (tab == TAB_BG) {
                    val palette = paletteView()
                    if (palette?.hasFocus() == true) {
                        palette.nudge(dir, 0); return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (tab == TAB_BG) {
                    val palette = paletteView()
                    if (palette?.hasFocus() == true) {
                        // 色盘顶部再按上：回到预设色块行
                        if (!palette.nudge(0, -1)) { firstSwatch()?.requestFocus(); return true }
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (tab == TAB_BG) {
                    val palette = paletteView()
                    if (palette?.hasFocus() == true) {
                        palette.nudge(0, +1); return true
                    }
                    // 预设色块行按 DOWN：进入色盘
                    if (palette != null) { palette.requestFocus(); return true }
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val focused = navItems.firstOrNull { it.hasFocus() }
                if (focused != null && !contentFocused) {
                    // OK 进入内容区：把焦点交给内容第一项
                    contentFocused = true
                    val list = contentFocusables
                    if (list.isNotEmpty()) { list[0].requestFocus(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun paletteView(): Palette2DView? = contentBg.findViewWithTag("palette")

    private fun firstSwatch(): View? = contentFocusables.firstOrNull { it !is Palette2DView }

    private fun consumeAdjust(dir: Int): Boolean {
        val focused = contentFocusables.firstOrNull { it.hasFocus() } ?: return false
        when (focused) {
            fontSizeLabel -> { activity.stepFontSize(dir); refreshLayoutLabels() }
            lineHeightLabel -> adjLineHeight(if (dir > 0) 0.1f else -0.1f)
            marginLabel -> adjMargin(if (dir > 0) 8 else -8)
            marginYLabel -> adjMarginY(if (dir > 0) 8 else -8)
            pageModeLabel -> togglePageMode()
            dirMapLabel -> toggleDirMap()
            longPressLabel -> toggleLongPress()
            else -> return false
        }
        return true
    }

    private fun adjLineHeight(delta: Float) {
        Theme.setLineHeight(activity, (Theme.lineHeight(activity) + delta).coerceIn(1.0f, 2.5f))
        refreshLayoutLabels()
        activity.onLayoutPrefChanged()
    }

    private fun toggleLongPress() {
        Theme.setLongPressJump(activity, !Theme.longPressJump(activity))
        refreshLayoutLabels()
    }

    private fun adjMargin(delta: Int) {
        Theme.setMarginPx(activity, (Theme.marginPx(activity) + delta).coerceIn(32, 140))
        refreshLayoutLabels()
        activity.onLayoutPrefChanged()
    }

    private fun adjMarginY(delta: Int) {
        Theme.setMarginYPx(activity, (Theme.marginYPx(activity) + delta).coerceIn(16, 160))
        refreshLayoutLabels()
        activity.onLayoutPrefChanged()
    }

    private fun togglePageMode() {
        Theme.setInstantPage(activity, !Theme.instantPage(activity))
        refreshLayoutLabels()
        activity.onLayoutPrefChanged()
    }

    private fun toggleDirMap() {
        Theme.setVerticalPaging(activity, !Theme.verticalPaging(activity))
        refreshLayoutLabels()
    }

    private fun applyColor(color: Int) {
        Theme.setBgColor(activity, color)
        onChanged(color)
    }

    private fun space(h: Int) = FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 2D 色盘：横轴色相（24 段）、纵轴明暗（8 段），格子与选中框严格对应。
     * 左右调色相，上下调明暗；上下到达边界时返回 false 让焦点离开。
     */
    class Palette2DView(ctx: Context) : View(ctx) {
        var onColorPicked: ((Int) -> Unit)? = null
        private var col = 14      // 0..SEG-1（默认蓝色附近）
        private var row = 2       // 0..ROWS-1（0 = 最亮）
        private val paint = Paint()

        companion object {
            private const val SEG = 24
            private const val ROWS = 8
            private const val BAR_RATIO = 0.28f
            private const val BAR_GAP_DP = 14
        }

        init {
            tag = "palette"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> invalidate() }
        }

        private fun barH(h: Float) = h * BAR_RATIO
        private fun gridTop(h: Float) = barH(h) + dip(BAR_GAP_DP)
        private fun gridH(h: Float) = h - gridTop(h)
        private fun colHue(c: Int) = c * 360f / SEG
        /** 第 r 行的明暗系数：0 行最亮 → 7 行最暗 */
        private fun rowT(r: Int) = 1f - r.toFloat() / (ROWS - 1)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val bh = barH(h)
            // 色相条
            for (i in 0 until SEG) {
                paint.color = Color.HSVToColor(floatArrayOf(colHue(i), 0.65f, 0.9f))
                canvas.drawRect(w * i / SEG, 0f, w * (i + 1) / SEG, bh, paint)
            }
            // 明暗网格：列与色相条同宽，行高一致
            val gt = gridTop(h)
            val gh = gridH(h)
            for (r in 0 until ROWS) {
                val t = rowT(r)
                for (c in 0 until SEG) {
                    paint.color = Color.HSVToColor(floatArrayOf(colHue(c), 0.65f * t + 0.05f, 0.9f * t + 0.08f))
                    canvas.drawRect(
                        w * c / SEG, gt + gh * r / ROWS,
                        w * (c + 1) / SEG, gt + gh * (r + 1) / ROWS, paint,
                    )
                }
            }
            // 选中指示：色相条竖线 + 网格格子的完整描边
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            val cx = w * (col + 0.5f) / SEG
            canvas.drawRect(cx - dip(2), 0f, cx + dip(2), bh, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dip(3).toFloat()
            val cellW = w / SEG
            val cellH = gh / ROWS
            val l = w * col / SEG
            val t = gt + gh * row / ROWS
            canvas.drawRect(l + dip(2), t + dip(2), l + cellW - dip(2), t + cellH - dip(2), paint)
            paint.style = Paint.Style.FILL
        }

        fun currentColor(): Int {
            val t = rowT(row)
            return Color.HSVToColor(floatArrayOf(colHue(col), 0.65f * t + 0.05f, 0.9f * t + 0.08f))
        }

        /**
         * @return true 表示已消费按键；上下到达边界时返回 false（用于让焦点离开色盘）
         */
        fun nudge(dx: Int, dy: Int): Boolean {
            if (dx != 0) {
                col = ((col + dx) % SEG + SEG) % SEG
            }
            if (dy != 0) {
                val nr = row + dy
                if (nr !in 0 until ROWS) return false
                row = nr
            }
            invalidate()
            onColorPicked?.invoke(currentColor())
            return true
        }

        private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    }
}
