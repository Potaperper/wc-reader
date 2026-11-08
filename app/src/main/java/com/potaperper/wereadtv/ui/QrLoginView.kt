package com.potaperper.wereadtv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 扫码登录页：全屏二维码 + 状态提示 + 可选验证码输入，遥控器操作。
 */
class QrLoginView(
    private val activity: MainActivity,
    private val onBack: () -> Unit,
) : FrameLayout(activity) {

    private val qrImage = ImageView(activity).apply {
        layoutParams = LayoutParams(dip(300), dip(300))
    }
    private val statusText = TextView(activity).apply {
        textSize = 20f
        setTextColor(0xFFCCCCCC.toInt())
        gravity = android.view.Gravity.CENTER
    }
    private val otpInput = EditText(activity).apply {
        hint = "验证码"
        textSize = 18f
        visibility = GONE
        layoutParams = LinearLayout.LayoutParams(dip(360), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private val otpButton = Button(activity).apply {
        text = "确认"
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
    }
    private val backButton = Button(activity).apply {
        text = "返回"
        isFocusable = true
        isFocusableInTouchMode = true
    }

    init {
        setBackgroundColor(0xFF1A1A1A.toInt())

        val title = TextView(activity).apply {
            text = "账号登录"
            textSize = 28f
            setTextColor(Color.WHITE)
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dip(40), 0, dip(40))
            addView(title)
            addView(space(dip(24)))
            addView(qrImage)
            addView(space(dip(16)))
            addView(statusText)
            addView(space(dip(12)))
            addView(otpInput)
            addView(space(dip(8)))
            addView(otpButton)
            addView(space(dip(8)))
            addView(backButton)
        }

        addView(
            ScrollView(activity).apply {
                addView(column, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                ))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        otpButton.setOnClickListener {
            val otp = otpInput.text.toString().trim()
            if (otp.isNotEmpty()) activity.submitOtp(otp)
        }
        backButton.setOnClickListener { onBack() }
        post { backButton.requestFocus() }
    }

    fun setStatus(text: String) {
        statusText.text = text
    }

    fun showQr(content: String) {
        val size = 560
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        } catch (e: Exception) {
            setStatus("二维码生成失败")
            return
        }
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        qrImage.setImageBitmap(bmp)
    }

    fun showOtpInput() {
        otpInput.visibility = VISIBLE
        otpButton.visibility = VISIBLE
        otpInput.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun space(height: Int): View =
        FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, height)
        }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
