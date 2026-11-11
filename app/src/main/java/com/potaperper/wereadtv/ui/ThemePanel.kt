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
import android.widget.EditText
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
        private const val TAB_EXTRA = 2
        private const val TAB_ACCOUNT = 3
        private const val TAB_ABOUT = 4

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
    private lateinit var navExtra: TextView
    private lateinit var navAccount: TextView
    private lateinit var navAbout: TextView
    private lateinit var contentBg: ViewGroup
    private lateinit var contentLayout: ViewGroup
    private lateinit var contentExtra: ViewGroup
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
    private lateinit var textBrightnessLabel: TextView
    private lateinit var boldLabel: TextView
    private lateinit var cornerTL: TextView
    private lateinit var cornerTR: TextView
    private lateinit var cornerBL: TextView
    private lateinit var cornerBR: TextView
    private lateinit var autoUpdateLabel: TextView

    // ---- 字体 ----
    private val fontRows = mutableListOf<TextView>()
    private var selectedFont = "system"

    // ---- 额外功能控件 ----
    private lateinit var fastJumpSecLabel: TextView
    private lateinit var fastJumpChaptersLabel: TextView
    private lateinit var updateLineLabel: TextView
    private lateinit var speedTestBtn: TextView

    // ---- 背景控件 ----
    private lateinit var darkModeLabel: TextView
    private lateinit var hueSatPad: HueSatPad
    private lateinit var lightnessBar: LightnessBar
    private lateinit var inputHexBtn: TextView
    private lateinit var saveCustomBtn: TextView
    private lateinit var customSwatch: FrameLayout
    private val swatchCells = mutableListOf<Pair<Int, FrameLayout>>()
    private val swatchColors = HashMap<View, Int>()

    private var pickerAdjusting = false
    private var pickerHue = 210f
    private var pickerSat = 0.5f
    private var pickerVal = 1f

    private var contentFocused = false

    init {
        setBackgroundColor(0xD8000000.toInt())
        clipChildren = false
        clipToPadding = false

        val navBgView = navItem("背景", TAB_BG)
        val navLayoutView = navItem("排版", TAB_LAYOUT)
        val navExtraView = navItem("功能", TAB_EXTRA)
        val navAccountView = navItem("账号", TAB_ACCOUNT)
        val navAboutView = navItem("关于", TAB_ABOUT)
        navBg = navBgView
        navLayout = navLayoutView
        navExtra = navExtraView
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
            addView(navExtraView)
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
        contentExtra = buildExtraContent()
        contentAccount = buildAccountContent()
        contentAbout = buildAboutContent()

        val contentPanel = FrameLayout(activity).apply {
            setPadding(dip(36), dip(26), dip(36), dip(26))
            addView(contentBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(contentExtra, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            navExtra to TAB_EXTRA,
            navAccount to TAB_ACCOUNT,
            navAbout to TAB_ABOUT,
        )
        for ((v, idx) in items) {
            val focused = v.hasFocus()
            val active = tab == idx
            val name = when (idx) {
                TAB_BG -> "背景"
                TAB_LAYOUT -> "排版"
                TAB_EXTRA -> "功能"
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
        contentExtra.visibility = if (index == TAB_EXTRA) VISIBLE else GONE
        contentAccount.visibility = if (index == TAB_ACCOUNT) VISIBLE else GONE
        contentAbout.visibility = if (index == TAB_ABOUT) VISIBLE else GONE
        refreshNavStyles()
    }

    private val navItems: List<TextView> get() =
        listOf(navBg, navLayout, navExtra, navAccount, navAbout)

    // ---------- 背景内容 ----------

    private fun buildBgContent(): ViewGroup {
        darkModeLabel = prefRow("暗黑模式")
        val swatchRow = LinearLayout(activity).apply {
            tag = "swatchRow"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dip(10), 0, dip(6))
            clipChildren = false
            clipToPadding = false
            val entries = Theme.PRESETS.toList() + listOf("自定义" to Theme.customColor(activity))
            entries.forEachIndexed { idx, (name, color) ->
                val bgView = FrameLayout(activity).apply {
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
                            if (hasFocus) 0xFFFFFFFF.toInt() else swatchBorderColor(swatchColors[v] ?: color),
                        )
                    }
                    setOnClickListener {
                        val c = swatchColors[this] ?: color
                        syncPicker(c)
                        applyColor(c)
                    }
                }
                swatchColors[bgView] = color
                if (idx == entries.size - 1) customSwatch = bgView
                swatchCells.add(idx to bgView)
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(dip(82), ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(bgView)
                    addView(TextView(activity).apply {
                        text = name
                        textSize = 12f
                        setTextColor(TEXT_SUB)
                        gravity = Gravity.CENTER
                        setPadding(0, dip(4), 0, 0)
                    })
                }
                addView(cell)
            }
        }

        val initial = Theme.savedBgColor(activity)
        hueSatPad = HueSatPad(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, dip(210), 1f,
            ).apply { marginEnd = dip(16) }
            setColor(initial)
            onPick = { h, s -> pickerHue = h; pickerSat = s; applyPickerColor() }
        }
        lightnessBar = LightnessBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dip(64), dip(210))
            setHsv(initial)
            onPick = { v -> pickerVal = v; applyPickerColor() }
        }
        val pickerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hueSatPad)
            addView(lightnessBar)
        }

        val saveCustomBtnLocal = actionButton("保存为自定义颜色") {
            Theme.setCustomColor(activity, Theme.savedBgColor(activity))
            updateCustomSwatch()
            activity.showToast("已保存到「自定义」色块")
        }
        saveCustomBtn = saveCustomBtnLocal
        val inputHexBtnLocal = actionButton("输入色值…") { showHexDialog() }
        inputHexBtn = inputHexBtnLocal

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(sectionTitle("背景颜色"))
            addView(darkModeLabel)
            addView(swatchRow)
            addView(space(dip(18)))
            addView(sectionTitle("自定义颜色"))
            addView(pickerRow)
            addView(TextView(activity).apply {
                text = "色盘上按 OK 进入调整：左右调色相、上下调饱和；返回键退出调整。" +
                    "右侧滑块调明暗；调整结果自动生效。"
                textSize = 12f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(3).toFloat(), 1.15f)
                setPadding(dip(4), dip(10), 0, dip(8))
            })
            addView(saveCustomBtnLocal)
            addView(inputHexBtnLocal)
        }
        return ScrollView(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(col)
        }
    }

    /** 自定义色块换色后刷新边框与底色 */
    private fun updateCustomSwatch() {
        if (!::customSwatch.isInitialized) return
        val color = Theme.customColor(activity)
        swatchColors[customSwatch] = color
        customSwatch.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dip(14).toFloat()
            setStroke(dip(2), swatchBorderColor(color))
        }
    }

    /** 把当前颜色同步到色盘 / 明暗滑块 */
    private fun syncPicker(color: Int) {
        if (!::hueSatPad.isInitialized) return
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        pickerHue = hsv[0]
        pickerSat = hsv[1]
        pickerVal = hsv[2].coerceAtLeast(0.05f)
        hueSatPad.setColor(color)
        lightnessBar.setHsv(color)
    }

    private fun applyPickerColor() {
        val color = Color.HSVToColor(floatArrayOf(pickerHue, pickerSat, pickerVal))
        // 右侧明暗滑块的底色跟随色相/饱和度实时更新（色盘本身始终满亮度）
        lightnessBar.setHueSat(pickerHue, pickerSat)
        applyColor(color)
    }

    /** 色值输入放在独立弹窗里，避免误触输入法 */
    private fun showHexDialog() {
        val dialog = android.app.Dialog(
            activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen,
        )
        val input = EditText(activity).apply {
            setText(Theme.toHex(Theme.savedBgColor(activity)))
            hint = "#RRGGBB"
            setTextColor(TEXT_MAIN)
            setHintTextColor(TEXT_SUB)
            textSize = 20f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dip(18), dip(12), dip(18), dip(12))
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dip(10).toFloat()
                setStroke(dip(1), CARD_BORDER)
            }
        }
        val title = TextView(activity).apply {
            text = "输入色值"
            textSize = 20f
            setTextColor(TEXT_MAIN)
        }
        val hintView = TextView(activity).apply {
            text = "格式：#RRGGBB，例如 #4A90D9"
            textSize = 13f
            setTextColor(TEXT_SUB)
            setPadding(0, dip(8), 0, dip(16))
        }
        fun validHex(): Int? {
            val raw = input.text.toString().trim().removePrefix("#").trim()
            if (raw.length != 6) return null
            val v = raw.toLongOrNull(16) ?: return null
            return (0xFF000000L or v).toInt()
        }
        val applyBtn = actionButton("应用色值") {
            val c = validHex()
            if (c == null) {
                activity.showToast("色值格式不正确，请输入 6 位十六进制")
            } else {
                syncPicker(c)
                applyColor(c)
                dialog.dismiss()
            }
        }
        val copyBtn = actionButton("复制当前色值") {
            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("WC-reader 色值", input.text))
            activity.showToast("已复制 ${input.text}")
        }
        val cancelBtn = actionButton("取消") { dialog.dismiss() }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(36), dip(30), dip(36), dip(26))
            background = GradientDrawable().apply {
                setColor(0xFF1B1B1F.toInt())
                cornerRadius = dip(18).toFloat()
                setStroke(dip(1), 0x22FFFFFF)
            }
            addView(title)
            addView(hintView)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(space(dip(14)))
            addView(applyBtn)
            addView(copyBtn)
            addView(cancelBtn)
        }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xC8000000.toInt())
            addView(card, FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.42f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    /** 暗黑模式是独立叠加模式：不修改用户保存的背景色，关闭后自动回到原配色 */
    private fun toggleDarkMode() {
        val on = !Theme.darkMode(activity)
        Theme.setDarkMode(activity, on)
        if (!on) {
            // 回到正常模式：文字自动使用对比色，并把浓淡恢复满
            Theme.setTextBrightness(activity, 100)
        }
        refreshLayoutLabels()
        // 关闭时色盘/色值恢复为用户原先保存的颜色
        if (!on) syncPicker(Theme.savedBgColor(activity))
        activity.onDarkModeChanged()
    }

    private fun swatchBorderColor(color: Int): Int =
        if (Theme.isDark(color)) 0x33FFFFFF else 0x22000000

    // ---------- 排版内容 ----------

    private fun buildLayoutContent(): ViewGroup {
        fontSizeLabel = prefRow("字号")
        textBrightnessLabel = prefRow("字体颜色浓淡")
        boldLabel = prefRow("文字加粗")
        lineHeightLabel = prefRow("行距")
        marginLabel = prefRow("左右边距")
        marginYLabel = prefRow("上下边距")
        pageModeLabel = prefRow("翻页方式")
        dirMapLabel = prefRow("翻页方向")
        longPressLabel = prefRow("长按方向键")
        refreshLayoutLabels()

        fontRows.clear()
        selectedFont = Theme.readerFont(activity)
        val fontCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            for ((key, name) in Theme.FONTS) {
                val row = fontRow(key, name)
                fontRows.add(row)
                addView(row)
            }
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("文字"))
            addView(fontSizeLabel)
            addView(textBrightnessLabel)
            addView(boldLabel)
            addView(sectionTitle("字体样式"))
            addView(fontCol)
            addView(space(dip(10)))
            addView(sectionTitle("版式"))
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

    /** 字体选项行：用该字体渲染自己的名字，选中的前面加 ✓ */
    private fun fontRow(key: String, name: String): TextView {
        val tf = fontTypeface(key)
        return TextView(activity).apply {
            textSize = 19f
            setPadding(dip(22), dip(14), dip(22), dip(14))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dip(8) }
            if (tf != null) typeface = tf
            tag = key
            setOnClickListener {
                selectedFont = key
                Theme.setReaderFont(activity, key)
                refreshFontRows()
                activity.onFontChanged()
            }
            setOnFocusChangeListener { v, _ -> styleFontRow(v as TextView) }
            styleFontRow(this)
        }
    }

    private fun refreshFontRows() {
        for (row in fontRows) styleFontRow(row)
    }

    private fun styleFontRow(v: TextView) {
        val key = v.tag as? String ?: "system"
        val name = Theme.FONTS.firstOrNull { it.first == key }?.second ?: key
        val selected = key == selectedFont
        val focused = v.hasFocus()
        v.text = (if (selected) "✓  " else "　　") + name
        v.setTextColor(
            when {
                focused -> 0xFFFFFFFF.toInt()
                selected -> ACCENT
                else -> TEXT_MAIN
            },
        )
        v.background = GradientDrawable().apply {
            cornerRadius = dip(12).toFloat()
            setColor(if (focused) 0x2E4A90D9 else CARD_BG)
            setStroke(dip(1), if (focused) ACCENT else CARD_BORDER)
        }
    }

    /** 字体预览用 typeface；字体文件缺失时退回系统字体 */
    private fun fontTypeface(key: String): android.graphics.Typeface? = try {
        when (key) {
            "system" -> null
            "serif" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_serif.ttf")
            "sans" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_sans.ttf")
            "kai" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_kai.ttf")
            "xihei" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_xihei.ttf")
            "yozai" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_yozai.ttf")
            "glow" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_glow.ttf")
            "smiley" -> android.graphics.Typeface.createFromAsset(activity.assets, "fonts/preview_smiley.ttf")
            "mono" -> android.graphics.Typeface.MONOSPACE
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    // ---------- 额外功能内容 ----------

    private fun buildExtraContent(): ViewGroup {
        fastJumpSecLabel = prefRow("长按触发时间")
        fastJumpChaptersLabel = prefRow("每次跳跃章数")
        cornerTL = prefRow("左上角")
        cornerTR = prefRow("右上角")
        cornerBL = prefRow("左下角")
        cornerBR = prefRow("右下角")
        updateLineLabel = prefRow("下载线路")
        speedTestBtn = actionButton("测速并选择最快线路") {
            activity.speedTestUpdate { msg ->
                refreshLayoutLabels()
                activity.showToast(msg)
            }
        }
        refreshLayoutLabels()

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("目录快速跳跃"))
            addView(fastJumpSecLabel)
            addView(fastJumpChaptersLabel)
            addView(TextView(activity).apply {
                text = "在目录页按住上/下键达到设定时间后进入快速跳跃模式，" +
                    "之后每按一下跳过设定的章节数，按返回键退出。"
                textSize = 12f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(3).toFloat(), 1.15f)
                setPadding(dip(4), dip(4), dip(4), dip(18))
            })
            addView(sectionTitle("阅读页四角信息"))
            addView(cornerTL)
            addView(cornerTR)
            addView(cornerBL)
            addView(cornerBR)
            addView(TextView(activity).apply {
                text = "四个角可以分别选择显示内容，选择「无」即可隐藏。"
                textSize = 12f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(3).toFloat(), 1.15f)
                setPadding(dip(4), dip(4), dip(4), dip(18))
            })
            addView(sectionTitle("更新下载"))
            addView(updateLineLabel)
            addView(speedTestBtn)
            addView(TextView(activity).apply {
                text = "首次下载会自动测速并记住最快的线路；也可以手动测速。"
                textSize = 12f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(3).toFloat(), 1.15f)
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
            "字体颜色浓淡" -> "${Theme.textBrightness(activity)}%"
            "文字加粗" -> if (Theme.readerBold(activity)) "开启" else "关闭"
            "行距" -> "${"%.1f".format(Theme.lineHeight(activity))} 倍"
            "左右边距" -> "${Theme.marginPx(activity)} px"
            "上下边距" -> "${Theme.marginYPx(activity)} px"
            "左上角" -> cornerLabel(0)
            "右上角" -> cornerLabel(1)
            "左下角" -> cornerLabel(2)
            "右下角" -> cornerLabel(3)
            "暗黑模式" -> if (Theme.darkMode(activity)) "开启" else "关闭"
            "长按触发时间" -> "${Theme.fastJumpSec(activity)} 秒"
            "每次跳跃章数" -> "${Theme.fastJumpChapters(activity)} 章"
            "下载线路" -> com.potaperper.wereadtv.update.UpdateManager.sourceLabel(activity)
            "翻页方式" -> if (Theme.instantPage(activity)) "瞬时跳页" else "平滑滚动"
            "长按方向键" -> if (Theme.longPressJump(activity)) "跳到上/下一章" else "连续快进"
            "自动检查更新" -> if (Theme.autoCheckUpdate(activity)) "开启" else "关闭"
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
        stylePrefRow(textBrightnessLabel, "字体颜色浓淡", textBrightnessLabel.hasFocus())
        stylePrefRow(boldLabel, "文字加粗", boldLabel.hasFocus())
        stylePrefRow(lineHeightLabel, "行距", lineHeightLabel.hasFocus())
        stylePrefRow(marginLabel, "左右边距", marginLabel.hasFocus())
        stylePrefRow(marginYLabel, "上下边距", marginYLabel.hasFocus())
        stylePrefRow(pageModeLabel, "翻页方式", pageModeLabel.hasFocus())
        stylePrefRow(dirMapLabel, "翻页方向", dirMapLabel.hasFocus())
        stylePrefRow(longPressLabel, "长按方向键", longPressLabel.hasFocus())
        if (::fastJumpSecLabel.isInitialized) {
            stylePrefRow(fastJumpSecLabel, "长按触发时间", fastJumpSecLabel.hasFocus())
            stylePrefRow(fastJumpChaptersLabel, "每次跳跃章数", fastJumpChaptersLabel.hasFocus())
            stylePrefRow(cornerTL, "左上角", cornerTL.hasFocus())
            stylePrefRow(cornerTR, "右上角", cornerTR.hasFocus())
            stylePrefRow(cornerBL, "左下角", cornerBL.hasFocus())
            stylePrefRow(cornerBR, "右下角", cornerBR.hasFocus())
            if (::updateLineLabel.isInitialized) {
                stylePrefRow(updateLineLabel, "下载线路", updateLineLabel.hasFocus())
            }
        }
        if (::darkModeLabel.isInitialized) {
            stylePrefRow(darkModeLabel, "暗黑模式", darkModeLabel.hasFocus())
        }
        if (::autoUpdateLabel.isInitialized) {
            stylePrefRow(autoUpdateLabel, "自动检查更新", autoUpdateLabel.hasFocus())
        }
    }

    private fun cornerLabel(corner: Int): String {
        val cur = Theme.cornerMode(activity, corner)
        return Theme.CORNER_MODES.firstOrNull { it.first == cur }?.second ?: "无"
    }

    // ---------- 关于内容 ----------

    private fun buildAboutContent(): ViewGroup {
        val version = activity.currentVersion()
        autoUpdateLabel = prefRow("自动检查更新")
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("版本信息"))
            addView(infoLine("WC-reader　v$version", 20f, TEXT_MAIN))
            addView(infoLine("作者：Potaperper", 15f, TEXT_SUB))
            addView(infoLine("项目：github.com/Potaperper/wc-reader", 15f, TEXT_SUB))
            addView(space(dip(8)))
            addView(TextView(activity).apply {
                text = "一个为 Android TV / 机顶盒 / 投影仪设计的第三方阅读客户端。" +
                    "支持微信读书账号登录、书架、整页分页阅读、进度云同步、注释浏览，" +
                    "并针对遥控器与大屏阅读做了专门优化。\n\n本项目非官方应用，仅供个人学习与技术研究使用。"
                textSize = 14f
                setTextColor(TEXT_SUB)
                setLineSpacing(dip(4).toFloat(), 1.2f)
                setPadding(dip(4), 0, dip(4), dip(16))
            })
            addView(autoUpdateLabel)
            addView(actionButton("检查更新") {
                activity.checkForUpdate { msg -> activity.showToast(msg) }
            })
        }
        stylePrefRow(autoUpdateLabel, "自动检查更新", false)
        return ScrollView(activity).apply { addView(col) }
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
                out.add(darkModeLabel)
                contentBg.findViewsWithTag(out, "swatch_focusable")
                out.add(hueSatPad)
                out.add(lightnessBar)
                out.add(inputHexBtn)
                out.add(saveCustomBtn)
                out
            }
            TAB_LAYOUT -> buildList {
                add(fontSizeLabel)
                add(textBrightnessLabel)
                add(boldLabel)
                addAll(fontRows)
                add(lineHeightLabel)
                add(marginLabel)
                add(marginYLabel)
                add(pageModeLabel)
                add(dirMapLabel)
                add(longPressLabel)
            }
            TAB_EXTRA -> listOf(
                fastJumpSecLabel, fastJumpChaptersLabel,
                cornerTL, cornerTR, cornerBL, cornerBR,
                updateLineLabel, speedTestBtn,
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
            KeyEvent.KEYCODE_BACK -> {
                if (pickerAdjusting) {
                    pickerAdjusting = false
                    hueSatPad.adjusting = false
                    lightnessBar.adjusting = false
                    return true
                }
                val focusedView = findFocus()
                val inNav = focusedView != null && navItems.any { it === focusedView }
                if (focusedView != null && !inNav) {
                    // 先回到左侧导航，再按一次才退出设置
                    contentFocused = false
                    navBg.requestFocus()
                    return true
                }
                onDismiss()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                if (tab == TAB_LAYOUT) {
                    if (consumeAdjust(dir)) return true
                } else if (tab == TAB_EXTRA) {
                    if (consumeAdjust(dir)) return true
                } else if (tab == TAB_BG) {
                    if (pickerAdjusting && hueSatPad.hasFocus()) {
                        pickerHue = (pickerHue + dir * 2f + 360f) % 360f
                        hueSatPad.setHueSat(pickerHue, pickerSat)
                        applyPickerColor()
                        return true
                    }
                    if (pickerAdjusting && lightnessBar.hasFocus()) {
                        pickerVal = (pickerVal + dir * 0.02f).coerceIn(0.05f, 1f)
                        lightnessBar.setValue(pickerVal)
                        applyPickerColor()
                        return true
                    }
                    if (consumeAdjust(dir)) return true
                } else if (tab == TAB_ABOUT) {
                    if (autoUpdateLabel.hasFocus()) {
                        Theme.setAutoCheckUpdate(activity, !Theme.autoCheckUpdate(activity))
                        refreshLayoutLabels()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dy = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else +1
                if (tab == TAB_BG) {
                    if (pickerAdjusting && hueSatPad.hasFocus()) {
                        pickerSat = (pickerSat + dy * 0.02f).coerceIn(0f, 1f)
                        hueSatPad.setHueSat(pickerHue, pickerSat)
                        applyPickerColor()
                        return true
                    }
                    if (pickerAdjusting && lightnessBar.hasFocus()) {
                        pickerVal = (pickerVal - dy * 0.02f).coerceIn(0.05f, 1f)
                        lightnessBar.setValue(pickerVal)
                        applyPickerColor()
                        return true
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
                if (tab == TAB_BG && (hueSatPad.hasFocus() || lightnessBar.hasFocus())) {
                    pickerAdjusting = !pickerAdjusting
                    hueSatPad.adjusting = pickerAdjusting
                    lightnessBar.adjusting = pickerAdjusting
                    if (pickerAdjusting) {
                        activity.showToast("调整中：左右=色相，上下=饱和/明暗，返回键退出")
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun firstSwatch(): View? {
        val out = mutableListOf<View>()
        contentBg.findViewsWithTag(out, "swatch_focusable")
        return out.firstOrNull()
    }

    private fun consumeAdjust(dir: Int): Boolean {
        val focused = contentFocusables.firstOrNull { it.hasFocus() } ?: return false
        if (focused in fontRows) {
            moveFontSelection(dir)
            return true
        }
        when (focused) {
            fontSizeLabel -> { activity.stepFontSize(dir); refreshLayoutLabels() }
            boldLabel -> {
                Theme.setReaderBold(activity, !Theme.readerBold(activity))
                refreshLayoutLabels()
                activity.onFontChanged()
            }
            textBrightnessLabel -> {
                Theme.setTextBrightness(activity, Theme.textBrightness(activity) + dir * 5)
                refreshLayoutLabels()
                activity.onTextColorChanged()
            }
            lineHeightLabel -> adjLineHeight(if (dir > 0) 0.1f else -0.1f)
            marginLabel -> adjMargin(if (dir > 0) 8 else -8)
            marginYLabel -> adjMarginY(if (dir > 0) 8 else -8)
            cornerTL -> cycleCorner(0, dir)
            cornerTR -> cycleCorner(1, dir)
            cornerBL -> cycleCorner(2, dir)
            cornerBR -> cycleCorner(3, dir)
            pageModeLabel -> togglePageMode()
            dirMapLabel -> toggleDirMap()
            longPressLabel -> toggleLongPress()
            fastJumpSecLabel -> {
                Theme.setFastJumpSec(activity, Theme.fastJumpSec(activity) + dir)
                refreshLayoutLabels()
            }
            fastJumpChaptersLabel -> {
                Theme.setFastJumpChapters(activity, Theme.fastJumpChapters(activity) + dir * 10)
                refreshLayoutLabels()
            }
            updateLineLabel -> cycleUpdateSource(dir)
            darkModeLabel -> toggleDarkMode()
            autoUpdateLabel -> {
                Theme.setAutoCheckUpdate(activity, !Theme.autoCheckUpdate(activity))
                refreshLayoutLabels()
            }
            else -> return false
        }
        return true
    }

    /** 左右键在四角内容选项间循环 */
    private fun cycleCorner(corner: Int, dir: Int) {
        val list = Theme.CORNER_MODES
        val cur = Theme.cornerMode(activity, corner)
        val idx = list.indexOfFirst { it.first == cur }.let { if (it < 0) 0 else it }
        val next = (idx + dir + list.size) % list.size
        Theme.setCornerMode(activity, corner, list[next].first)
        refreshLayoutLabels()
        activity.onCornerInfoChanged()
    }

    /** 左右键切换更新下载线路：自动 / 直连 / 各镜像 */
    private fun cycleUpdateSource(dir: Int) {
        val modes = listOf("auto") + com.potaperper.wereadtv.update.UpdateManager.SOURCES.map { it.id }
        val cur = Theme.updateSource(activity)
        val idx = modes.indexOf(cur).let { if (it < 0) 0 else it }
        val next = (idx + dir + modes.size) % modes.size
        Theme.setUpdateSource(activity, modes[next])
        refreshLayoutLabels()
        activity.showToast("下载线路：${com.potaperper.wereadtv.update.UpdateManager.sourceLabel(activity)}")
    }

    /** 左右键在预设文字颜色间循环 */
    private fun moveFontSelection(dir: Int) {
        val idx = Theme.FONTS.indexOfFirst { it.first == selectedFont }.let { if (it < 0) 0 else it }
        val next = (idx + dir + Theme.FONTS.size) % Theme.FONTS.size
        selectedFont = Theme.FONTS[next].first
        Theme.setReaderFont(activity, selectedFont)
        refreshFontRows()
        activity.onFontChanged()
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

    private fun applyColor(color: Int, keepDarkMode: Boolean = false) {
        if (!keepDarkMode && Theme.darkMode(activity)) {
            Theme.setDarkMode(activity, false)
            refreshLayoutLabels()
        }
        Theme.setBgColor(activity, color)
        onChanged(color)
    }

    private fun space(h: Int) = FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 连续色盘：横轴色相（0-360°），纵轴饱和度（0-1），明度由右侧滑块控制。
     */
    class HueSatPad(ctx: android.content.Context) : View(ctx) {
        var onPick: ((Float, Float) -> Unit)? = null
        var hue = 210f
            private set
        var sat = 0.5f
            private set

        /** 调整模式：按 OK 进入，方向键才生效 */
        var adjusting = false
            set(v) {
                field = v
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            tag = "hueSat"
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(0xFF232329.toInt())
                cornerRadius = dip(12).toFloat()
                setStroke(dip(1), 0xFF2E2E36.toInt())
            }
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        fun setColor(color: Int) {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hue = hsv[0]
            sat = hsv[1]
            invalidate()
        }

        fun setHueSat(h: Float, s: Float) {
            hue = h
            sat = s
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = dip(10)
            val l = pad.toFloat()
            val t = pad.toFloat()
            val r = width.toFloat() - pad
            val b = height.toFloat() - pad
            // 色相渐变
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                l, t, r, t,
                intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                    Color.BLUE, Color.MAGENTA, Color.RED,
                ),
                null, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(l, t, r, b, dip(8).toFloat(), dip(8).toFloat(), paint)
            // 饱和度：顶部白 → 底部透明
            paint.shader = android.graphics.LinearGradient(
                l, t, l, b,
                Color.WHITE, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(l, t, r, b, dip(8).toFloat(), dip(8).toFloat(), paint)
            paint.shader = null
            // 选中点
            val cx = l + (r - l) * hue / 360f
            val cy = t + (b - t) * sat
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dip(3).toFloat()
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dip(9).toFloat(), paint)
            paint.strokeWidth = dip(1.5f).toFloat()
            paint.color = Color.BLACK
            canvas.drawCircle(cx, cy, dip(11).toFloat(), paint)
            // 焦点框
            if (isFocused) {
                paint.strokeWidth = dip(if (adjusting) 4 else 2).toFloat()
                paint.color = if (adjusting) 0xFF7DBBFF.toInt() else 0xFF4A90D9.toInt()
                canvas.drawRoundRect(
                    dip(1).toFloat(), dip(1).toFloat(),
                    width - dip(1).toFloat(), height - dip(1).toFloat(),
                    dip(12).toFloat(), dip(12).toFloat(), paint,
                )
            }
            paint.style = Paint.Style.FILL
        }

        private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    }

    /**
     * 竖直深浅滑块：顶部最亮，底部纯黑。
     */
    class LightnessBar(ctx: android.content.Context) : View(ctx) {
        var onPick: ((Float) -> Unit)? = null
        private var hue = 210f
        private var sat = 0.5f
        var value = 1f
            private set

        var adjusting = false
            set(v) {
                field = v
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            tag = "lightBar"
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(0xFF232329.toInt())
                cornerRadius = dip(12).toFloat()
                setStroke(dip(1), 0xFF2E2E36.toInt())
            }
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        fun setHsv(color: Int) {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2].coerceIn(0.05f, 1f)
            invalidate()
        }

        /** 只更新底色（色相/饱和度），保持当前明度 */
        fun setHueSat(h: Float, s: Float) {
            hue = h
            sat = s
            invalidate()
        }

        fun setValue(v: Float) {
            value = v.coerceIn(0.05f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = dip(10)
            val l = pad.toFloat()
            val t = pad.toFloat()
            val r = width.toFloat() - pad
            val b = height.toFloat() - pad
            val full = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                l, t, l, b, full, Color.BLACK, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(l, t, r, b, dip(8).toFloat(), dip(8).toFloat(), paint)
            paint.shader = null
            // 当前值指示
            val cy = t + (b - t) * (1f - value)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dip(3).toFloat()
            paint.color = Color.WHITE
            canvas.drawLine(l - dip(2), cy, r + dip(2), cy, paint)
            paint.color = Color.BLACK
            paint.strokeWidth = dip(1).toFloat()
            canvas.drawLine(l - dip(3), cy + dip(2), r + dip(3), cy + dip(2), paint)
            if (isFocused) {
                paint.strokeWidth = dip(if (adjusting) 4 else 2).toFloat()
                paint.color = if (adjusting) 0xFF7DBBFF.toInt() else 0xFF4A90D9.toInt()
                canvas.drawRoundRect(
                    dip(1).toFloat(), dip(1).toFloat(),
                    width - dip(1).toFloat(), height - dip(1).toFloat(),
                    dip(12).toFloat(), dip(12).toFloat(), paint,
                )
            }
            paint.style = Paint.Style.FILL
        }

        private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    }
}
