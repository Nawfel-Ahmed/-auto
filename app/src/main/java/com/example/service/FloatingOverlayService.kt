package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.aistudio.floatingbubble.kxpzqa.R
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null

    // 100% Transparent Fullscreen Canvas for Hint Vector & Board Alignment
    private var transparentCanvasView: DirectBoardOverlayView? = null
    private var canvasWindowParams: WindowManager.LayoutParams? = null

    // Minimalist Floating Control Bar
    private var controlBarContainer: FrameLayout? = null
    private var controlBarParams: WindowManager.LayoutParams? = null

    // Calibration & Alignment Floating Popup
    private var alignPopupContainer: LinearLayout? = null
    private var alignPopupParams: WindowManager.LayoutParams? = null

    // Control Bar Views
    private var hintBtnLabel: TextView? = null
    private var autoHintBtnLayout: LinearLayout? = null
    private var autoHintBtnLabel: TextView? = null
    private var alignBtnLayout: LinearLayout? = null
    private var alignBtnLabel: TextView? = null
    private var hudToastView: TextView? = null

    // Persistent background polling coroutine for Auto-Trigger Hint on Opponent Move
    private var autoHintPollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        FloatingStateHolder.initPrefs(this)

        createNotificationChannel()
        startForegroundCompat()

        // Set running status first so background coroutines can run
        FloatingStateHolder.updateServiceRunningStatus(true)

        setup100PercentTransparentCanvasOverlay()
        setupMinimalistFloatingControlBar()
        setupAlignFloatingPopup()

        observeState()
        if (FloatingStateHolder.isAutoHintEnabled.value) {
            startAutoHintPollingLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_MEDIA_PROJECTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    // Android 14 requirement: Foreground service MUST transition to MEDIA_PROJECTION type
                    // BEFORE calling getMediaProjection(resultCode, data)
                    startForegroundWithMediaProjection()
                    ScreenCaptureManager.initProjection(this, resultCode, data)
                }
            }
            ACTION_MEDIA_PROJECTION_READY -> startForegroundCompat()
        }
        return START_STICKY
    }

    private fun startForegroundWithMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (ScreenCaptureManager.isScreenCaptureActive.value) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            }
            if (type != 0) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "16 Ghuti Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live 16 Ghuti hint overlays and screen reading assistance"
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("16 Ghuti Floating Assistant Active")
            .setContentText("Transparent hint overlay & screen reader ready")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setup100PercentTransparentCanvasOverlay() {
        val wm = windowManager ?: return

        transparentCanvasView = DirectBoardOverlayView(this)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        canvasWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        transparentCanvasView?.setOnTouchListener { _, event ->
            if (FloatingStateHolder.isAlignModeActive.value) {
                transparentCanvasView?.onTouchEvent(event) ?: false
            } else if (FloatingStateHolder.isSyncModeActive.value && event.action == MotionEvent.ACTION_UP) {
                val touchedNode = transparentCanvasView?.findTouchedNode(event.rawX, event.rawY)
                if (touchedNode != null) {
                    FloatingStateHolder.cycleNodePiece(touchedNode)
                    showHudToast("Node $touchedNode toggled")
                }
                true
            } else {
                false
            }
        }

        try {
            wm.addView(transparentCanvasView, canvasWindowParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupMinimalistFloatingControlBar() {
        val wm = windowManager ?: return

        val container = FrameLayout(this)
        controlBarContainer = container

        // Modern Glassmorphism Pill Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121824")) // 90% dark slate
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#404E67"))
            }
            background = bg
            elevation = dp(8).toFloat()
        }

        // 1. "10s Hint" Button (Primary Neon Action)
        val hintBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))

            val hintBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676")) // Neon Green
                cornerRadius = dp(20).toFloat()
            }
            background = hintBg

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_dialog_info)
                setColorFilter(Color.parseColor("#003314"))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(6)
                }
            }
            addView(icon)

            hintBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = "10s Hint"
                setTextColor(Color.parseColor("#003314"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(hintBtnLabel)

            setOnClickListener {
                try {
                    if (FloatingStateHolder.isAnalyzing.value) return@setOnClickListener

                    // Strictly clear previous hint overlay and bestMove immediately to avoid duplicate arrows
                    transparentCanvasView?.clearHint()
                    FloatingStateHolder.clearBestMove()

                    // Dismiss alignment mode if open
                    if (FloatingStateHolder.isAlignModeActive.value) {
                        FloatingStateHolder.toggleAlignMode(false)
                    }

                    if (ScreenCaptureManager.isScreenCaptureActive.value) {
                        showHudToast("Scanning screen & computing hint...")
                        performSafeScreenScan { summary ->
                            try {
                                FloatingStateHolder.setSyncMode(true)
                                FloatingStateHolder.triggerScanFlash()
                                if (summary.success) {
                                    showHudToast("Scanned: ${summary.blueCount} Blue, ${summary.redCount} Red. Computing...")
                                } else {
                                    showHudToast(summary.message)
                                }
                                FloatingStateHolder.triggerAnalysis { result ->
                                    val move = result.bestMove
                                    if (move != null) {
                                        val action = if (move.isCapture) "Capture jump" else "Step"
                                        showHudToast("$action: ${move.from} → ${move.to}")
                                    } else {
                                        showHudToast("No valid move found")
                                    }
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                showHudToast("Hint error: ${e.localizedMessage ?: "Unknown"}")
                            }
                        }
                    } else {
                        FloatingStateHolder.setSyncMode(true)
                        showHudToast("Calculating best 16 Ghuti move...")
                        FloatingStateHolder.triggerAnalysis { result ->
                            val move = result.bestMove
                            if (move != null) {
                                val action = if (move.isCapture) "Capture jump" else "Step"
                                showHudToast("$action: ${move.from} → ${move.to}")
                            } else {
                                showHudToast("No move found. Start Screen Capture or Scan!")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    showHudToast("Error: ${e.localizedMessage ?: "Try again"}")
                }
            }
        }
        toolbar.addView(hintBtn)

        // 2. "Scan" Button (Live screen sampling & Permanent Floating Map Sync)
        val scanBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))

            val scanBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            background = scanBg

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(Color.parseColor("#38BDF8"))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(4)
                }
            }
            addView(icon)

            val label = TextView(this@FloatingOverlayService).apply {
                text = "Scan"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(label)

            setOnClickListener {
                try {
                    showHudToast("Scanning & syncing game board...")
                    performSafeScreenScan { summary ->
                        try {
                            FloatingStateHolder.setSyncMode(true)
                            FloatingStateHolder.triggerScanFlash()
                            if (summary.success) {
                                showHudToast("Synced: ${summary.blueCount} Blue, ${summary.redCount} Red ✓ (Map Permanent)")
                            } else {
                                showHudToast(summary.message)
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    showHudToast("Scan failed: ${e.localizedMessage ?: "Unknown"}")
                }
            }
        }
        val scanParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(6)
        }
        toolbar.addView(scanBtn, scanParams)

        // 3. "Auto Hint" Toggle Button (Triggers 10s hint automatically 3s after opponent move)
        val autoHintBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            autoHintBtnLayout = this

            val isAuto = FloatingStateHolder.isAutoHintEnabled.value
            val autoBg = GradientDrawable().apply {
                setColor(if (isAuto) Color.parseColor("#083344") else Color.parseColor("#1E293B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (isAuto) Color.parseColor("#22D3EE") else Color.parseColor("#334155"))
            }
            background = autoBg

            autoHintBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = if (isAuto) "Auto: ON" else "Auto: OFF"
                setTextColor(if (isAuto) Color.parseColor("#22D3EE") else Color.parseColor("#94A3B8"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(autoHintBtnLabel)

            setOnClickListener {
                FloatingStateHolder.toggleAutoHint()
                val next = FloatingStateHolder.isAutoHintEnabled.value
                showHudToast(if (next) "Auto-Hint ON: Triggers 3s after opponent moves ✓" else "Auto-Hint OFF")
            }
        }
        val autoParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(6)
        }
        toolbar.addView(autoHintBtn, autoParams)

        // 4. "Edit / Align" Button (Interactive alignment mode toggle)
        val alignBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            alignBtnLayout = this

            val alignBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = alignBg

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                setColorFilter(Color.parseColor("#FACC15")) // Gold
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(4)
                }
            }
            addView(icon)

            alignBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = "Edit / Align"
                setTextColor(Color.parseColor("#FACC15"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(alignBtnLabel)

            setOnClickListener {
                FloatingStateHolder.toggleAlignMode()
                val isNowActive = FloatingStateHolder.isAlignModeActive.value
                if (isNowActive) {
                    showHudToast("Drag screen with 1 finger | Pinch to scale")
                } else {
                    FloatingStateHolder.savePrefs()
                    showHudToast("Board Locked ✓ 100% Transparent Overlay")
                }
            }
        }
        val alignParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(6)
        }
        toolbar.addView(alignBtn, alignParams)

        // 5. "Reset" / Clear Move Button
        val resetBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.parseColor("#94A3B8"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                shape = GradientDrawable.OVAL
            }
            background = btnBg
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                transparentCanvasView?.clearHint()
                FloatingStateHolder.clearBestMove()
                FloatingStateHolder.setSyncMode(false)
                showHudToast("Overlay map & hints cleared")
            }
        }
        toolbar.addView(resetBtn)

        // 5. Close Button
        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#EF4444"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            val closeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                shape = GradientDrawable.OVAL
            }
            background = closeBg
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                stopSelf()
            }
        }
        toolbar.addView(closeBtn)

        container.addView(toolbar)

        // HUD Quick Toast View
        hudToastView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            val toastBg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6000000"))
                cornerRadius = dp(14).toFloat()
            }
            background = toastBg
            gravity = Gravity.CENTER
        }
        val toastParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        }
        container.addView(hudToastView, toastParams)

        controlBarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }

        // Draggable Toolbar Touch Listener
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var isMove = false

        toolbar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = controlBarParams?.x ?: 0
                    initialY = controlBarParams?.y ?: 0
                    isMove = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isMove = true
                    }
                    controlBarParams?.x = initialX + dx
                    controlBarParams?.y = initialY + dy
                    controlBarContainer?.let { c ->
                        controlBarParams?.let { p ->
                            wm.updateViewLayout(c, p)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMove) {
                        toolbar.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, controlBarParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Safely executes screen scanning by temporarily hiding the transparent overlay.
     * This prevents our own rendered beads and hint arrows from being re-captured
     * into the screen capture buffer, completely eliminating ghost and duplicate pieces.
     */
    private fun performSafeScreenScan(onFinished: (ScanSummary) -> Unit) {
        val canvas = transparentCanvasView
        canvas?.clearHint()
        val wasVisible = (canvas?.visibility == View.VISIBLE)
        if (canvas != null) {
            canvas.visibility = View.INVISIBLE
        }
        mainHandler.postDelayed({
            try {
                ScreenCaptureManager.scanBoardFromScreen { summary ->
                    try {
                        if (wasVisible || FloatingStateHolder.isSyncModeActive.value) {
                            canvas?.visibility = View.VISIBLE
                        }
                        onFinished(summary)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        canvas?.visibility = View.VISIBLE
                        onFinished(summary)
                    }
                }
            } catch (e: Throwable) {
                canvas?.visibility = View.VISIBLE
                e.printStackTrace()
                onFinished(ScanSummary(0, 0, 37, false, "Scan failed: ${e.message}"))
            }
        }, 120L)
    }

    /**
     * Enhanced Floating Alignment HUD:
     * D-Pad fine nudging, Scale, Wings, Presets, and Lock button.
     */
    @SuppressLint("SetTextI18n")
    private fun setupAlignFloatingPopup() {
        val wm = windowManager ?: return

        alignPopupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(12))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F50F172A")) // Dark Navy
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#FACC15")) // Gold border in align mode
            }
            background = bg
            elevation = dp(14).toFloat()
        }

        // Header Title
        val header = TextView(this).apply {
            text = "🎯 16 Ghuti Board Alignment"
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        alignPopupContainer?.addView(header)

        val sub = TextView(this).apply {
            text = "1-Finger Drag to Move | 2-Finger Pinch to Resize | Arrows to Nudge"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        alignPopupContainer?.addView(sub)

        fun createBtn(text: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(5), dp(10), dp(5))
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#475569"))
                }
                background = btnBg
                setOnClickListener { onClick() }
            }
        }

        // 1. D-Pad Arrows for single-pixel precision nudging
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val upRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(createBtn("▲ Up") { FloatingStateHolder.nudgeOffset(0f, -0.003f) })
        }
        dpad.addView(upRow)

        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))

            addView(createBtn("◀ Left") { FloatingStateHolder.nudgeOffset(-0.003f, 0f) })

            val centerBtn = createBtn("Center") {
                FloatingStateHolder.setBoardHorizontalOffset(FloatingStateHolder.DEFAULT_H_OFFSET)
                FloatingStateHolder.setBoardVerticalOffset(FloatingStateHolder.DEFAULT_V_OFFSET)
            }
            val cParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            addView(centerBtn, cParams)

            addView(createBtn("Right ▶") { FloatingStateHolder.nudgeOffset(0.003f, 0f) })
        }
        dpad.addView(midRow)

        val downRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(createBtn("▼ Down") { FloatingStateHolder.nudgeOffset(0f, 0.003f) })
        }
        dpad.addView(downRow)

        alignPopupContainer?.addView(dpad)

        // 2. Adjustments Row: Board Size, Vertical Scale, Wings Height, Wings Width
        val adjustmentsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))

            fun createControlGroup(title: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
                return LinearLayout(this@FloatingOverlayService).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL

                    val lbl = TextView(this@FloatingOverlayService).apply {
                        text = title
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 10f
                    }
                    addView(lbl)

                    val btnRow = LinearLayout(this@FloatingOverlayService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(2), 0, 0)

                        val minusBtn = createBtn(" - ") { onMinus() }
                        addView(minusBtn)

                        val plusBtn = createBtn(" + ") { onPlus() }
                        val pParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(4) }
                        addView(plusBtn, pParams)
                    }
                    addView(btnRow)
                }
            }

            val sizeGroup = createControlGroup("Size",
                onMinus = {
                    val cur = FloatingStateHolder.boardScaleFactor.value
                    FloatingStateHolder.setBoardScale(cur - 0.015f)
                },
                onPlus = {
                    val cur = FloatingStateHolder.boardScaleFactor.value
                    FloatingStateHolder.setBoardScale(cur + 0.015f)
                }
            )
            addView(sizeGroup)

            val vScaleGroup = createControlGroup("Height",
                onMinus = {
                    val cur = FloatingStateHolder.boardVerticalScaleFactor.value
                    FloatingStateHolder.setBoardVerticalScale(cur - 0.015f)
                },
                onPlus = {
                    val cur = FloatingStateHolder.boardVerticalScaleFactor.value
                    FloatingStateHolder.setBoardVerticalScale(cur + 0.015f)
                }
            )
            val vsParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(vScaleGroup, vsParams)

            val wingHeightGroup = createControlGroup("Wings H",
                onMinus = {
                    val cur = FloatingStateHolder.triangleRatio.value
                    FloatingStateHolder.setTriangleRatio(cur - 0.02f)
                },
                onPlus = {
                    val cur = FloatingStateHolder.triangleRatio.value
                    FloatingStateHolder.setTriangleRatio(cur + 0.02f)
                }
            )
            val whParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(wingHeightGroup, whParams)

            val wingWidthGroup = createControlGroup("Wings W",
                onMinus = {
                    val cur = FloatingStateHolder.triangleWidthRatio.value
                    FloatingStateHolder.setTriangleWidthRatio(cur - 0.02f)
                },
                onPlus = {
                    val cur = FloatingStateHolder.triangleWidthRatio.value
                    FloatingStateHolder.setTriangleWidthRatio(cur + 0.02f)
                }
            )
            val wwParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(wingWidthGroup, wwParams)

            val pieceSizeGroup = createControlGroup("Piece",
                onMinus = {
                    val cur = FloatingStateHolder.nodeRadiusFactor.value
                    FloatingStateHolder.setNodeRadiusFactor(cur - 0.05f)
                },
                onPlus = {
                    val cur = FloatingStateHolder.nodeRadiusFactor.value
                    FloatingStateHolder.setNodeRadiusFactor(cur + 0.05f)
                }
            )
            val psParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(pieceSizeGroup, psParams)
        }
        alignPopupContainer?.addView(adjustmentsRow)

        // 3. Quick Game Presets: AlignIt / Standard
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))

            fun createPresetChip(name: String, label: String): TextView {
                return TextView(this@FloatingOverlayService).apply {
                    text = label
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 10f
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    val chipBg = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F172A"))
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), Color.parseColor("#0284C7"))
                    }
                    background = chipBg
                    setOnClickListener {
                        FloatingStateHolder.applyPreset(name)
                        showHudToast("Applied $label preset")
                    }
                }
            }

            addView(createPresetChip("AlignIt", "Default 16 Guti"))
            val c1 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            addView(createPresetChip("Compact", "Compact"), c1)
            val c2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            addView(createPresetChip("Expanded", "Expanded"), c2)
        }
        alignPopupContainer?.addView(presetRow)

        // 4. "Save & Lock Alignment" Button
        val doneBtn = TextView(this).apply {
            text = "💾 Save & Lock Alignment (100% Transparent)"
            setTextColor(Color.parseColor("#003314"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val doneBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = dp(12).toFloat()
            }
            background = doneBg
            setOnClickListener {
                FloatingStateHolder.savePrefs()
                FloatingStateHolder.toggleAlignMode(false)
                showHudToast("Alignment Saved Permanently! Overlay is 100% Transparent ✓")
            }
        }
        val doneParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        }
        alignPopupContainer?.addView(doneBtn, doneParams)

        alignPopupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }

        try {
            wm.addView(alignPopupContainer, alignPopupParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeState() {
        serviceScope.launch {
            FloatingStateHolder.bestMove.collect { move ->
                if (move == null) {
                    transparentCanvasView?.clearHint()
                }
                refreshCanvasData()
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isAutoHintEnabled.collect { isAuto ->
                val bg = autoHintBtnLayout?.background as? GradientDrawable
                if (isAuto) {
                    bg?.setColor(Color.parseColor("#083344"))
                    bg?.setStroke(dp(1), Color.parseColor("#22D3EE"))
                    autoHintBtnLabel?.text = "Auto: ON"
                    autoHintBtnLabel?.setTextColor(Color.parseColor("#22D3EE"))
                    startAutoHintPollingLoop()
                } else {
                    bg?.setColor(Color.parseColor("#1E293B"))
                    bg?.setStroke(dp(1), Color.parseColor("#475569"))
                    autoHintBtnLabel?.text = "Auto: OFF"
                    autoHintBtnLabel?.setTextColor(Color.parseColor("#94A3B8"))
                    stopAutoHintPollingLoop()
                }
            }
        }

        serviceScope.launch {
            ScreenCaptureManager.isScreenCaptureActive.collect { isActive ->
                if (isActive && FloatingStateHolder.isAutoHintEnabled.value) {
                    startAutoHintPollingLoop()
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isAnalyzing.collect { analyzing ->
                if (!analyzing && FloatingStateHolder.analysisCountdownSec.value == 0) {
                    hintBtnLabel?.text = "10s Hint"
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.analysisCountdownSec.collect { sec ->
                if (sec > 0) {
                    hintBtnLabel?.text = "Hint (${sec}s)"
                } else if (!FloatingStateHolder.isAnalyzing.value) {
                    hintBtnLabel?.text = "10s Hint"
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.boardVerticalOffsetFactor.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.boardHorizontalOffsetFactor.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.boardScaleFactor.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.boardVerticalScaleFactor.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.triangleRatio.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.triangleWidthRatio.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.nodeRadiusFactor.collect { refreshCanvasData() }
        }
        serviceScope.launch {
            FloatingStateHolder.scanFlashActive.collect { refreshCanvasData() }
        }

        serviceScope.launch {
            FloatingStateHolder.isAlignModeActive.collect { isAlign ->
                alignPopupContainer?.visibility = if (isAlign) View.VISIBLE else View.GONE
                updateCanvasTouchability(isAlign)
                refreshCanvasData()

                val bg = alignBtnLayout?.background as? GradientDrawable
                if (isAlign) {
                    bg?.setColor(Color.parseColor("#422006"))
                    bg?.setStroke(dp(1), Color.parseColor("#FACC15"))
                    alignBtnLabel?.text = "Done"
                } else {
                    bg?.setColor(Color.parseColor("#1E293B"))
                    bg?.setStroke(dp(1), Color.parseColor("#334155"))
                    alignBtnLabel?.text = "Align"
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isSyncModeActive.collect { isSync ->
                updateCanvasTouchability(FloatingStateHolder.isAlignModeActive.value)
                refreshCanvasData()
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isAppInForeground.collect { inForeground ->
                // Persistent Display: Transparent canvas must always be visible so hint arrow never vanishes
                transparentCanvasView?.visibility = View.VISIBLE
                if (inForeground) {
                    alignPopupContainer?.visibility = View.GONE
                } else if (FloatingStateHolder.isAlignModeActive.value) {
                    alignPopupContainer?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateCanvasTouchability(touchable: Boolean) {
        val wm = windowManager ?: return
        val canvas = transparentCanvasView ?: return
        val params = canvasWindowParams ?: return

        if (touchable) {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        try {
            wm.updateViewLayout(canvas, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshCanvasData() {
        transparentCanvasView?.updateData(
            pieces = FloatingStateHolder.boardState.value,
            move = FloatingStateHolder.bestMove.value,
            isSync = FloatingStateHolder.isSyncModeActive.value,
            isAlign = FloatingStateHolder.isAlignModeActive.value,
            showNodes = FloatingStateHolder.showNodeIndicators.value,
            vOffset = FloatingStateHolder.boardVerticalOffsetFactor.value,
            hOffset = FloatingStateHolder.boardHorizontalOffsetFactor.value,
            scale = FloatingStateHolder.boardScaleFactor.value,
            vScale = FloatingStateHolder.boardVerticalScaleFactor.value,
            triRatio = FloatingStateHolder.triangleRatio.value,
            triWidthRatio = FloatingStateHolder.triangleWidthRatio.value,
            radiusFactor = FloatingStateHolder.nodeRadiusFactor.value,
            flash = FloatingStateHolder.scanFlashActive.value
        )
    }

    private fun showHudToast(message: String) {
        mainHandler.post {
            try {
                hudToastView?.let { view ->
                    view.text = message
                    view.visibility = View.VISIBLE
                    view.removeCallbacks(hideHudToastRunnable)
                    view.postDelayed(hideHudToastRunnable, 2600L)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private val hideHudToastRunnable = Runnable {
        hudToastView?.visibility = View.GONE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    /**
     * Persistent background polling coroutine for 'Auto: ON' mode.
     * Quietly scans the board state every 1.5 to 2 seconds (1800ms).
     * As soon as a board state change is detected (meaning the opponent has made a move):
     * Waits 3 seconds for animation stability, then automatically triggers the
     * full 10-second hint calculation and renders the single hint arrow.
     */
    private fun startAutoHintPollingLoop() {
        if (autoHintPollingJob?.isActive == true) return

        autoHintPollingJob = serviceScope.launch(Dispatchers.Default) {
            var lastObservedBoard: Array<Piece>? = null

            // Seed initial baseline if existing board is already populated
            val currentPieces = FloatingStateHolder.currentBoard.pieces
            if (currentPieces.any { it != Piece.EMPTY }) {
                lastObservedBoard = currentPieces.clone()
            }

            while (FloatingStateHolder.isOverlayServiceRunning.value &&
                FloatingStateHolder.isAutoHintEnabled.value
            ) {
                // 2. Quietly scan the board state every 1.5 to 2 seconds (1800ms)
                delay(1800L)

                try {
                    val isCaptureActive = ScreenCaptureManager.isScreenCaptureActive.value
                    val isAutoHintOn = FloatingStateHolder.isAutoHintEnabled.value
                    val isAnalyzing = FloatingStateHolder.isAnalyzing.value
                    val isAlignMode = FloatingStateHolder.isAlignModeActive.value

                    if (!isCaptureActive || !isAutoHintOn || isAnalyzing || isAlignMode) {
                        continue
                    }

                    // Quiet non-intrusive snapshot from ImageReader / screen frame
                    val currentSample = ScreenCaptureManager.sampleCurrentBoardFromScreen() ?: continue
                    val blueCount = currentSample.count { it == Piece.BLUE }
                    val redCount = currentSample.count { it == Piece.RED }

                    // Skip empty or transient black frames
                    if (blueCount + redCount < 2) {
                        continue
                    }

                    // Initialize baseline if this is our first successful frame
                    if (lastObservedBoard == null) {
                        lastObservedBoard = currentSample.clone()
                        withContext(Dispatchers.Main) {
                            FloatingStateHolder.updateAllNodes(currentSample)
                        }
                        continue
                    }

                    val previous = lastObservedBoard ?: continue

                    // Check if board state has changed
                    if (!currentSample.contentEquals(previous)) {
                        val prevBlue = previous.count { it == Piece.BLUE }
                        val prevRed = previous.count { it == Piece.RED }

                        val redPositionsChanged = (prevRed != redCount) || (0 until SholoGutiBoard.TOTAL_NODES).any { idx ->
                            previous[idx] == Piece.RED && currentSample[idx] != Piece.RED
                        }
                        val blueCaptured = (blueCount < prevBlue)
                        val isOpponentMove = redPositionsChanged || blueCaptured

                        // User move detection
                        val bluePositionsChanged = (prevBlue != blueCount) || (0 until SholoGutiBoard.TOTAL_NODES).any { idx ->
                            previous[idx] == Piece.BLUE && currentSample[idx] != Piece.BLUE
                        }

                        if (!isOpponentMove && bluePositionsChanged) {
                            // User just executed a move! Clear the old hint arrow and update baseline
                            withContext(Dispatchers.Main) {
                                transparentCanvasView?.clearHint()
                                FloatingStateHolder.clearBestMove()
                                FloatingStateHolder.updateAllNodes(currentSample)
                            }
                            lastObservedBoard = currentSample.clone()
                            continue
                        }

                        // Opponent move detected (or board state changed)
                        withContext(Dispatchers.Main) {
                            // Clear old hint arrow immediately to prevent overlapping arrows
                            transparentCanvasView?.clearHint()
                            FloatingStateHolder.clearBestMove()
                            showHudToast("Opponent moved! Waiting 3s for stability...")
                        }

                        // 3. Wait 3 seconds for animation and board stability
                        delay(3000L)

                        // Quietly re-sample after 3s to guarantee final settled state
                        val stableSample = ScreenCaptureManager.sampleCurrentBoardFromScreen() ?: currentSample
                        val stableBlue = stableSample.count { it == Piece.BLUE }
                        val stableRed = stableSample.count { it == Piece.RED }

                        if (stableBlue + stableRed >= 2) {
                            lastObservedBoard = stableSample.clone()

                            withContext(Dispatchers.Main) {
                                // Clear prior hint
                                transparentCanvasView?.clearHint()
                                FloatingStateHolder.clearBestMove()

                                // Update board state and trigger flash indicator
                                FloatingStateHolder.updateAllNodes(stableSample)
                                FloatingStateHolder.triggerScanFlash()
                                showHudToast("Board Stable ✓ Auto-calculating 10s Hint...")

                                // Automatically trigger the 10-second hint calculation and render hint arrow
                                FloatingStateHolder.triggerAnalysis { res ->
                                    val move = res.bestMove
                                    if (move != null) {
                                        val action = if (move.isCapture) "Capture jump" else "Step"
                                        showHudToast("Auto-Hint: $action ${move.from} → ${move.to} ✓")
                                    } else {
                                        showHudToast("Auto-Hint: No valid move found")
                                    }
                                }
                            }
                        } else {
                            lastObservedBoard = currentSample.clone()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun stopAutoHintPollingLoop() {
        autoHintPollingJob?.cancel()
        autoHintPollingJob = null
    }

    override fun onDestroy() {
        stopAutoHintPollingLoop()
        val wm = windowManager
        transparentCanvasView?.let {
            try { wm?.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        controlBarContainer?.let {
            try { wm?.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        alignPopupContainer?.let {
            try { wm?.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        FloatingStateHolder.updateServiceRunningStatus(false)
        ScreenCaptureManager.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_MEDIA_PROJECTION_START = "com.example.service.ACTION_MEDIA_PROJECTION_START"
        const val ACTION_MEDIA_PROJECTION_READY = "com.example.service.ACTION_MEDIA_PROJECTION_READY"
        const val EXTRA_RESULT_CODE = "com.example.service.EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.example.service.EXTRA_RESULT_DATA"
        private const val NOTIFICATION_CHANNEL_ID = "floating_controller_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        fun startWithMediaProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_MEDIA_PROJECTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateMediaProjectionForeground(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_MEDIA_PROJECTION_READY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
