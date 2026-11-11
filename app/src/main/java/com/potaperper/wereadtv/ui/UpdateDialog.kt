package com.potaperper.wereadtv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.potaperper.wereadtv.update.UpdateInfo

/**
 * 更新提示对话框（TV 遥控器操作）：
 * 下载更新 / 忽略此版本 / 不再提示。下载时显示进度条。
 */
class UpdateDialog(
    private val activity: MainActivity,
    private val info: UpdateInfo,
    private val onDownload: () -> Unit,
    private val onIgnoreVersion: () -> Unit,
    private val onDisableAutoCheck: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(activity) {

    private val accent = 0xFF4A90D9.toInt()
    private val progressBar: ProgressBar
    private val progressText: TextView
    private val buttons = mutableListOf<TextView>()
    private var downloading = false

    init {
        setBackgroundColor(0xD8000000.toInt())
        isFocusable = true

        val title = TextView(activity).apply {
            text = "发现新版本"
            textSize = 22f
            setTextColor(0xFFEAEAEE.toInt())
        }
        val versionText = TextView(activity).apply {
            text = "${info.tag}（当前 v${activity.currentVersion()}）"
            textSize = 15f
            setTextColor(accent)
            setPadding(0, dip(6), 0, 0)
        }

        val notesView = TextView(activity).apply {
            text = info.notes.trim().ifEmpty { "包含若干修复与改进。" }.take(700)
            textSize = 13f
            setTextColor(0xFF9A9AA2.toInt())
            setLineSpacing(dip(3).toFloat(), 1.15f)
            setPadding(0, dip(14), 0, 0)
        }

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dip(8),
            ).apply { topMargin = dip(16) }
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2E36.toInt())
        }
        progressText = TextView(activity).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF9A9AA2.toInt())
            gravity = Gravity.END
            setPadding(0, dip(6), 0, 0)
            visibility = GONE
        }

        val downloadBtn = button("下载更新") {
            if (!downloading) {
                downloading = true
                progressBar.visibility = VISIBLE
                progressText.visibility = VISIBLE
                progressText.text = "0%"
                downBtn.text = "下载中…"
                onDownload()
            }
        }
        val ignoreBtn = button("忽略此版本") { onIgnoreVersion() }
        val neverBtn = button("不再提示") { onDisableAutoCheck() }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(36), dip(30), dip(36), dip(26))
            background = GradientDrawable().apply {
                setColor(0xFF1B1B1F.toInt())
                cornerRadius = dip(18).toFloat()
                setStroke(dip(1), 0x22FFFFFF)
            }
            addView(title)
            addView(versionText)
            addView(notesView)
            addView(progressBar)
            addView(progressText)
            addView(space(dip(18)))
            addView(downloadBtn)
            addView(ignoreBtn)
            addView(neverBtn)
        }
        addView(card, LayoutParams(
            (resources.displayMetrics.widthPixels * 0.52f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        post { downloadBtn.requestFocus() }
    }

    private lateinit var downBtn: TextView

    private fun button(text: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTextColor(0xFFEAEAEE.toInt())
            gravity = Gravity.CENTER
            setPadding(dip(24), dip(15), dip(24), dip(15))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dip(10) }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    cornerRadius = dip(12).toFloat()
                    setColor(if (hasFocus) 0x2E4A90D9 else 0xFF232329.toInt())
                    setStroke(dip(1), if (hasFocus) accent else 0xFF2E2E36.toInt())
                }
                (v as TextView).setTextColor(if (hasFocus) Color.WHITE else 0xFFEAEAEE.toInt())
            }
            background = GradientDrawable().apply {
                cornerRadius = dip(12).toFloat()
                setColor(0xFF232329.toInt())
                setStroke(dip(1), 0xFF2E2E36.toInt())
            }
            if (text == "下载更新") downBtn = this
            buttons.add(this)
        }

    fun setProgress(pct: Int) {
        progressBar.progress = pct
        progressText.text = "$pct%"
        if (pct >= 100) {
            progressText.text = "下载完成，正在打开安装程序…"
        }
    }

    fun setDownloadFailed(msg: String) {
        downloading = false
        progressBar.visibility = GONE
        progressText.visibility = GONE
        downBtn.text = "重试下载"
        activity.showToast(msg)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { onDismiss(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    val idx = buttons.indexOfFirst { it.hasFocus() }
                    val next = if (idx < 0) 0 else (idx + dir).coerceIn(0, buttons.size - 1)
                    buttons[next].requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    buttons.firstOrNull { it.hasFocus() }?.performClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun space(h: Int) = FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
