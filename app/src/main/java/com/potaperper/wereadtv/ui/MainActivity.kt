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

    /** 1px 步进，14–40px */
    val fontSizeSteps: FloatArray = FloatArray(27) { 14f + it }
    private var settingsDialog: android.app.Dialog? = null

    /** 主界面（书架）也响应设置键 */
    private var shelfSettingsHandler: (() -> Unit)? = null

    fun openSettingsFromShelf() {
        shelfSettingsHandler?.invoke() ?: showThemePanel()
    }

    fun cycleFontSize() {
        val idx = fontSizeSteps.indexOfFirst { it == fontSizeSp }
        val next = if (idx < 0) 0 else (idx + 1) % fontSizeSteps.size
        fontSizeSp = fontSizeSteps[next]
        readerView?.applyZoom()
        showToast("字号 ${fontSizeSp.toInt()}")
    }

    /** 排版设置里按步进调字号（1px/步） */
    fun stepFontSize(delta: Int) {
        val exact = fontSizeSteps.indexOfFirst { it == fontSizeSp }
        val idx = if (exact >= 0) exact else fontSizeSteps.indexOfFirst { it > fontSizeSp }
            .let { if (it < 0) fontSizeSteps.size - 1 else it }
        fontSizeSp = fontSizeSteps[(idx + delta).coerceIn(0, fontSizeSteps.size - 1)]
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

    /** 正文字体颜色变化 */
    fun onTextColorChanged() {
        readerView?.applyTheme()
    }

    /** 四角信息显示开关变化 */
    fun onCornerInfoChanged() {
        readerView?.refreshHudVisibility()
    }

    /** 阅读字体变化 */
    fun onFontChanged() {
        readerView?.applyFont()
    }

    /** 暗黑模式切换：不修改用户颜色，只刷新实际生效的配色 */
    fun onDarkModeChanged() {
        val bg = Theme.bgColor(this)
        shelfView?.setBackgroundColor(bg)
        shelfView?.refreshTheme()
        readerView?.applyTheme()
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        // 更新检查与登录状态无关：先在后台悄悄检查
        maybeAutoCheckUpdate()

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

    override fun onResume() {
        super.onResume()
        // 从“未知来源安装”设置页返回后，若已授权则自动继续安装
        val pending = pendingInstallApk
        if (pending != null && pending.exists() &&
            (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())
        ) {
            pendingInstallApk = null
            mainHandler.postDelayed({ installApk(pending) }, 400)
        }
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
                            startCookieRenewal()
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
                    runCatching { ReadProgress.fromJson(api.getProgress(book.bookId)) }
                        .getOrDefault(ReadProgress(book.bookId, -1, 0, 0))
                }
                val local = com.potaperper.wereadtv.util.LocalProgress.load(this@MainActivity, book.bookId)
                val localIdx = local?.let { p ->
                    chapters.indexOfFirst { it.chapterUid == p.chapterUid }
                } ?: -1
                val cloudIdx = chapters.indexOfFirst { it.chapterUid == progress.chapterUid }
                when {
                    // 本地进度存在且不比云端旧：优先本地（可精确到段落）
                    local != null && localIdx >= 0 && (cloudIdx < 0 || localIdx >= cloudIdx) ->
                        reader.openAt(local.chapterUid, local.offset, local.percent)
                    progress.chapterUid > 0 ->
                        reader.openAt(progress.chapterUid, progress.chapterOffset, progress.progress.toFloat())
                    else -> reader.loadChapter(0, 0)
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

    // ---------- 自动更新 ----------

    private var updateDialog: android.app.Dialog? = null

    /** 启动时自动检查更新（可在“关于”页关闭） */
    fun maybeAutoCheckUpdate() {
        if (!Theme.autoCheckUpdate(this)) return
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { com.potaperper.wereadtv.update.UpdateManager.fetchLatest() }.getOrNull()
            } ?: return@launch
            if (!com.potaperper.wereadtv.update.UpdateManager.isNewer(info.version, currentVersion())) {
                return@launch
            }
            if (info.version == Theme.ignoredUpdateVersion(this@MainActivity)) return@launch
            showUpdateDialog(info)
        }
    }

    fun showUpdateDialog(info: com.potaperper.wereadtv.update.UpdateInfo) {
        if (updateDialog != null) return
        // 设置面板可能还开着：先关掉，保证更新弹窗在最上层且能拿到焦点
        if (settingsDialog != null) closeSettings()
        lateinit var dialogRef: android.app.Dialog
        val view = UpdateDialog(
            this, info,
            onDownload = { startUpdateDownload(info, dialogRef) },
            onCopyLink = {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("WC-reader APK", info.apkUrl),
                )
                showToast("下载链接已复制，可用手机/电脑下载后传到电视")
            },
            onIgnoreVersion = {
                Theme.setIgnoredUpdateVersion(this, info.version)
                showToast("已忽略 ${info.tag}")
                dialogRef.dismiss()
            },
            onDisableAutoCheck = {
                Theme.setAutoCheckUpdate(this, false)
                showToast("已关闭自动检查更新")
                dialogRef.dismiss()
            },
            onDismiss = { dialogRef.dismiss() },
        )
        val dialog = android.app.Dialog(
            this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen,
        )
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener { updateDialog = null }
        dialog.setOnShowListener { view.focusFirst() }
        dialogRef = dialog
        updateDialog = dialog
        dialog.show()
    }

    private fun startUpdateDownload(
        info: com.potaperper.wereadtv.update.UpdateInfo,
        dialog: android.app.Dialog,
    ) {
        val view = dialog.findViewById<android.view.View>(android.R.id.content)
        val updateView = (view as? android.view.ViewGroup)?.getChildAt(0) as? UpdateDialog
        ioScope.launch {
            val file = com.potaperper.wereadtv.update.UpdateManager.download(
                this@MainActivity, info,
                onProgress = { pct -> mainHandler.post { updateView?.setProgress(pct) } },
                onStatus = { s -> mainHandler.post { updateView?.setStatus(s) } },
            )
            if (file != null) {
                mainHandler.post {
                    dialog.dismiss()
                    installApk(file)
                }
            } else {
                mainHandler.post {
                    updateView?.setDownloadFailed("下载失败：已尝试直连与加速线路。可点「复制下载链接」用其他设备下载")
                }
            }
        }
    }

    /** 等待安装权限授予后自动继续安装的 APK */
    private var pendingInstallApk: java.io.File? = null

    /** 拉起系统安装器 */
    private fun installApk(file: java.io.File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = file
                showToast("请允许 WC-reader 安装应用，返回后自动继续")
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
                return
            }
            pendingInstallApk = null
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            showToast("无法启动安装器：${e.message}")
        }
    }

    // ---------- 账号 ----------

    /** 当前应用版本名 */
    fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 检查 GitHub Releases：有新版直接弹出下载对话框（带进度条） */
    fun checkForUpdate(onResult: (String) -> Unit) {
        scope.launch {
            onResult("正在检查更新…")
            val info = withContext(Dispatchers.IO) {
                runCatching { com.potaperper.wereadtv.update.UpdateManager.fetchLatest() }.getOrNull()
            }
            val cur = currentVersion()
            if (info == null) {
                onResult("检查更新失败，请检查网络")
                return@launch
            }
            if (!com.potaperper.wereadtv.update.UpdateManager.isNewer(info.version, cur)) {
                onResult("已是最新版本 v$cur")
                return@launch
            }
            showUpdateDialog(info)
        }
    }

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
