package com.potaperper.wereadtv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.potaperper.wereadtv.api.WeReadApi
import com.potaperper.wereadtv.model.Book
import com.potaperper.wereadtv.model.Chapter
import com.potaperper.wereadtv.model.ChapterContent
import com.potaperper.wereadtv.model.ReadProgress
import com.potaperper.wereadtv.util.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：扫码登录 → 书架 ⇄ 阅读器。
 * 遥控器：方向键翻页/移动焦点，OK 确认/呼出目录，MENU 字号，BACK 逐级返回。
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api by lazy { WeReadApi(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cookie 定期续期：微信读书 skey 约 6 小时过期，每 30 分钟续一次 */
    private var renewalJob: kotlinx.coroutines.Job? = null

    /** 独立的 IO 作用域（进程级）：进度上报等短任务不随 Activity 销毁而取消 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startCookieRenewal() {
        renewalJob?.cancel()
        renewalJob = scope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                try {
                    withContext(Dispatchers.IO) { api.renewCookie() }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "后台续期失败: ${e.message}")
                }
            }
        }
    }

    private lateinit var root: FrameLayout
    private var shelfView: ShelfView? = null
    private var readerView: ReaderView? = null
    private var qrLoginView: QrLoginView? = null
    private var currentBook: Book? = null

    // ---------- 字号 ----------

    var fontSizeSp: Float
        get() = Theme.fontPx(this)
        set(value) { Theme.setFontPx(this, value) }
    val fontSizeSteps = floatArrayOf(16f, 18f, 20f, 22f, 26f, 30f, 34f, 40f)
    private var settingsDialog: android.app.Dialog? = null

    /** 主界面（书架）也响应设置键 */
    private var shelfSettingsHandler: (() -> Unit)? = null

    fun openSettingsFromShelf() {
        shelfSettingsHandler?.invoke() ?: showThemePanel()
    }

    fun cycleFontSize() {
        val idx = fontSizeSteps.indexOfFirst { it == fontSizeSp }
        fontSizeSp = fontSizeSteps[(idx + 1).mod(fontSizeSteps.size)]
        readerView?.applyZoom()
        showToast("字号 ${fontSizeSp.toInt()}")
    }

    /** 排版设置里按步进调字号 */
    fun stepFontSize(delta: Int) {
        val idx = fontSizeSteps.indexOfFirst { it == fontSizeSp }
        val next = (idx + delta).coerceIn(0, fontSizeSteps.size - 1)
        fontSizeSp = fontSizeSteps[next]
        readerView?.applyZoom()
    }

    fun showThemePanel(initialTab: Int = 0) {
        if (settingsDialog != null) return
        val panel = ThemePanel(this, initialTab, onChanged = { color ->
            // 背景变化实时作用到书架与阅读器
            shelfView?.setBackgroundColor(color)
            shelfView?.refreshTheme()
            readerView?.applyTheme()
        }, onDismiss = { closeSettings() })

        // Dialog 承载：独立窗口，焦点不会逃逸到背后的书架/阅读器
        val dialog = android.app.Dialog(
            this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen,
        )
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            settingsDialog = null
            if (readerView != null) readerView?.requestFocus() else shelfView?.focusGrid()
        }
        settingsDialog = dialog
        dialog.show()
    }

    private fun closeSettings() {
        settingsDialog?.dismiss()
        settingsDialog = null
    }

    /** 排版偏好变化（行距/边距/翻页方式），通知阅读器重排 */
    fun onLayoutPrefChanged() {
        readerView?.applyLayoutPrefs()
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        if (api.hasCookie) {
            showShelf()
            refreshShelf()
            startCookieRenewal()
        } else {
            startQrLogin()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renewalJob?.cancel()
        scope.cancel()
        readerView?.release()
    }

    override fun onPause() {
        super.onPause()
        // 切后台/退出前保存进度
        readerView?.saveProgress()
    }

    // ---------- 视图切换 ----------

    private fun swapView(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    fun showShelf() {
        readerView?.saveProgress()
        readerView?.release()
        readerView = null
        val v = shelfView ?: ShelfView(this) { book -> openBook(book) }.also {
            shelfView = it
        }
        v.setBackgroundColor(Theme.bgColor(this))
        shelfSettingsHandler = { showThemePanel() }
        swapView(v)
        v.requestFocus()
    }

    fun startQrLogin() {
        val v = QrLoginView(this) {
            qrPolling = false
            if (api.hasCookie) {
                showShelf(); refreshShelf()
            } else {
                finish()
            }
        }
        qrLoginView = v
        swapView(v)
        beginQrPolling()
    }

    private var qrPolling = false

    @Volatile
    private var pendingOtp: String? = null

    fun submitOtp(otp: String) {
        pendingOtp = otp
    }

    private fun beginQrPolling() {
        qrPolling = true
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { api.beginQrLogin() }
                if (!qrPolling) return@launch
                qrLoginView?.showQr(session.qrUrl)
                qrLoginView?.setStatus("请用微信扫描二维码")

                var otpShown = false
                var consecutiveWait = 0
                while (qrPolling) {
                    val otp = pendingOtp
                    pendingOtp = null
                    val result = try {
                        withContext(Dispatchers.IO) { api.pollQrLogin(session.uid, otp ?: "") }
                    } catch (e: Exception) {
                        // 长轮询超时等网络异常视为仍在等待
                        null
                    }
                    when (result) {
                        null -> { /* 网络异常，继续下一轮 */ }
                        is WeReadApi.QrPollResult.NeedOtp -> {
                            if (!otpShown) {
                                otpShown = true
                                qrLoginView?.setStatus("请在手机微信确认，或输入页面提示的验证码")
                                qrLoginView?.showOtpInput()
                            }
                        }
                        is WeReadApi.QrPollResult.OtpMismatch -> {
                            otpShown = false
                            qrLoginView?.setStatus("验证码错误，请重新输入")
                            qrLoginView?.showOtpInput()
                        }
                        is WeReadApi.QrPollResult.Expired -> {
                            // 自动刷新二维码，无需用户手动退出重进
                            showToast("二维码已过期，正在刷新")
                            beginQrPolling()
                            return@launch
                        }
                        is WeReadApi.QrPollResult.Success -> {
                            qrPolling = false
                            showToast("登录成功")
                            showShelf()
                            refreshShelf()
                            return@launch
                        }
                        is WeReadApi.QrPollResult.Waiting -> {
                            consecutiveWait++
                            if (consecutiveWait % 15 == 0) {
                                qrLoginView?.setStatus("等待扫码中...")
                            }
                        }
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                if (qrPolling) qrLoginView?.setStatus("登录失败: ${e.message}")
            }
        }
    }

    // ---------- 书架 ----------

    fun refreshShelf(silent: Boolean = false) {
        scope.launch {
            try {
                val books = withContext(Dispatchers.IO) {
                    val arr = api.shelfSync()
                    val list = mutableListOf<Book>()
                    for (i in 0 until arr.length()) {
                        list.add(Book.fromJson(arr.getJSONObject(i)))
                    }
                    list.sortByDescending { it.readUpdateTime }
                    list
                }
                shelfView?.setBooks(books)
                if (!silent) showToast("书架已刷新")
            } catch (e: Exception) {
                // 登录彻底失效（续期也失败）→ 回扫码登录页
                val isAuth = e is WeReadApi.AuthExpiredException ||
                    (e.message ?: "").contains("登录") || (e.message ?: "").contains("鉴权")
                if (isAuth) {
                    showToast("登录已过期，请重新扫码")
                    startQrLogin()
                } else {
                    shelfView?.showError(e.message ?: "未知错误")
                    if (!silent) showToast("刷新失败：${e.message}")
                }
            }
        }
    }

    /** 书架顶栏“刷新书架”按钮 */
    fun refreshShelfFromUi() {
        showToast("正在刷新…")
        refreshShelf(silent = false)
    }

    // ---------- 阅读 ----------

    private fun openBook(book: Book) {
        currentBook = book
        val reader = ReaderView(this, book) {
            showShelf()
        }
        readerView = reader
        swapView(reader)
        reader.requestFocus()

        scope.launch {
            try {
                val chapters = withContext(Dispatchers.IO) {
                    Chapter.fromArray(api.chapterInfos(book.bookId))
                }
                if (chapters.isEmpty()) throw Exception("目录为空")
                reader.setChapters(chapters)
                val progress = withContext(Dispatchers.IO) {
                    ReadProgress.fromJson(api.getProgress(book.bookId))
                }
                if (progress.chapterUid > 0) {
                    reader.openAt(progress.chapterUid, progress.chapterOffset)
                } else {
                    reader.loadChapter(0, 0)
                }
            } catch (e: Exception) {
                reader.showChapterError("获取目录失败: ${e.message}")
                reader.onChapterLoadFinished()
            }
        }
    }

    fun loadChapterContent(chapterUid: Long, seq: Int) {
        val book = currentBook ?: return
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    val (html, style, format) = api.chapterContent(book.bookId, chapterUid)
                    ChapterContent(html, style, format)
                }
                if (isFinishing) return@launch
                readerView?.showChapterContent(content, seq)
                // 预加载下一章：翻章零等待
                readerView?.nextChapterUid()?.let { nextUid ->
                    launch(Dispatchers.IO) {
                        try { api.chapterContent(book.bookId, nextUid) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                readerView?.showChapterError(e.message ?: "加载失败")
            } finally {
                readerView?.onChapterLoadFinished()
            }
        }
    }

    fun reportProgress(chapterUid: Long, percent: Int, chapterOffset: Int, format: String) {
        val book = currentBook ?: return
        // ioScope 独立于 Activity 生命周期：退出瞬间发起的上报也能完成
        ioScope.launch {
            try {
                val init = api.reportProgress(
                    book.bookId, chapterUid, percent, chapterOffset, format, isInit = true,
                )
                if (init.optInt("succ", 0) == 1) {
                    val token = init.optString("readerToken")
                    if (token.isNotEmpty()) {
                        api.reportProgress(
                            book.bookId, chapterUid, percent, chapterOffset, format,
                            isInit = false, readerToken = token,
                        )
                    }
                }
                android.util.Log.i("MainActivity", "进度已上报 ch=$chapterUid p=$percent")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "进度上报失败: ${e.message}")
            }
        }
    }

    // ---------- 账号 ----------

    /** 当前登录账号的 vid（从 cookie 提取），供账号面板显示 */
    fun accountVid(): String {
        val m = Regex("wr_vid=(\\d+)").find(api.cookie)
        return m?.groupValues?.get(1) ?: ""
    }

    fun logout() {
        api.cookie = ""
        android.widget.Toast.makeText(this, "已退出登录", android.widget.Toast.LENGTH_SHORT).show()
        startQrLogin()
    }

    fun showAccountPanel() {
        showThemePanel(2)  // ThemePanel.TAB_ACCOUNT
    }

    // ---------- 工具 ----------

    fun showToast(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dip(24), dip(12), dip(24), dip(12))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dip(10f).toFloat()
            }
        }
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        ))
        tv.animate().alpha(0f).setStartDelay(900).setDuration(400).withEndAction {
            root.removeView(tv)
        }
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
