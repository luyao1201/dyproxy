package com.tzx.dyproxy

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 桌面入口：黑底 + OpenGL 立体弹跳文字「我是歪果仁」
 * 左下角白色静态文字：适配版本 + 抖音版本列表（用独立透明窗口叠加，避免被 GL 表面盖住）。
 */
class MainActivity : Activity() {

    private var glView: GLSurfaceView? = null
    private var labelDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(2)
        gl.setZOrderOnTop(true)
        gl.setRenderer(BounceRenderer("我是歪果仁", this))
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setBackgroundColor(Color.BLACK)
        setContentView(gl)
        glView = gl

        goFullscreen()
        setupVersionLabel()
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView?.onPause()
    }

    override fun onDestroy() {
        try { labelDialog?.dismiss() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /** 左下角白色静态文字：适配版本（每个版本一行） */
    private fun setupVersionLabel() {
        try {
            val dlg = Dialog(this)
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val tv = TextView(this)
            tv.text = "适配版本\n39.4.0\n40.7.0"
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setLineSpacing(0f, 1.15f)

            val fl = FrameLayout(this)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.START
            lp.leftMargin = dp(24)
            lp.bottomMargin = dp(36)
            fl.addView(tv, lp)
            dlg.setContentView(fl)

            val w = dlg.window
            if (w != null) {
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val wlp = w.attributes
                wlp.flags = wlp.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wlp.width = WindowManager.LayoutParams.MATCH_PARENT
                wlp.height = WindowManager.LayoutParams.MATCH_PARENT
                w.setAttributes(wlp)
            }
            dlg.show()
            labelDialog = dlg
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 全屏用**不透明**方案：GLSurfaceView 在带透明装饰的窗口上会和合成冲突（会黑屏）。
     */
    private fun goFullscreen() {
        try {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        } catch (_: Throwable) {
        }
    }
}
