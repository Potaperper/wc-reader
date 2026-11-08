package com.potaperper.wereadtv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.potaperper.wereadtv.model.Book
import com.potaperper.wereadtv.util.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.LruCache

/**
 * 书架页：封面网格，遥控器焦点导航由 RecyclerView 原生处理。
 */
class ShelfView(
    private val activity: MainActivity,
    private val onBookClick: (Book) -> Unit,
) : FrameLayout(activity) {

    private val books = mutableListOf<Book>()
    private val adapter = BookAdapter()
    private val hintText: TextView
    private lateinit var headerBox: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val coverCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        val bg = Theme.bgColor(activity)
        setBackgroundColor(bg)
        val fg = Theme.fgColor(bg)

        // ---- 顶栏：标题 + 账号/设置 按钮 ----
        val title = TextView(activity).apply {
            text = "微信读书 · 书架"
            textSize = 26f
            setTextColor(fg)
            setPadding(dip(32), dip(24), dip(32), dip(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = pillButton(activity, "刷新书架", fg) { activity.refreshShelfFromUi() }
        val settingsBtn = pillButton(activity, "设置", fg) { activity.showThemePanel() }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dip(24), dip(16), dip(24), 0)
            addView(title)
            addView(refreshBtn)
            addView(space(activity, dip(12)))
            addView(settingsBtn)
        }

        hintText = TextView(activity).apply {
            text = "加载中..."
            textSize = 16f
            setTextColor(Theme.secondaryColor(bg))
            setPadding(dip(32), dip(4), dip(32), dip(8))
        }

        // 标题栏容器：不透明背景 + 提升层级，滚动时封面不会盖住标题与按钮
        headerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            elevation = dip(8).toFloat()
            addView(topRow)
            addView(hintText)
        }

        val recycler = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, 6)
            adapter = this@ShelfView.adapter
            // 上下留出空间，缩放的焦点框/光晕不被裁切
            setPadding(dip(24), dip(12), dip(24), dip(20))
            clipToPadding = false
            clipChildren = false
        }
        recyclerView = recycler

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(headerBox)
            addView(
                recycler,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ),
            )
        }
        clipChildren = false
        clipToPadding = false
        addView(column)
    }

    /** 顶栏胶囊按钮：主题色描边 + 焦点高亮描边 */
    private fun pillButton(activity: MainActivity, text: String, fg: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTextColor(fg)
            setPadding(dip(20), dip(8), dip(20), dip(8))
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                cornerRadius = dip(20).toFloat()
                setStroke(dip(2), fg)
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                background = GradientDrawable().apply {
                    cornerRadius = dip(20).toFloat()
                    setStroke(dip(3), if (hasFocus) 0xFFFFFFFF.toInt() else fg)
                    setColor(if (hasFocus) 0x50888888.toInt() else 0x00000000)
                }
                setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else fg)
            }
        }

    private fun space(activity: MainActivity, size: Int): View =
        FrameLayout(activity).apply { layoutParams = LinearLayout.LayoutParams(size, 1) }

    /** 焦点变化时重绘按钮描边（主题切换后调用） */
    private fun redrawPillButtons() {
        // 描边色在焦点监听器里按当前焦点态生成；这里仅触发主题色刷新
        refreshTheme()
    }

    fun setBooks(list: List<Book>) {
        books.clear()
        books.addAll(list)
        adapter.notifyDataSetChanged()
        hintText.text = if (list.isEmpty()) "书架为空" else "共 ${list.size} 本"
        if (list.isNotEmpty()) {
            // 列表就绪后把焦点落到第一个封面，遥控器立即可用
            post { recyclerView.requestFocus() }
        }
    }

    /** 从设置面板返回时，把焦点交回书籍网格 */
    fun focusGrid() {
        recyclerView.requestFocus()
    }

    fun showError(msg: String) {
        hintText.text = "加载失败: $msg"
    }

    /** 主题变化时刷新文字颜色（封面在 RecyclerView 内重绑时也会取新色） */
    fun refreshTheme() {
        val bg = Theme.bgColor(activity)
        hintText.setTextColor(Theme.secondaryColor(bg))
        headerBox.setBackgroundColor(bg)
        adapter.notifyDataSetChanged()
    }

    inner class BookAdapter : RecyclerView.Adapter<BookViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            return BookViewHolder(BookCell(parent.context))
        }

        override fun getItemCount(): Int = books.size

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.bind(books[position])
        }
    }

    inner class BookViewHolder(val cell: BookCell) : RecyclerView.ViewHolder(cell) {
        fun bind(book: Book) {
            cell.bind(book)
            cell.setOnClickListener { onBookClick(book) }
        }
    }

    inner class BookCell(context: Context) : LinearLayout(context) {
        private val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 占位透明：封面加载前不显示灰色底块
            setImageDrawable(null)
        }
        private val titleView = TextView(context).apply {
            textSize = 13f
            maxLines = 1
        }

        init {
            orientation = VERTICAL
            setPadding(dip(8), dip(8), dip(8), dip(8))
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // 焦点框：明亮描边 + 微放大，深浅背景都清晰
            foreground = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = dip(8).toFloat()
                setStroke(0, 0)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f).setDuration(120).start()
                foreground = GradientDrawable().apply {
                    setColor(if (hasFocus) 0x28888888 else 0x00000000)
                    cornerRadius = dip(8).toFloat()
                    setStroke(dip(3), if (hasFocus) 0xFFFFFFFF.toInt() else 0)
                }
            }
            addView(cover, LayoutParams(dip(150), dip(210)))
            addView(titleView, LayoutParams(dip(150), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(book: Book) {
            titleView.text = book.title
            titleView.setTextColor(Theme.fgColor(Theme.bgColor(activity)))
            cover.tag = book.cover
            val cached = coverCache.get(book.cover)
            if (cached != null) {
                cover.setImageBitmap(cached)
            } else {
                cover.setImageDrawable(null)
                loadCoverAsync(book.cover, cover)
            }
        }
    }

    private fun loadCoverAsync(url: String, view: ImageView) {
        if (url.isEmpty()) return
        view.tag = url
        ioExecutor.execute {
            val bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) coverCache.put(url, bmp)
            mainHandler.post {
                // 单元格可能已被回收复用到另一本书，只有 URL 匹配才显示
                if (view.tag == url) view.setImageBitmap(bmp)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    activity.moveTaskToBack(true)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    activity.openSettingsFromShelf()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dip(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
