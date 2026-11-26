package com.qlinqinglin.pocketmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import java.util.Locale
import kotlin.math.abs

class MonitorActivity : Activity(), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<String>()
    private lateinit var texture: TextureView
    private lateinit var header: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var telemetry: TextView
    private lateinit var empty: TextView
    private lateinit var modeButton: Button

    private var helper: CameraHelper? = null
    private var device: UsbDevice? = null
    private var deviceLabel = "未连接"
    private var modes = emptyList<Size>()
    private var active: Size? = null
    private var requested: Size? = null
    private var opened = false
    private var foreground = false
    private var permissionPrompt = false
    private var status = "等待 USB 采集卡"
    private var lastFrame = 0L
    private var lastTextureTimestamp = Long.MIN_VALUE
    private var previewStarted = 0L
    private var period = 0L
    private var frames = 0
    private var total = 0L
    private var fps = 0.0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var aspect = 16f / 9f

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            fps = frames * 1000.0 / (now - period).coerceAtLeast(1)
            frames = 0
            period = now
            if (opened && now - maxOf(lastFrame, previewStarted) >= 3000) {
                if (status != "未收到新画面") {
                    setStatus("未收到新画面")
                    showControls(true)
                }
            }
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        UVCUtils.init(application)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        fullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        period = SystemClock.elapsedRealtime()
        main.postDelayed(ticker, 1000)
        refresh()
        connect()
    }

    override fun onStop() {
        foreground = false
        main.removeCallbacks(ticker)
        disconnect()
        super.onStop()
    }

    private fun connect() {
        if (!foreground || helper != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("请授予相机权限，以读取 USB 采集卡")
            if (!permissionPrompt) {
                permissionPrompt = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            }
            return
        }
        val camera = CameraHelper()
        helper = camera
        setStatus("等待 USB 采集卡")
        camera.setStateCallback(object : ICameraHelper.StateCallback {
            private fun current(usb: UsbDevice) = helper === camera && device == usb

            override fun onAttach(usb: UsbDevice) {
                if (helper === camera) select(camera, usb)
            }

            override fun onDeviceOpen(usb: UsbDevice, first: Boolean) {
                if (!current(usb)) return
                setStatus("正在读取采集模式")
                camera.openCamera()
            }

            override fun onCameraOpen(usb: UsbDevice) {
                if (!current(usb)) return
                val size = camera.previewSize
                val supported = camera.supportedSizeList.orEmpty()
                modes = expandModes(supported)
                if (requested == null) {
                    val preferred = modes.firstOrNull()
                    if (preferred != null && key(preferred) != size?.let(::key)) {
                        openMode(camera, preferred)
                        return
                    }
                }
                requested?.let {
                    if (key(it) != size?.let(::key)) note("实际模式与请求不同")
                }
                active = size
                size?.let { aspect = it.width.toFloat() / it.height }
                transform()
                opened = true
                frames = 0
                total = 0
                lastFrame = 0
                lastTextureTimestamp = Long.MIN_VALUE
                previewStarted = SystemClock.elapsedRealtime()
                setStatus("等待 HDMI 画面")
                camera.startPreview()
                texture.surfaceTexture?.let { camera.addSurface(it, false) }
            }

            override fun onCameraClose(usb: UsbDevice) = Unit
            override fun onDeviceClose(usb: UsbDevice) = Unit

            override fun onDetach(usb: UsbDevice) {
                if (!current(usb)) return
                note("采集卡已拔出")
                disconnect()
                showControls(true)
                connect()
            }

            override fun onCancel(usb: UsbDevice) {
                if (!current(usb)) return
                setStatus("USB 授权已取消，请点重连")
            }

            override fun onError(usb: UsbDevice, error: CameraException) {
                if (!current(usb)) return
                opened = false
                showControls(true)
                note("UVC 错误 ${error.code} ${error.message.orEmpty()}")
                setStatus("采集卡打开失败，请切换模式或重连")
            }
        })
        camera.deviceList.orEmpty().firstOrNull(::isVideo)?.let { select(camera, it) }
    }

    private fun select(camera: CameraHelper, usb: UsbDevice) {
        if (device != null || !isVideo(usb)) return
        device = usb
        deviceLabel = String.format(Locale.ROOT, "%04X:%04X", usb.vendorId, usb.productId)
        note("USB 设备 $deviceLabel")
        setStatus("等待 USB 授权")
        camera.selectDevice(usb)
    }

    private fun isVideo(usb: UsbDevice) =
        usb.deviceClass == 14 || (0 until usb.interfaceCount).any { usb.getInterface(it).interfaceClass == 14 }

    private fun openMode(camera: CameraHelper, size: Size) {
        if (opened) texture.surfaceTexture?.let(camera::removeSurface)
        opened = false
        active = null
        requested = size
        fps = 0.0
        setStatus("正在打开 ${label(size)}")
        camera.closeCamera()
        camera.openCamera(size)
    }

    private fun disconnect() {
        if (device != null) {
            getPreferences(MODE_PRIVATE).edit().putString("lastCapture", captureReport()).apply()
        }
        val camera = helper
        helper = null
        device = null
        deviceLabel = "未连接"
        modes = emptyList()
        active = null
        requested = null
        opened = false
        lastFrame = 0
        frames = 0
        fps = 0.0
        if (camera != null) {
            texture.surfaceTexture?.let(camera::removeSurface)
            camera.release()
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request != 1) return
        if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) connect()
        else setStatus("相机权限未授予，可点重连重试")
    }

    private fun expandModes(supported: List<Size>): List<Size> =
        supported.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG || it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED }
            .flatMap { mode ->
                mode.fpsList.orEmpty().ifEmpty { listOf(mode.fps) }.filter { it > 0 }.map { rate ->
                    Size(mode.type, mode.width, mode.height, rate, listOf(rate))
                }
            }.distinctBy(::key).sortedWith(compareBy<Size>(
                { abs(it.width - 1920) + abs(it.height - 1080) },
                { abs(it.fps - 60) },
                { if (it.type == UVCCamera.UVC_VS_FRAME_MJPEG) 0 else 1 },
                { -it.fps }
            ))

    private fun key(size: Size) = listOf(size.type, size.width, size.height, size.fps)
    private fun label(size: Size): String {
        val format = if (size.type == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "YUY2"
        return "${size.width}×${size.height} · ${size.fps} fps · $format"
    }

    private fun chooseMode() {
        val camera = helper ?: return
        val choices = modes
        if (choices.isEmpty()) return
        AlertDialog.Builder(this).setTitle("采集模式")
            .setSingleChoiceItems(choices.map(::label).toTypedArray(), choices.indexOfFirst { key(it) == active?.let(::key) }) { dialog, index ->
                dialog.dismiss()
                if (camera === helper && key(choices[index]) != active?.let(::key)) openMode(camera, choices[index])
            }.setNegativeButton("关闭", null).show()
    }

    private fun note(message: String) {
        if (history.size == 20) history.removeFirst()
        history.addLast(message)
    }

    private fun setStatus(message: String) {
        if (status != message) note(message)
        status = message
        refresh()
    }

    private fun refresh() {
        telemetry.text = active?.let { "${label(it)}   |   显示 ${rate()} fps" } ?: status
        empty.text = when (status) {
            "等待 USB 采集卡" -> "等待 USB 采集卡\n用 OTG 转接头连接采集卡，再接入 HDMI 信号。"
            "未收到新画面" -> "未收到新画面\n检查 HDMI 信号，或尝试其他采集模式。"
            else -> status
        }
        empty.visibility = if (status == "正在监看") View.GONE else View.VISIBLE
        modeButton.isEnabled = modes.isNotEmpty()
    }

    private fun rate() = String.format(Locale.ROOT, "%.1f", fps)

    private fun captureReport(): String = buildString {
            appendLine("掌上监看 0.1.0")
            appendLine("${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            appendLine("USB VID:PID $deviceLabel")
            appendLine("状态 $status")
            appendLine("请求 ${requested?.let(::label) ?: "自动"}")
            appendLine("当前 ${active?.let(::label) ?: "—"}")
            appendLine("显示帧率 ${rate()} fps / 本次显示帧数 $total")
            appendLine("可选模式 ${modes.size}")
            modes.forEach { appendLine(label(it)) }
            appendLine("本次会话")
            history.forEach { appendLine(it) }
    }

    private fun diagnostics() {
        val report = if (device == null) {
            getPreferences(MODE_PRIVATE).getString("lastCapture", null)?.let { "上次采集报告\n$it" }
                ?: captureReport()
        } else captureReport()
        AlertDialog.Builder(this).setTitle("诊断报告").setMessage(report)
            .setNegativeButton("关闭", null).setPositiveButton("复制") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("掌上监看诊断", report))
                Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show()
            }.show()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        transform()
        if (opened) helper?.addSurface(surface, false)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = transform()
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (opened) helper?.removeSurface(surface)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!opened || surface.timestamp == lastTextureTimestamp) return
        lastTextureTimestamp = surface.timestamp
        frames++
        total++
        lastFrame = SystemClock.elapsedRealtime()
        if (status != "正在监看") setStatus("正在监看")
    }

    private fun transform() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0) return
        val fittedW = minOf(w, h * aspect) * zoom
        val fittedH = fittedW / aspect
        val maxX = ((fittedW - w) / 2).coerceAtLeast(0f)
        val maxY = ((fittedH - h) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        texture.setTransform(Matrix().apply {
            setScale(fittedW / w, fittedH / h, w / 2, h / 2)
            postTranslate(panX, panY)
        })
    }

    private fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        transform()
    }

    @Suppress("DEPRECATION")
    private fun fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun showControls(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        header.visibility = visibility
        controls.visibility = visibility
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply { surfaceTextureListener = this@MonitorActivity }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(0xFFD5E3E9.toInt())
            setLineSpacing(dp(9).toFloat(), 1f)
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        header = bar().apply {
            val name = TextView(context).apply {
                text = "掌上监看"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            addView(name)
            telemetry = TextView(context).apply {
                textSize = 13f
                setTextColor(0xFF9CE9DC.toInt())
                gravity = Gravity.END
                setPadding(dp(12), 0, 0, 0)
            }
            addView(telemetry, LinearLayout.LayoutParams(0, -2, 1f))
        }
        controls = bar().apply {
            gravity = Gravity.CENTER
            modeButton = action("采集模式", ::chooseMode)
            action("复位画面", ::resetZoom)
            action("重连") {
                permissionPrompt = false
                disconnect()
                connect()
            }
            action("诊断报告", ::diagnostics)
        }
        val topParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(12), dp(16), 0) }
        val bottomParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) }
        root.addView(header, topParams)
        root.addView(controls, bottomParams)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                topParams.setMargins(dp(16) + cutout.left, dp(12) + cutout.top, dp(16) + cutout.right, 0)
                header.layoutParams = topParams
                bottomParams.leftMargin = cutout.left
                bottomParams.rightMargin = cutout.right
                bottomParams.bottomMargin = dp(12) + cutout.bottom
                controls.layoutParams = bottomParams
                insets
            }
        }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                transform()
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                texture.performClick()
                return true
            }
            override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale.isInProgress || current.pointerCount != 1) return false
                panX -= dx
                panY -= dy
                transform()
                return true
            }
        })
        texture.setOnClickListener { showControls(header.visibility != View.VISIBLE) }
        texture.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
        setContentView(root)
        refresh()
    }

    private fun bar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(5), dp(13), dp(5))
        minimumHeight = dp(48)
        background = GradientDrawable().apply {
            setColor(0xE8131D25.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xFF30424D.toInt())
        }
    }

    private fun LinearLayout.action(title: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).also {
        it.text = title
        it.isAllCaps = false
        it.setTextColor(0xFFE3EFF3.toInt())
        it.setOnClickListener { click() }
        addView(it)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
