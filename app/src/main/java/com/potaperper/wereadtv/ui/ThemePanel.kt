package com.potaperper.wereadtv.ui

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
 * 设置面板：左侧导航 + 右侧卡片式内容，深色统一视觉，全遥控器操作。
 * 背景：预设色块 + 2D 色盘；排版：字号/行距/边距/翻页；账号：刷新/退出。
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
        private const val TAB_ABOUT = 3

        // 统一强调色（与阅读器链接色一致）
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val PANEL_BG = 0xFF1B1B1F.toInt()
        private const val NAV_BG = 0xFF151518.toInt()
        private const val CARD_BG = 0xFF232329.toInt()
        private const val CARD_BORDER = 0xFF2E2E36.toInt()
        private const val TEXT_MAIN = 0xFFEAEAEE.toInt()
        private const val TEXT_SUB = 0xFF9A9AA2.toInt()
    }

    private var tab = initialTab
    private lateinit var navBg: TextView
    private lateinit var navLayout: TextView
    private lateinit var navAccount: TextView
    private lateinit var navAbout: TextView
    private lateinit var contentBg: ViewGroup
    private lateinit var contentLayout: ViewGroup
    private lateinit var contentAccount: ViewGroup
    private lateinit var contentAbout: ViewGroup

    // ---- 排版控件 ----
    private lateinit var fontSizeLabel: TextView
    private lateinit var lineHeightLabel: TextView
    private lateinit var marginLabel: TextView
    private lateinit var marginYLabel: TextView
    private lateinit var pageModeLabel: TextView
    private lateinit var dirMapLabel: TextView
    private lateinit var longPressLabel: TextView

    private var contentFocused = false

    init {
        setBackgroundColor(0xD8000000.toInt())
        clipChildren = false
        clipToPadding = false

        val navBgView = navItem("背景", TAB_BG)
        val navLayoutView = navItem("排版", TAB_LAYOUT)
        val navAccountView = navItem("账号", TAB_ACCOUNT)
        val navAboutView = navItem("关于", TAB_ABOUT)
        navBg = navBgView
        navLayout = navLayoutView
        navAccount = navAccountView
        navAbout = navAboutView

        val navTitle = TextView(activity).apply {
            text = "设置"
            textSize = 22f
            setTextColor(TEXT_MAIN)
            letterSpacing = 0.05f
            setPadding(dip(28), dip(30), dip(28), dip(22))
        }
        val navCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(navTitle)
            addView(navBgView)
            addView(navLayoutView)
            addView(navAccountView)
            addView(navAboutView)
        }
        val navPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAV_BG)
            addView(navCol)
        }

        contentBg = buildBgContent()
        contentLayout = buildLayoutContent()
        contentAccount = buildAccountContent()
        contentAbout = buildAboutContent()

        val contentPanel = FrameLayout(activity).apply {
            setPadding(dip(36), dip(26), dip(36), dip(26))
            addView(contentBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentAccount, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentAbout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            elevation = dip(18).toFloat()
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = dip(18).toFloat()
                setStroke(dip(1), 0x22FFFFFF)
            }
            addView(navPanel, LinearLayout.LayoutParams(dip(250), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(contentPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        addView(card, LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            (resources.displayMetrics.heightPixels * 0.84f).toInt(),
            Gravity.CENTER,
        ))

        showTab(initialTab)
        post {
            if (initialTab == TAB_ACCOUNT) navAccount.requestFocus() else navBg.requestFocus()
        }
    }

    // ---------- 左栏 ----------

    private fun navItem(text: String, index: Int): TextView = TextView(activity).apply {
        this.text = text
        textSize = 18f
        setPadding(dip(28), dip(16), dip(28), dip(16))
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dip(16); marginEnd = dip(16); bottomMargin = dip(6) }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) { showTab(index); contentFocused = false }
            refreshNavStyles()
        }
        setOnClickListener { requestFocus() }
    }

    private fun refreshNavStyles() {
        val items = listOf(
            navBg to TAB_BG,
            navLayout to TAB_LAYOUT,
            navAccount to TAB_ACCOUNT,
            navAbout to TAB_ABOUT,
        )
        for ((v, idx) in items) {
            val focused = v.hasFocus()
            val active = tab == idx
            val name = when (idx) {
                TAB_BG -> "背景"
                TAB_LAYOUT -> "排版"
                TAB_ACCOUNT -> "账号"
                else -> "关于"
            }
            v.text = name
            v.setTextColor(
                when {
                    focused -> 0xFFFFFFFF.toInt()
                    active -> ACCENT
                    else -> TEXT_SUB
                },
            )
            v.background = GradientDrawable().apply {
                cornerRadius = dip(12).toFloat()
                when {
                    focused -> {
                        setColor(0x334A90D9)
                        setStroke(dip(1), ACCENT)
                    }
                    active -> {
                        setColor(0x1A4A90D9)
                    }
                    else -> setColor(0x00000000)
                }
            }
        }
    }

    private fun showTab(index: Int) {
        tab = index
        contentBg.visibility = if (index == TAB_BG) VISIBLE else GONE
        contentLayout.visibility = if (index == TAB_LAYOUT) VISIBLE else GONE
        contentAccount.visibility = if (index == TAB_ACCOUNT) VISIBLE else GONE
        contentAbout.visibility = if (index == TAB_ABOUT) VISIBLE else GONE
        refreshNavStyles()
    }

    private val navItems: List<TextView> get() = listOf(navBg, navLayout, navAccount, navAbout)

    // ---------- 背景内容 ----------

    private fun buildBgContent(): ViewGroup {
        val swatchRow = LinearLayout(activity).apply {
            tag = "swatchRow"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dip(10), 0, dip(6))
            clipChildren = false
            clipToPadding = false
            var i = 0
            for ((name, color) in Theme.PRESETS) {
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(dip(82), ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(FrameLayout(activity).apply {
                        tag = "swatch_focusable"
                        background = GradientDrawable().apply {
                            setColor(color)
                            cornerRadius = dip(14).toFloat()
                            setStroke(dip(2), swatchBorderColor(color))
                        }
                        layoutParams = LinearLayout.LayoutParams(dip(56), dip(56))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.animate().scaleX(if (hasFocus) 1.12f else 1f)
                                .scaleY(if (hasFocus) 1.12f else 1f).setDuration(110).start()
                            (v.background as? GradientDrawable)?.setStroke(
                                dip(if (hasFocus) 3 else 2),
                                if (hasFocus) 0xFFFFFFFF.toInt() else swatchBorderColor(color),
                            )
                        }
                        setOnClickListener { applyColor(color) }
                    })
                    addView(TextView(activity).apply {
                        text = name
                        textSize = 12f
                        setTextColor(TEXT_SUB)
                        gravity = Gravity.CENTER
                        setPadding(0, dip(4), 0, 0)
                    })
                }
                addView(cell)
                i++
            }
        }

        val palette = Palette2DView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dip(220),
            ).apply { topMargin = dip(8) }
            onColorPicked = { applyColor(it) }
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(sectionTitle("背景颜色"))
            addView(swatchRow)
            addView(space(dip(18)))
            addView(sectionTitle("自定义颜色（色盘：上下左右调色）"))
            addView(palette)
        }
        return ScrollView(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(col)
        }
    }

    private fun swatchBorderColor(color: Int): Int =
        if (Theme.isDark(color)) 0x33FFFFFF else 0x22000000

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
            addView(sectionTitle("阅读排版"))
            addView(fontSizeLabel)
            addView(lineHeightLabel)
            addView(marginLabel)
            addView(marginYLabel)
            addView(space(dip(14)))
            addView(sectionTitle("翻页"))
            addView(pageModeLabel)
            addView(dirMapLabel)
            addView(longPressLabel)
            addView(space(dip(10)))
            addView(TextView(activity).apply {
                text = "聚焦后用左右键调整，更改立即生效"
                textSize = 12f
                setTextColor(TEXT_SUB)
                setPadding(dip(4), dip(4), 0, 0)
            })
        }
        return ScrollView(activity).apply { addView(col) }
    }

    private fun prefRow(name: String): TextView = TextView(activity).apply {
        textSize = 17f
        setPadding(dip(22), dip(17), dip(22), dip(17))
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dip(10) }
        setOnFocusChangeListener { v, hasFocus ->
            stylePrefRow(v as TextView, name, hasFocus)
        }
        stylePrefRow(this, name, false)
    }

    private fun stylePrefRow(v: TextView, name: String, focused: Boolean) {
        val value = when (name) {
            "字号" -> "${activity.fontSizeSp.toInt()} px"
            "行距" -> "${"%.1f".format(Theme.lineHeight(activity))} 倍"
            "左右边距" -> "${Theme.marginPx(activity)} px"
            "上下边距" -> "${Theme.marginYPx(activity)} px"
            "翻页方式" -> if (Theme.instantPage(activity)) "瞬时跳页" else "平滑滚动"
            "长按方向键" -> if (Theme.longPressJump(activity)) "跳到上/下一章" else "连续快进"
            else -> if (Theme.verticalPaging(activity)) "上下键" else "左右键"
        }
        v.text = "$name　　　$value"
        v.setTextColor(if (focused) 0xFFFFFFFF.toInt() else TEXT_MAIN)
        v.background = GradientDrawable().apply {
            cornerRadius = dip(12).toFloat()
            setColor(if (focused) 0x2E4A90D9 else CARD_BG)
            setStroke(dip(1), if (focused) ACCENT else CARD_BORDER)
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
            text = if (vid.isNotEmpty()) "当前账号  UID $vid" else "未登录"
            textSize = 15f
            setTextColor(TEXT_SUB)
            setPadding(dip(6), 0, 0, dip(22))
        }
        val refreshBtn = actionButton("刷新书架") { activity.refreshShelf(); onDismiss() }
        val logoutBtn = actionButton("退出登录") { activity.logout() }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("账号管理"))
            addView(info)
            addView(refreshBtn)
            addView(logoutBtn)
        }
        return ScrollView(activity).apply { addView(col) }
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        textSize = 17f
        setTextColor(TEXT_MAIN)
        setPadding(dip(26), dip(16), dip(26), dip(16))
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dip(10) }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, hasFocus ->
            (v as TextView).setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else TEXT_MAIN)
            v.background = GradientDrawable().apply {
                cornerRadius = dip(12).toFloat()
                setColor(if (hasFocus) 0x2E4A90D9 else CARD_BG)
                setStroke(dip(1), if (hasFocus) ACCENT else CARD_BORDER)
            }
        }
        background = GradientDrawable().apply {
            cornerRadius = dip(12).toFloat()
            setColor(CARD_BG)
            setStroke(dip(1), CARD_BORDER)
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEXT_SUB)
        letterSpacing = 0.06f
        setPadding(dip(4), 0, 0, dip(12))
    }

    // ---------- 关于内容 ----------

    private fun buildAboutContent(): ViewGroup {
        val version = activity.currentVersion()
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("关于"))
            addView(infoLine("WC-reader　v$version", 20f, TEXT_MAIN))
            addView(infoLine("作者：Potaperper", 15f, TEXT_SUB))
            addView(infoLine("项目：github.com/Potaperper/wc-reader", 15f, TEXT_SUB))
            addView(space(dip(10)))
            addView(TextView(activity).apply {
                text = "一个为 Android TV / 机顶盒 / 投影仪设计的第三方阅读客户端。" +
                    "支持微信读书账号登录、书架、整页分页阅读、进度云同步、注释浏览，" +
                    "并针对遥控器与大屏阅读做了专门优化。\n\n本项目非官方应用，仅供个人学习与技术研究使用。"
                textSize = 14f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(4).toFloat(), 1.2f)
                setPadding(dip(4), 0, dip(4), dip(20))
            })
            addView(actionButton("检查更新") {
                activity.checkForUpdate { msg -> activity.showToast(msg) }
            })
        }
        return ScrollView(activity).apply { addView(col) }
    }

    private fun infoLine(text: String, size: Float, color: Int): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(dip(4), dip(4), dip(4), dip(4))
        }

    // ---------- 焦点/按键 ----------

    private val contentFocusables: List<View>
        get() = when (tab) {
            TAB_BG -> {
                val out = mutableListOf<View>()
                contentBg.findViewsWithTag(out, "swatch_focusable")
                val palette = contentBg.findViewWithTag<Palette2DView>("palette")
                if (palette != null) out.add(palette)
                out
            }
            TAB_LAYOUT -> listOf(
                fontSizeLabel, lineHeightLabel, marginLabel, marginYLabel,
                pageModeLabel, dirMapLabel, longPressLabel,
            )
            TAB_ACCOUNT -> contentAccount.findAllFocusableViews()
            else -> contentAbout.findAllFocusableViews()
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
                        if (!palette.nudge(0, -1)) { firstSwatch()?.requestFocus(); return true }
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (tab == TAB_BG) {
                    val navFocused = navItems.any { it.hasFocus() }
                    if (!navFocused) {
                        val palette = paletteView()
                        if (palette?.hasFocus() == true) {
                            palette.nudge(0, +1); return true
                        }
                        if (palette != null) { palette.requestFocus(); return true }
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val focused = navItems.firstOrNull { it.hasFocus() }
                if (focused != null && !contentFocused) {
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
     */
    class Palette2DView(ctx: android.content.Context) : View(ctx) {
        var onColorPicked: ((Int) -> Unit)? = null
        private var col = 14
        private var row = 2
        private val paint = Paint()

        companion object {
            private const val SEG = 24
            private const val ROWS = 8
        }

        init {
            tag = "palette"
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(0xFF232329.toInt())
                cornerRadius = (10 * resources.displayMetrics.density).toInt().toFloat()
                setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF2E2E36.toInt())
            }
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        private fun colHue(c: Int) = c * 360f / SEG
        private fun rowT(r: Int) = 1f - r.toFloat() / (ROWS - 1)

        override fun onDraw(canvas: Canvas) {
            val pad = dip(12)
            val w = width.toFloat() - 2 * pad
            val h = height.toFloat() - 2 * pad
            val bh = h * 0.22f
            canvas.save()
            canvas.translate(pad.toFloat(), pad.toFloat())
            // 色相条
            for (i in 0 until SEG) {
                paint.color = Color.HSVToColor(floatArrayOf(colHue(i), 0.65f, 0.9f))
                canvas.drawRect(w * i / SEG, 0f, w * (i + 1) / SEG, bh, paint)
            }
            // 明暗网格
            val gt = bh + dip(12)
            val gh = h - gt
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
            // 选中指示
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            val cx = w * (col + 0.5f) / SEG
            canvas.drawRect(cx - dip(2), 0f, cx + dip(2), bh, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dip(3).toFloat()
            val cellW = w / SEG
            val cellH = gh / ROWS
            val l = w * col / SEG
            val t2 = gt + gh * row / ROWS
            canvas.drawRect(l + dip(2), t2 + dip(2), l + cellW - dip(2), t2 + cellH - dip(2), paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }

        fun currentColor(): Int {
            val t = rowT(row)
            return Color.HSVToColor(floatArrayOf(colHue(col), 0.65f * t + 0.05f, 0.9f * t + 0.08f))
        }

        fun nudge(dx: Int, dy: Int): Boolean {
            if (dx != 0) col = ((col + dx) % SEG + SEG) % SEG
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
