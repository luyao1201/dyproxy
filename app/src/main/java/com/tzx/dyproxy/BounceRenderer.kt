package com.tzx.dyproxy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * 完全复刻参考实现（Nuke 1.0.3）的 OpenGL 立体弹跳文字。
 *
 * 从它的 dex 里挖出的全部要点：
 *   · 几何**不是长方体**：在位图掩码上逐像素找"暴露边"，每条边生成一个侧壁四边形，
 *     于是挤出体的外轮廓跟着字形走（"按字切割"），7 float/顶点（stride 28 字节）
 *   · 前后两个「盖面」aCap=1 → 用文字纹理的 alpha；**侧壁 aCap=0 → 实心**，
 *     aShade 取 0.46（竖直壁）/ 0.58（水平壁）做明暗 → 这就是"厚度"的来源
 *   · 片元：alpha = mix(1, 纹理a, aCap)，< 0.04 丢弃，gl_FragColor = (uColor*aShade, smoothstep(0.04,0.72,alpha))
 *   · 背面剔除 BACK + 深度 LEQUAL + SRC_ALPHA 混合（所以半透明"雾感"是混合来的）
 *   · 视图 lookAt(0,0,5 → 原点)；投影 48° / 0.1 / 20；屏幕半高 = tan(24°)×5，半宽 = ×宽高比
 *   · 初始：pos=(-1.0, 0.2)，vel=(0.72, 0.56)；四角 18/-28/6/195，转速 23/31/9/12 °/s，mod 360
 *   · 模型 = T(pos)·Rz(F)·Ry(E)·Rx(D)；MVP = 投影·视图·模型
 *   · 碰撞用旋转后包围盒收紧；颜色 = HSV(角G, 0.86, 1.0) 再 +0.14 提亮
 *   · 纹理：1024×256 位图，SansSerif 180px，居中，宽度超 976 自动缩字号
 *   · dt 夹在 [0, 0.05]
 */
class BounceRenderer(private val text: String, private var dumpCtx: android.content.Context? = null) : GLSurfaceView.Renderer {

    // ── 状态（初值照抄）──
    private var posX = -1.0f
    private var posY = 0.2f
    private var velX = 0.72f
    private var velY = 0.56f
    private var angD = 18f
    private var angE = -28f
    private var angF = 6f
    private var angG = 195f

    // 尺寸不是写死的：半宽 = min(boundU×0.68, 2.1)，半高 = 半宽/4（位图 1024×256 的宽高比）
    private var halfW = 1.0f
    private var halfH = 0.25f
    private var halfD = 0.05f     // ★ 半厚度（厚度就是这个）
    private var vertCount = 0
    private var mesh: FloatBuffer? = null
    private var mask: BooleanArray? = null   // 字形掩码：alpha ≥ 96 的像素
    private var boundU = 1.0f
    private var boundV = 1.0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var aCap = 0
    private var aShade = 0
    private var uMvp = 0
    private var uColor = 0
    private var uTexture = 0
    private var texId = 0
    private var lastNanos = 0L

    private val vs = """
        attribute vec3 aPosition;
        attribute vec2 aTextureCoordinate;
        attribute float aCap;
        attribute float aShade;
        uniform mat4 uModelViewProjection;
        varying vec2 vTextureCoordinate;
        varying float vCap;
        varying float vShade;
        void main() {
            vTextureCoordinate = aTextureCoordinate;
            vCap = aCap;
            vShade = aShade;
            gl_Position = uModelViewProjection * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    private val fs = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec3 uColor;
        varying vec2 vTextureCoordinate;
        varying float vCap;
        varying float vShade;
        void main() {
            float capAlpha = texture2D(uTexture, vTextureCoordinate).a;
            float alpha = mix(1.0, capAlpha, vCap);
            if (alpha < 0.04) discard;
            gl_FragColor = vec4(uColor * vShade, smoothstep(0.04, 0.72, alpha));
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)
        buildProgram()
        buildTexture()
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        vpW = width; vpH = height
        val aspect = width.toFloat() / max(height, 1).toFloat()
        Matrix.perspectiveM(proj, 0, 48f, aspect, 0.1f, 20f)
        boundV = tan(Math.toRadians(24.0)).toFloat() * 5f
        boundU = boundV * aspect
        // 参考就在这里算尺寸并现场生成立体网格（掩码已就绪时）
        halfW = min(boundU * 0.68f, 2.1f)
        halfH = halfW / 4f
        buildMesh()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) {
            lastNanos = now
            return
        }
        var dt = (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        dt = dt.coerceIn(0f, 0.05f)

        // 四角自转 + 位移
        angD = (angD + 23f * dt) % 360f
        angE = (angE + 31f * dt) % 360f
        angF = (angF + 9f * dt) % 360f
        angG = (angG + 12f * dt) % 360f
        posX += velX * dt
        posY += velY * dt

        // 旋转后包围盒（照抄；用立方体的宽/厚参与投影）
        val rD = Math.toRadians(angD.toDouble())
        val rE = Math.toRadians(angE.toDouble())
        val rF = Math.toRadians(angF.toDouble())
        val sD = sin(rD).toFloat(); val cD = cos(rD).toFloat()
        val sE = sin(rE).toFloat(); val cE = cos(rE).toFloat()
        val sF = sin(rF).toFloat(); val cF = cos(rF).toFloat()
        val boxW = abs(cE * cF) * halfW + abs(sD * sE * cF - cD * sF) * halfH
        val boxH = abs(cE * sF) * halfW + abs(sD * sE * sF + cD * cF) * halfH

        val minX = -boundU + boxW
        val maxX = boundU - boxW
        if (minX >= maxX) {
            posX = 0f
        } else if (posX < minX) {
            posX = minX
            velX = abs(velX)
        } else if (posX > maxX) {
            posX = maxX
            velX = -abs(velX)
        }
        val minY = -boundV + boxH
        val maxY = boundV - boxH
        if (minY >= maxY) {
            posY = 0f
        } else if (posY < minY) {
            posY = minY
            velY = abs(velY)
        } else if (posY > maxY) {
            posY = maxY
            velY = -abs(velY)
        }

        // 模型 = T(pos)·Rz(F)·Ry(E)·Rx(D)；MVP = 投影·视图·模型
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, posX, posY, 0f)
        Matrix.rotateM(model, 0, angF, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, angE, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, angD, 1f, 0f, 0f)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        // 颜色 = HSV(角 G, 0.86, 1.0) + 0.14 提亮
        val rgb = hsv(angG, 0.86f, 1f)
        for (i in rgb.indices) rgb[i] = (rgb[i] + 0.14f).coerceAtMost(1f)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform3f(uColor, rgb[0], rgb[1], rgb[2])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexture, 0)

        val buf = mesh ?: return
        if (vertCount <= 0) return
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aTex)
        buf.position(5)
        GLES20.glVertexAttribPointer(aCap, 1, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aCap)
        buf.position(6)
        GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(aShade)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)   // 盖面 12 + 侧壁 6×边数

        frameNo++
        if (debugDump && !dumpedFrame && frameNo == 45) {
            dumpedFrame = true
            dumpFrame(vpW, vpH)
        }
    }

    // =========================================================================
    // 立方体：±halfW × ±halfH × ±halfD
    //   盖面(z=±d)：aCap=1 用文字 alpha，正面 shade=1.0、背面 0.72
    //   侧面：aCap=0 实心，shade 0.46 / 0.58 交替（照抄它的两个明暗值）
    // =========================================================================
    // 立体网格：照抄参考的实现（这才是"按字切割"的关键）
    //   · 12 个盖面顶点：前后各一个整幅四边形，贴文字纹理（cap=1，shade=1.0）
    //   · 侧壁：遍历 1024×256 的字形掩码，每个**暴露的像素边**（邻居为空）生成一个
    //     四边形（6 顶点），深度 z∈[-0.05,+0.05]，cap=0 实心
    //       左右方向的边（竖直壁）shade=0.46；上下方向的边（水平壁）shade=0.58
    //   · 顶点数 = 暴露边数×6 + 12（与参考的 (edge*6+12)*7 完全一致）
    // =========================================================================
    private val bw = 1024
    private val bh = 256

    private fun buildMesh() {
        val m = mask ?: return
        val w = halfW
        val h = halfH
        val d = halfD
        val stepX = 2f * w / bw
        val stepY = 2f * h / bh

        fun inside(x: Int, y: Int): Boolean =
            x in 0 until bw && y in 0 until bh && m[y * bw + x]

        var edges = 0
        for (y in 0 until bh) {
            val row = y * bw
            for (x in 0 until bw) {
                if (!m[row + x]) continue
                if (!inside(x - 1, y)) edges++
                if (!inside(x + 1, y)) edges++
                if (!inside(x, y - 1)) edges++
                if (!inside(x, y + 1)) edges++
            }
        }

        val arr = FloatArray((edges * 6 + 12) * 7)
        var o = 0
        fun v(x: Float, y: Float, z: Float, u: Float, t: Float, cap: Float, shade: Float) {
            arr[o++] = x; arr[o++] = y; arr[o++] = z
            arr[o++] = u; arr[o++] = t; arr[o++] = cap; arr[o++] = shade
        }

        /** 四边形按 P0-P1-P2-P3 顺序切成两个三角，UV 固定 (0,0)、cap=0（侧壁） */
        fun wall(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            shade: Float
        ) {
            v(x0, y0, z0, 0f, 0f, 0f, shade)
            v(x1, y1, z1, 0f, 0f, 0f, shade)
            v(x2, y2, z2, 0f, 0f, 0f, shade)
            v(x0, y0, z0, 0f, 0f, 0f, shade)
            v(x2, y2, z2, 0f, 0f, 0f, shade)
            v(x3, y3, z3, 0f, 0f, 0f, shade)
        }

        // ── 前盖面 z=+d（从 +z 看逆时针）──
        v(-w, -h, d, 0f, 1f, 1f, 1f)
        v(w, -h, d, 1f, 1f, 1f, 1f)
        v(w, h, d, 1f, 0f, 1f, 1f)
        v(-w, -h, d, 0f, 1f, 1f, 1f)
        v(w, h, d, 1f, 0f, 1f, 1f)
        v(-w, h, d, 0f, 0f, 1f, 1f)
        // ── 后盖面 z=-d（从 -z 看逆时针）──
        v(w, -h, -d, 1f, 1f, 1f, 1f)
        v(-w, -h, -d, 0f, 1f, 1f, 1f)
        v(-w, h, -d, 0f, 0f, 1f, 1f)
        v(w, -h, -d, 1f, 1f, 1f, 1f)
        v(-w, h, -d, 0f, 0f, 1f, 1f)
        v(w, h, -d, 1f, 0f, 1f, 1f)

        // ── 侧壁：逐像素暴露边 ──
        for (y in 0 until bh) {
            val row = y * bw
            val yTop = h - y * stepY
            val yBot = yTop - stepY
            for (x in 0 until bw) {
                if (!m[row + x]) continue
                val xL = -w + x * stepX
                val xR = xL + stepX
                // 左边暴露 → 壁在 x=xL，法线朝 -x
                if (!inside(x - 1, y)) wall(xL, yBot, -d, xL, yBot, d, xL, yTop, d, xL, yTop, -d, 0.46f)
                // 右边暴露 → 壁在 x=xR，法线朝 +x
                if (!inside(x + 1, y)) wall(xR, yBot, d, xR, yBot, -d, xR, yTop, -d, xR, yTop, d, 0.46f)
                // 上边暴露 → 壁在 y=yTop，法线朝 +y
                if (!inside(x, y - 1)) wall(xL, yTop, d, xR, yTop, d, xR, yTop, -d, xL, yTop, -d, 0.58f)
                // 下边暴露 → 壁在 y=yBot，法线朝 -y
                if (!inside(x, y + 1)) wall(xR, yBot, d, xL, yBot, d, xL, yBot, -d, xR, yBot, -d, 0.58f)
            }
        }

        val bb = ByteBuffer.allocateDirect(o * 4).order(ByteOrder.nativeOrder())
        mesh = bb.asFloatBuffer().also { it.put(arr, 0, o); it.position(0) }
        vertCount = o / 7
        android.util.Log.i("QQGold", "网格 暴露边=$edges 顶点=$vertCount 半宽=$w 半高=$h")
    }


    private fun buildTexture() {
        val bw = 1024
        val bh = 256
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 180f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL_AND_STROKE
        val maxW = 976f
        val measured = paint.measureText(text)
        if (measured > maxW) paint.textSize = paint.textSize * (maxW / measured)
        val fm = paint.fontMetrics
        val baseline = bh / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, bw / 2f, baseline, paint)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        // 照抄参考：MIN 用 LINEAR_MIPMAP_LINEAR 并生成 mipmap
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        // 字形掩码：alpha ≥ 96 算"字内"（参考用的就是 96 这个阈值）
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        val m = BooleanArray(px.size)
        var hit = 0
        for (i in px.indices) {
            if ((px[i] ushr 24) >= 96) { m[i] = true; hit++ }
        }
        mask = m
        if (debugDump) dumpBitmap(bmp, "glyph.png")
        bmp.recycle()
        android.util.Log.i("QQGold", "字形掩码 覆盖像素=$hit/${px.size}")
    }

    private fun buildProgram() {
        val vsh = compile(GLES20.GL_VERTEX_SHADER, vs)
        val fsh = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vsh)
        GLES20.glAttachShader(program, fsh)
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        aCap = GLES20.glGetAttribLocation(program, "aCap")
        aShade = GLES20.glGetAttribLocation(program, "aShade")
        uMvp = GLES20.glGetUniformLocation(program, "uModelViewProjection")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        android.util.Log.i(
            "QQGold",
            "GL 程序 链接=" + (linked[0] != 0) +
                " 位置 aPosition=" + aPos + " aTex=" + aTex + " aCap=" + aCap + " aShade=" + aShade +
                " uMvp=" + uMvp + " uColor=" + uColor + " uTexture=" + uTexture
        )
    }

    private var vpW = 0
    private var vpH = 0
    private var frameNo = 0
    private var dumpedFrame = false

    /** 需要抓 GL 证据时改成 true（会把字形位图与一帧画面导到外部缓存） */
    private val debugDump = false

    private fun dumpBitmap(b: Bitmap, name: String) {
        try {
            val f = java.io.File(dumpCtx?.getExternalCacheDir() ?: dumpCtx?.cacheDir, name)
            java.io.FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.i("QQGold", "已导出 $name -> " + f.absolutePath)
        } catch (e: Throwable) {
            android.util.Log.e("QQGold", "导出 $name 失败: " + e)
        }
    }

    /** 把 GL 帧缓冲原样读回来（不走 screencap，排除合成层干扰） */
    private fun dumpFrame(w: Int, h: Int) {
        try {
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = ((h - 1 - y) * w + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                val a = buf.get(i + 3).toInt() and 0xFF
                px[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            dumpBitmap(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), "gl_frame.png")
        } catch (e: Throwable) {
            android.util.Log.e("QQGold", "导出帧失败: " + e)
        }
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            android.util.Log.e("QQGold", "着色器编译失败($type): " + GLES20.glGetShaderInfoLog(sh))
        }
        return sh
    }

    /** HSV 转 RGB（参考实现是色相驱动的） */
    private fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        return when (i % 6) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            else -> floatArrayOf(v, p, q)
        }
    }
}
