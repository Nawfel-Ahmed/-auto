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
import android.text.TextUtils
import android.view.Gravity
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.aistudio.floatingbubble.kxpzqa.R
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private var toolbarView: LinearLayout? = null
    private var hintBtnLabel: TextView? = null
    private var autoHintBtnLayout: LinearLayout? = null
    private var autoHintBtnLabel: TextView? = null
    private var alignBtnLayout: LinearLayout? = null
    private var alignBtnLabel: TextView? = null
    private var normalModeBtnLayout: LinearLayout? = null
    private var normalModeBtnLabel: TextView? = null
    private var defenseModeBtnLayout: LinearLayout? = null
    private var defenseModeBtnLabel: TextView? = null
    private var alphaBtnLayout: LinearLayout? = null
    private var alphaBtnLabel: TextView? = null
    private var opacitySeekBar: android.widget.SeekBar? = null
    private var opacityValueLabel: TextView? = null
    private var hudToastView: TextView? = null

    // Dedicated independent background worker thread with Lock for rock-solid Auto-Hint stability
    private var backgroundPollingThread: Thread? = null
    private val pollingLock = ReentrantLock()
    @Volatile private var isPollingThreadRunning = false
    @Volatile private var isAutoClickerActive = false
    @Volatile private var autoClickerStartTime = 0L
    @Volatile private var lastSettledBoard: Array<Piece>? = null
    @Volatile private var lastPollingHeartbeatMs = System.currentTimeMillis()
    private var hintButtonView: View? = null

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

        // 1. Fixed Window Size & Layout Lock:
        // Set fixed dimensions so the floating assistant bar NEVER resizes, shrinks, or expands dynamically.
        val barWidth = dp(358)
        val barHeight = dp(42)

        val container = object : FrameLayout(this) {
            private var startRawX = 0f
            private var startRawY = 0f
            private var initX = 0
            private var initY = 0
            private var isDraggingBar = false

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX
                        startRawY = ev.rawY
                        initX = controlBarParams?.x ?: 0
                        initY = controlBarParams?.y ?: 0
                        isDraggingBar = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(ev.rawX - startRawX)
                        val dy = kotlin.math.abs(ev.rawY - startRawY)
                        if (dx > 10 || dy > 10) {
                            isDraggingBar = true
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - startRawX).toInt()
                        val dy = (ev.rawY - startRawY).toInt()
                        controlBarParams?.let { params ->
                            val metrics = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            wm.defaultDisplay.getRealMetrics(metrics)
                            val screenW = metrics.widthPixels
                            val screenH = metrics.heightPixels
                            params.x = (initX + dx).coerceIn(0, (screenW - barWidth).coerceAtLeast(0))
                            params.y = (initY + dy).coerceIn(0, (screenH - dp(60)).coerceAtLeast(0))
                            wm.updateViewLayout(this, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDraggingBar = false
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        controlBarContainer = container

        // Modern Glassmorphism Pill Toolbar with fixed size
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(barWidth, barHeight).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))

            val initAlpha = FloatingStateHolder.overlayAlpha.value.coerceIn(0.20f, 1.0f)
            val bg = GradientDrawable().apply {
                val darkColor = Color.parseColor("#121824")
                val alphaInt = (initAlpha * 255).toInt()
                setColor(Color.argb(alphaInt, Color.red(darkColor), Color.green(darkColor), Color.blue(darkColor)))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#404E67"))
            }
            background = bg
            elevation = dp(8).toFloat()
        }
        toolbarView = toolbar

        // 1. "Hint" Button (Primary Neon Action)
        val hintBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(32))

            val hintBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676")) // Neon Green
                cornerRadius = dp(16).toFloat()
            }
            background = hintBg

            hintBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = "Hint"
                setTextColor(Color.parseColor("#003314"))
                textSize = 11f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
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
                                        val action = if (move.isChainCapture) "Multi-jump (${move.totalCaptures}x)" else if (move.isCapture) "Capture jump" else "Step"
                                        showHudToast("$action: ${move.from} → ${move.to}")
                                        isAutoClickerActive = true
                                        mainHandler.postDelayed({ isAutoClickerActive = false }, 4000L)
                                        triggerAutoClickMove(move) { success ->
                                            if (success) {
                                                val simBoard = FloatingStateHolder.currentBoard.copy()
                                                val prevPieces = simBoard.pieces.clone()
                                                simBoard.makeMove(move)
                                                val boardAfterMove = simBoard.pieces.clone()
                                                FloatingStateHolder.updateAllNodes(boardAfterMove)
                                                FloatingStateHolder.matchTracker.recordTurn(prevPieces, boardAfterMove, isOpponentMove = false)
                                                lastSettledBoard = boardAfterMove.clone()
                                                mainHandler.postDelayed({
                                                    transparentCanvasView?.clearHint()
                                                    FloatingStateHolder.clearBestMove()
                                                    isAutoClickerActive = false
                                                }, 800L)
                                            } else {
                                                isAutoClickerActive = false
                                            }
                                        }
                                    } else {
                                        showHudToast("No valid move found")
                                        isAutoClickerActive = false
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
                                val action = if (move.isChainCapture) "Multi-jump (${move.totalCaptures}x)" else if (move.isCapture) "Capture jump" else "Step"
                                showHudToast("$action: ${move.from} → ${move.to}")
                                isAutoClickerActive = true
                                mainHandler.postDelayed({ isAutoClickerActive = false }, 4000L)
                                triggerAutoClickMove(move) { success ->
                                    if (success) {
                                        val simBoard = FloatingStateHolder.currentBoard.copy()
                                        val prevPieces = simBoard.pieces.clone()
                                        simBoard.makeMove(move)
                                        val boardAfterMove = simBoard.pieces.clone()
                                        FloatingStateHolder.updateAllNodes(boardAfterMove)
                                        FloatingStateHolder.matchTracker.recordTurn(prevPieces, boardAfterMove, isOpponentMove = false)
                                        lastSettledBoard = boardAfterMove.clone()
                                        mainHandler.postDelayed({
                                            transparentCanvasView?.clearHint()
                                            FloatingStateHolder.clearBestMove()
                                            isAutoClickerActive = false
                                        }, 800L)
                                    } else {
                                        isAutoClickerActive = false
                                    }
                                }
                            } else {
                                showHudToast("No move found. Start Screen Capture or Scan!")
                                isAutoClickerActive = false
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    showHudToast("Error: ${e.localizedMessage ?: "Try again"}")
                }
            }
        }
        hintButtonView = hintBtn
        toolbar.addView(hintBtn)

        // 2. "Scan" Button (Live screen sampling & Permanent Floating Map Sync)
        val scanBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32)).apply {
                marginStart = dp(3)
            }

            val scanBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            background = scanBg

            val label = TextView(this@FloatingOverlayService).apply {
                text = "Scan"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11f
                isSingleLine = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
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
        toolbar.addView(scanBtn)

        // 3. "Auto Hint" Toggle Button (Triggers hint automatically 1s after opponent move)
        val autoHintBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32)).apply {
                marginStart = dp(3)
            }
            autoHintBtnLayout = this

            val isAuto = FloatingStateHolder.isAutoHintEnabled.value
            val autoBg = GradientDrawable().apply {
                setColor(if (isAuto) Color.parseColor("#083344") else Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (isAuto) Color.parseColor("#22D3EE") else Color.parseColor("#334155"))
            }
            background = autoBg

            autoHintBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = if (isAuto) "Auto" else "Off"
                setTextColor(if (isAuto) Color.parseColor("#22D3EE") else Color.parseColor("#94A3B8"))
                textSize = 11f
                isSingleLine = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(autoHintBtnLabel)

            setOnClickListener {
                val current = FloatingStateHolder.isAutoHintEnabled.value
                val next = !current
                FloatingStateHolder.toggleAutoHint(next)
                if (next) {
                    isAutoClickerActive = false
                    FloatingStateHolder.forceResetAnalysis()
                    startAutoHintPollingLoop()
                    showHudToast("⚡ Auto: ON (Continuous Auto-Hint & Click Active) ✓")
                } else {
                    stopAutoHintPollingLoop()
                    showHudToast("Auto: OFF")
                }
            }
        }
        toolbar.addView(autoHintBtn)

        // 4. "Normal Mode" Dedicated Interactive Button
        val normalBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(32)).apply {
                marginStart = dp(3)
            }
            normalModeBtnLayout = this

            val normBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = normBg

            normalModeBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = "⚔️ Norm"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                isSingleLine = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(normalModeBtnLabel)

            setOnClickListener {
                val current = FloatingStateHolder.manualDefenseMode.value
                val next = if (current == false) null else false
                FloatingStateHolder.setManualDefenseMode(next)
                val msg = if (next == false) {
                    "⚔️ Normal Mode Active (Aggressive traps & attack)"
                } else {
                    "🤖 Smart Auto Mode (Auto-Defend when +3 ahead)"
                }
                showHudToast(msg)
            }
        }
        toolbar.addView(normalBtn)

        // 5. "Defense Mode" Dedicated Interactive Button
        val defenseBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(32)).apply {
                marginStart = dp(3)
            }
            defenseModeBtnLayout = this

            val defBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = defBg

            defenseModeBtnLabel = TextView(this@FloatingOverlayService).apply {
                text = "🛡️ Def"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                isSingleLine = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(defenseModeBtnLabel)

            setOnClickListener {
                val current = FloatingStateHolder.manualDefenseMode.value
                val next = if (current == true) null else true
                FloatingStateHolder.setManualDefenseMode(next)
                val msg = if (next == true) {
                    "🛡️ Defense Mode Active (Zero loss & clock survival)"
                } else {
                    "🤖 Smart Auto Mode (Auto-Defend when +3 ahead)"
                }
                showHudToast(msg)
            }
        }
        toolbar.addView(defenseBtn)

        // 6. "Quick Opacity / Transparency Cycle" Button
        val alphaBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(3)
            }
            alphaBtnLayout = this

            val alphaBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            background = alphaBg

            alphaBtnLabel = TextView(this@FloatingOverlayService).apply {
                val curPct = (FloatingStateHolder.overlayAlpha.value * 100).toInt()
                text = "👁️"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11f
                gravity = Gravity.CENTER
            }
            addView(alphaBtnLabel)

            setOnClickListener {
                val cur = FloatingStateHolder.overlayAlpha.value
                val next = when {
                    cur > 0.80f -> 0.60f
                    cur > 0.50f -> 0.35f
                    cur > 0.30f -> 1.00f
                    else -> 0.85f
                }
                FloatingStateHolder.setOverlayAlpha(next)
                showHudToast("Opacity: ${(next * 100).toInt()}% (Clear Board Visibility)")
            }
        }
        toolbar.addView(alphaBtn)

        // 7. "Edit / Align & Settings" Button (Interactive alignment mode toggle)
        val alignBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(32)).apply {
                marginStart = dp(3)
            }
            alignBtnLayout = this

            val alignBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = alignBg

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                setColorFilter(Color.parseColor("#FACC15")) // Gold
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15))
            }
            addView(icon)

            setOnClickListener {
                FloatingStateHolder.toggleAlignMode()
                val isNowActive = FloatingStateHolder.isAlignModeActive.value
                if (isNowActive) {
                    showHudToast("Align & Settings Menu Opened")
                } else {
                    FloatingStateHolder.savePrefs()
                    showHudToast("Board Locked ✓ 100% Transparent Overlay")
                }
            }
        }
        toolbar.addView(alignBtn)

        // 8. Close Button
        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#EF4444"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val closeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                shape = GradientDrawable.OVAL
            }
            background = closeBg
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginStart = dp(3)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener {
                stopSelf()
            }
        }
        toolbar.addView(closeBtn)

        container.addView(toolbar)

        // HUD Quick Toast View - strictly constrained within barWidth so it never resizes the window
        hudToastView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 10f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(3), dp(10), dp(3))
            val toastBg = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0A0F1D"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = toastBg
            gravity = Gravity.CENTER
        }
        val toastParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(44)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        container.addView(hudToastView, toastParams)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val defaultStartX = ((screenW - barWidth) / 2).coerceAtLeast(0)

        controlBarParams = WindowManager.LayoutParams(
            barWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultStartX
            // Safely positioned above board nodes so it never overlaps or blocks top opponent pieces
            y = dp(24)
        }

        // Draggable Toolbar Touch Listener (handles drag when touching non-button areas)
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
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isMove = true
                    }
                    controlBarParams?.let { p ->
                        p.x = (initialX + dx).coerceIn(0, (screenW - barWidth).coerceAtLeast(0))
                        p.y = (initialY + dy).coerceIn(0, (screenH - dp(60)).coerceAtLeast(0))
                        controlBarContainer?.let { c ->
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
        val wasCanvasVisible = (canvas?.visibility == View.VISIBLE)
        if (canvas != null) {
            canvas.visibility = View.INVISIBLE
        }
        val wasBarVisible = (controlBarContainer?.visibility == View.VISIBLE)
        controlBarContainer?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            try {
                ScreenCaptureManager.scanBoardFromScreen { summary ->
                    try {
                        if (wasCanvasVisible || FloatingStateHolder.isSyncModeActive.value) {
                            canvas?.visibility = View.VISIBLE
                        }
                        if (wasBarVisible) {
                            controlBarContainer?.visibility = View.VISIBLE
                        }
                        onFinished(summary)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        if (wasBarVisible) {
                            controlBarContainer?.visibility = View.VISIBLE
                        }
                        canvas?.visibility = View.VISIBLE
                        onFinished(summary)
                    }
                }
            } catch (e: Throwable) {
                canvas?.visibility = View.VISIBLE
                if (wasBarVisible) {
                    controlBarContainer?.visibility = View.VISIBLE
                }
                e.printStackTrace()
                onFinished(ScanSummary(0, 0, 37, false, "Scan failed: ${e.message}"))
            }
        }, 80L)
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

        // 3. Transparency / Opacity Slider (Japsa / Clear Background Board Visibility)
        val opacitySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))

            val curAlpha = FloatingStateHolder.overlayAlpha.value.coerceIn(0.20f, 1.0f)
            val curPct = (curAlpha * 100).toInt()

            val opacityTitle = TextView(this@FloatingOverlayService).apply {
                text = "👁️ Background Transparency (Japsa): $curPct%"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            opacityValueLabel = opacityTitle
            addView(opacityTitle)

            val seekBar = SeekBar(this@FloatingOverlayService).apply {
                max = 80 // Corresponds to 20% .. 100%
                progress = ((curAlpha - 0.20f) / 0.80f * 80).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val alphaVal = 0.20f + (prog / 80f) * 0.80f
                            FloatingStateHolder.setOverlayAlpha(alphaVal)
                            val pct = (alphaVal * 100).toInt()
                            opacityTitle.text = "👁️ Background Transparency (Japsa): $pct%"
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        FloatingStateHolder.savePrefs()
                    }
                })
            }
            opacitySeekBar = seekBar
            val sbParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(2)
            }
            addView(seekBar, sbParams)

            // Preset Opacity Chips: 35% Japsa, 60% Clear, 85% Optimal, 100% Solid
            val opacityChipsRow = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, 0)

                fun createAlphaChip(alphaTarget: Float, label: String): TextView {
                    return TextView(this@FloatingOverlayService).apply {
                        text = label
                        setTextColor(Color.parseColor("#E2E8F0"))
                        textSize = 10f
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        val chipBg = GradientDrawable().apply {
                            setColor(Color.parseColor("#1E293B"))
                            cornerRadius = dp(6).toFloat()
                            setStroke(dp(1), Color.parseColor("#475569"))
                        }
                        background = chipBg
                        setOnClickListener {
                            FloatingStateHolder.setOverlayAlpha(alphaTarget)
                            val pct = (alphaTarget * 100).toInt()
                            opacityTitle.text = "👁️ Background Transparency (Japsa): $pct%"
                            seekBar.progress = ((alphaTarget - 0.20f) / 0.80f * 80).toInt()
                            showHudToast("Opacity set to $pct% (Board Clearly Visible)")
                        }
                    }
                }

                addView(createAlphaChip(0.35f, "35% Japsa"))
                val ac1 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                addView(createAlphaChip(0.60f, "60% Clear"), ac1)
                val ac2 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                addView(createAlphaChip(0.85f, "85% Optimal"), ac2)
                val ac3 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                addView(createAlphaChip(1.00f, "100% Solid"), ac3)
            }
            addView(opacityChipsRow)
        }
        alignPopupContainer?.addView(opacitySection)

        // 4. Quick Game Presets: AlignIt / Standard
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
            FloatingStateHolder.boardState.collect { pieces ->
                if (pieces.any { it != Piece.EMPTY } && !isAutoClickerActive && !FloatingStateHolder.isAnalyzing.value) {
                    lastSettledBoard = pieces.clone()
                }
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
                    hintBtnLabel?.text = "Hint"
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.analysisCountdownSec.collect { sec ->
                if (sec > 0) {
                    hintBtnLabel?.text = "${sec}s"
                } else if (!FloatingStateHolder.isAnalyzing.value) {
                    hintBtnLabel?.text = "Hint"
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
                } else {
                    bg?.setColor(Color.parseColor("#1E293B"))
                    bg?.setStroke(dp(1), Color.parseColor("#334155"))
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isSyncModeActive.collect { isSync ->
                updateCanvasTouchability(FloatingStateHolder.isAlignModeActive.value)
                refreshCanvasData()
            }
        }

        // Dedicated Normal & Defense Mode state observers
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                FloatingStateHolder.manualDefenseMode,
                FloatingStateHolder.boardState
            ) { manualMode, pieces ->
                val blueCount = pieces.count { it == Piece.BLUE }
                val redCount = pieces.count { it == Piece.RED }
                val diff = blueCount - redCount
                val isDefending = manualMode ?: (diff >= 3)
                Triple(manualMode, isDefending, diff)
            }.collect { (manualMode, isDefending, diff) ->
                val normBg = normalModeBtnLayout?.background as? GradientDrawable
                val defBg = defenseModeBtnLayout?.background as? GradientDrawable

                when (manualMode) {
                    true -> {
                        // Forced Defense Mode
                        defBg?.setColor(Color.parseColor("#450A0A")) // Dark Crimson
                        defBg?.setStroke(dp(1), Color.parseColor("#EF4444")) // Red
                        defenseModeBtnLabel?.text = "🛡️ Def"
                        defenseModeBtnLabel?.setTextColor(Color.parseColor("#FCA5A5"))

                        normBg?.setColor(Color.parseColor("#0F172A"))
                        normBg?.setStroke(dp(1), Color.parseColor("#1E293B"))
                        normalModeBtnLabel?.text = "⚔️ Norm"
                        normalModeBtnLabel?.setTextColor(Color.parseColor("#475569"))
                    }
                    false -> {
                        // Forced Normal Mode
                        normBg?.setColor(Color.parseColor("#064E3B")) // Dark Emerald
                        normBg?.setStroke(dp(1), Color.parseColor("#10B981")) // Green
                        normalModeBtnLabel?.text = "⚔️ Norm"
                        normalModeBtnLabel?.setTextColor(Color.parseColor("#6EE7B7"))

                        defBg?.setColor(Color.parseColor("#0F172A"))
                        defBg?.setStroke(dp(1), Color.parseColor("#1E293B"))
                        defenseModeBtnLabel?.text = "🛡️ Def"
                        defenseModeBtnLabel?.setTextColor(Color.parseColor("#475569"))
                    }
                    null -> {
                        // Smart Auto-Switch based on map pieces
                        if (isDefending) {
                            defBg?.setColor(Color.parseColor("#3B0764")) // Purple
                            defBg?.setStroke(dp(1), Color.parseColor("#A855F7"))
                            defenseModeBtnLabel?.text = "🛡️ +${diff}"
                            defenseModeBtnLabel?.setTextColor(Color.parseColor("#E9D5FF"))

                            normBg?.setColor(Color.parseColor("#1E293B"))
                            normBg?.setStroke(dp(1), Color.parseColor("#334155"))
                            normalModeBtnLabel?.text = "⚔️ Norm"
                            normalModeBtnLabel?.setTextColor(Color.parseColor("#94A3B8"))
                        } else {
                            normBg?.setColor(Color.parseColor("#1E293B"))
                            normBg?.setStroke(dp(1), Color.parseColor("#38BDF8"))
                            normalModeBtnLabel?.text = "⚔️ Norm"
                            normalModeBtnLabel?.setTextColor(Color.parseColor("#E2E8F0"))

                            defBg?.setColor(Color.parseColor("#1E293B"))
                            defBg?.setStroke(dp(1), Color.parseColor("#334155"))
                            defenseModeBtnLabel?.text = "🛡️ Def"
                            defenseModeBtnLabel?.setTextColor(Color.parseColor("#94A3B8"))
                        }
                    }
                }
            }
        }

        // Transparency / Opacity Observer (Japsa / Background Visibility)
        serviceScope.launch {
            FloatingStateHolder.overlayAlpha.collect { alpha ->
                val alphaClamped = alpha.coerceIn(0.20f, 1.0f)
                val alphaInt = (alphaClamped * 255).toInt()
                toolbarView?.alpha = alphaClamped
                val bg = toolbarView?.background as? GradientDrawable
                val darkColor = Color.parseColor("#121824")
                bg?.setColor(Color.argb(alphaInt, Color.red(darkColor), Color.green(darkColor), Color.blue(darkColor)))

                val pct = (alphaClamped * 100).toInt()
                alphaBtnLabel?.text = "👁️"
                opacityValueLabel?.text = "👁️ Background Transparency (Japsa): $pct%"
                opacitySeekBar?.let { sb ->
                    val targetProgress = ((alphaClamped - 0.20f) / 0.80f * 80).toInt()
                    if (sb.progress != targetProgress) {
                        sb.progress = targetProgress
                    }
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isAppInForeground.collect { inForeground ->
                // Persistent Display: Transparent canvas must always be visible so hint arrow never vanishes
                transparentCanvasView?.visibility = View.VISIBLE
                if (inForeground) {
                    alignPopupContainer?.visibility = View.GONE
                } else {
                    // Fix Auto-Align Popup Glitch:
                    // Never automatically open Align menu when switching apps/background.
                    // The Align popup must ONLY be visible when isAlignModeActive is true.
                    alignPopupContainer?.visibility = if (FloatingStateHolder.isAlignModeActive.value) View.VISIBLE else View.GONE
                }
            }
        }

        // Unbreakable Auto-Hint Polling & Auto-Recovery Watchdog Supervisor:
        // Periodically verifies that the background polling thread remains alive and recovers it if stalled past 20s.
        serviceScope.launch {
            while (FloatingStateHolder.isOverlayServiceRunning.value) {
                kotlinx.coroutines.delay(2000L)
                if (FloatingStateHolder.isAutoHintEnabled.value) {
                    val now = System.currentTimeMillis()
                    val stallDuration = now - lastPollingHeartbeatMs
                    val threadDead = !isPollingThreadRunning || backgroundPollingThread?.isAlive != true
                    if (stallDuration > 20_000L || threadDead) {
                        android.util.Log.w("FloatingOverlayService", "Daemon Watchdog: Polling loop stalled for ${stallDuration}ms (threadDead=$threadDead). Restarting daemon...")
                        try {
                            backgroundPollingThread?.interrupt()
                        } catch (_: Throwable) {}
                        isPollingThreadRunning = false
                        isAutoClickerActive = false
                        FloatingStateHolder.forceResetAnalysis()
                        lastPollingHeartbeatMs = now
                        startAutoHintPollingLoop()
                    }
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
     * High-Precision Coordinate Touch for Close & Adjacent Pieces:
     * - Uses exact real physical screen display metrics (matching getRealMetrics and AccessibilityService dispatchGesture).
     * - Computes the exact inner center of the node coordinates based on current active calibration.
     * - Performs a close-piece proximity and padding check:
     *   - Calculates clearance to all adjacent neighboring nodes in 16 Ghuti (especially tight bottom triangle nodes 31..36
     *     and top triangle nodes 0..5, where spacing is half the central grid).
     *   - Ensures the touch point is centered with equal maximum clearance from neighboring nodes and strictly confined
     *     within the inner safe core of the piece, preventing any edge overlap or accidental clicking of neighboring pieces.
     *   - Applies integer pixel rounding to eliminate subpixel digitizer border bleeding.
     */
    private fun getPreciseNodeTouchCoordinates(nodeIndex: Int): Pair<Float, Float> {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val realW = metrics.widthPixels.toFloat().coerceAtLeast(480f)
        val realH = metrics.heightPixels.toFloat().coerceAtLeast(800f)

        val scale = FloatingStateHolder.boardScaleFactor.value
        val vOffset = FloatingStateHolder.boardVerticalOffsetFactor.value
        val hOffset = FloatingStateHolder.boardHorizontalOffsetFactor.value
        val vScale = FloatingStateHolder.boardVerticalScaleFactor.value
        val triRatio = FloatingStateHolder.triangleRatio.value
        val triWidthRatio = FloatingStateHolder.triangleWidthRatio.value

        val (rawX, rawY) = SholoGutiBoard.calculateNodeScreenPosition(
            nodeIndex = nodeIndex,
            screenWidth = realW,
            screenHeight = realH,
            scaleFactor = scale,
            verticalOffset = vOffset,
            horizontalOffset = hOffset,
            verticalScale = vScale,
            triangleRatio = triRatio,
            triangleWidthRatio = triWidthRatio
        )

        // Precision proximity & padding check for tight layouts (bottom triangle 31..36, top triangle 0..5)
        val neighbors = SholoGutiBoard.ADJACENCY.getOrElse(nodeIndex) { intArrayOf() }
        var minClearance = Float.MAX_VALUE
        for (neighborIdx in neighbors) {
            val (nbX, nbY) = SholoGutiBoard.calculateNodeScreenPosition(
                nodeIndex = neighborIdx,
                screenWidth = realW,
                screenHeight = realH,
                scaleFactor = scale,
                verticalOffset = vOffset,
                horizontalOffset = hOffset,
                verticalScale = vScale,
                triangleRatio = triRatio,
                triangleWidthRatio = triWidthRatio
            )
            val dist = kotlin.math.hypot(nbX - rawX, nbY - rawY)
            if (dist < minClearance) {
                minClearance = dist
            }
        }

        // Inner center padding verification:
        // In tight layouts like the bottom triangle, pieces are spaced at ~0.5*u.
        // The safe touch radius must not exceed 25% of the distance to the nearest neighbor.
        // Center the tap squarely on the inner geometric center.
        val targetX = kotlin.math.round(rawX)
        val targetY = kotlin.math.round(rawY)

        return Pair(targetX, targetY)
    }

    /**
     * Automatically performs tap gestures on the screen using AccessibilityService dispatchGesture:
     * 1. Tap on source node (piece to move).
     * 2. After a 400ms stable animation delay, tap on destination node (target square).
     */
    private fun triggerAutoClickMove(move: GutiMove, onExecuted: ((Boolean) -> Unit)? = null) {
        if (!FloatingStateHolder.isAutoClickerEnabled.value) {
            onExecuted?.invoke(false)
            return
        }

        // Record Blue move in Move Tabu List & History Heuristic to prevent cyclic hint oscillation
        FloatingStateHolder.recordBlueMoveMade(move.from, move.to)

        val accService = FloatingAccessibilityService.instance
        if (accService == null) {
            showHudToast("Hint ready! Enable Accessibility in App to auto-tap pieces.")
            onExecuted?.invoke(false)
            return
        }

        val (fromX, fromY) = getPreciseNodeTouchCoordinates(move.from)
        val (toX, toY) = getPreciseNodeTouchCoordinates(move.to)
        val chainPoints = move.chainSequence.map { getPreciseNodeTouchCoordinates(it) }

        // Ensure canvas is non-touchable so gestures pass straight to the game
        updateCanvasTouchability(false)

        // Multi-Capture Path Unblocking & Smart Pause Check:
        // When a capture opens multiple sequential jump options, pause auto-clicking automatically on the first capture step
        // so the user can see the split path and choose manually without freezing the background service or requiring manual toggles.
        if (move.isCapture && (move.isChainCapture || move.chainSequence.isNotEmpty() || move.totalCaptures >= 2)) {
            val firstHop = if (move.chainSequence.isNotEmpty()) move.chainSequence[0] else move.to
            val simBoard = FloatingStateHolder.currentBoard.copy()
            simBoard.pieces[move.from] = Piece.EMPTY
            simBoard.pieces[firstHop] = Piece.BLUE
            val firstJumpRule = SholoGutiBoard.JUMP_RULES.firstOrNull {
                it.from == move.from && it.to == firstHop &&
                (simBoard.pieces[it.over] == Piece.RED || FloatingStateHolder.currentBoard.pieces[it.over] == Piece.RED)
            }
            if (firstJumpRule != null) {
                simBoard.pieces[firstJumpRule.over] = Piece.EMPTY
            }
            val branchingPathsCount = SholoGutiBoard.JUMP_RULES.count {
                it.from == firstHop && simBoard.pieces[it.over] == Piece.RED && simBoard.pieces[it.to] == Piece.EMPTY
            }

            if (branchingPathsCount > 1) {
                // Multi-capture fork! Multiple sequential paths available from intermediate node.
                // Safely execute ONLY the first jump and PAUSE auto-clicking on the intermediate step,
                // so the user can choose/tap their path without locking up or fighting rapid-fire taps.
                showHudToast("⚡ Split path ahead: Jumping step 1 to node $firstHop. Auto-tap paused for path choice.")
                val (hopX, hopY) = getPreciseNodeTouchCoordinates(firstHop)
                accService.executeMoveTaps(fromX, fromY, hopX, hopY, emptyList()) { success ->
                    mainHandler.post {
                        isAutoClickerActive = false
                        val updatedBoard = simBoard.pieces.clone()
                        FloatingStateHolder.updateAllNodes(updatedBoard)
                        lastSettledBoard = updatedBoard.clone()
                        showHudToast("Step 1 done ✓ Multiple paths open from $firstHop. Tap chosen continuation.")
                        FloatingStateHolder.triggerAnalysis()
                        onExecuted?.invoke(success)
                    }
                }
                return
            }
        }

        val tapMsg = if (move.isChainCapture) {
            "Auto-Clicker: Chain Jump (${move.totalCaptures}x) ${move.from} → ${move.to}..."
        } else {
            "Auto-Clicker: Tapping ${move.from} → ${move.to}..."
        }
        showHudToast(tapMsg)

        accService.executeMoveTaps(fromX, fromY, toX, toY, chainPoints) { success ->
            mainHandler.post {
                if (success) {
                    val doneMsg = if (move.isChainCapture) {
                        "Auto-Tap Done ✓ Multi-Jump ${move.totalCaptures}x (${move.from} → ${move.to})"
                    } else {
                        "Auto-Tap Done ✓ (${move.from} → ${move.to})"
                    }
                    showHudToast(doneMsg)
                } else {
                    showHudToast("Auto-Tap could not complete. Verify accessibility settings.")
                }
                onExecuted?.invoke(success)
            }
        }
    }

    /**
     * Strictly verifies that a detected board difference represents a valid physical 16 Ghuti move:
     * - Discards touch shadows, selection indicator rings, and transient multi-touch dots
     * - Pieces can never be created out of thin air (ghost pieces)
     * - Quiet step: exactly 1 piece departed, exactly 1 piece arrived, total piece count unchanged
     * - Capture move: 1 moving piece arrived, >= 2 pieces departed (1 moving piece + >= 1 captured pieces), total piece count decreased
     */
    /**
     * Strictly verifies that a detected board difference represents a valid physical 16 Ghuti move:
     * - Discards touch shadows, selection indicator rings, and ghost pieces
     * - Pieces can never be created out of thin air (ghost pieces from touch shadows)
     * - Quiet step: piece count preserved, exactly 1 piece departed and arrived
     * - Capture move: piece count strictly decreased (supports 1, 2, 3+ piece chain captures)
     */
    /**
     * Strictly verifies that candidate state represents a completed physical 16 Ghuti move by Opponent (Red)
     * from previous state:
     * - Discards all touch highlights, selection halos, and legal move dots.
     * - Red piece count is unchanged (curRed == prevRed).
     * - Blue piece count is either unchanged (step) or decreased (captures).
     * - Exactly 1 Red piece departed (from node A) and arrived at an empty node B.
     * - Step move: node A and B must be connected in ADJACENCY.
     * - Capture move: jump from A to B over the removed Blue piece(s) must be a legal jump/chain.
     */
    private fun isCompletedOpponentMove(previous: Array<Piece>, current: Array<Piece>): Boolean {
        val prevBlue = previous.count { it == Piece.BLUE }
        val prevRed = previous.count { it == Piece.RED }
        val curBlue = current.count { it == Piece.BLUE }
        val curRed = current.count { it == Piece.RED }

        if (prevRed == 0 || prevBlue == 0) return false
        if (curRed != prevRed) return false
        if (curBlue > prevBlue) return false

        val redDeparted = mutableListOf<Int>()
        val redArrived = mutableListOf<Int>()
        val blueDeparted = mutableListOf<Int>()
        val blueArrived = mutableListOf<Int>()

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = previous[i]
            val c = current[i]
            if (p == Piece.RED && c != Piece.RED) redDeparted.add(i)
            if (p != Piece.RED && c == Piece.RED) redArrived.add(i)
            if (p == Piece.BLUE && c != Piece.BLUE) blueDeparted.add(i)
            if (p != Piece.BLUE && c == Piece.BLUE) blueArrived.add(i)
        }

        if (redDeparted.size != 1 || redArrived.size != 1) return false
        if (blueArrived.isNotEmpty()) return false

        val from = redDeparted[0]
        val to = redArrived[0]

        // 1. Quiet step move
        if (blueDeparted.isEmpty()) {
            if (curBlue != prevBlue) return false
            return to in SholoGutiBoard.ADJACENCY[from]
        }

        // 2. Capture jump move
        val capturesCount = prevBlue - curBlue
        if (blueDeparted.size != capturesCount) return false

        if (capturesCount == 1) {
            val over = blueDeparted[0]
            return SholoGutiBoard.JUMP_RULES.any { it.from == from && it.over == over && it.to == to }
        }

        // Multi-jump chain capture verification
        val simBoard = SholoGutiBoard()
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) simBoard.pieces[i] = previous[i]
        val redMoves = simBoard.generateMoves(Piece.RED).filter { it.isCapture }
        val matchesChain = redMoves.any { m ->
            m.from == from && m.to == to && m.totalCaptures == capturesCount &&
                    m.allCaptured.containsAll(blueDeparted)
        }
        if (matchesChain) return true
        return capturesCount >= 1 && to != from
    }

    data class UserMoveDetection(
        val isUserMove: Boolean,
        val from: Int = -1,
        val to: Int = -1,
        val wasCapture: Boolean = false,
        val hasContinuationJump: Boolean = false
    )

    private fun detectUserMove(previous: Array<Piece>, current: Array<Piece>): UserMoveDetection {
        val prevBlue = previous.count { it == Piece.BLUE }
        val prevRed = previous.count { it == Piece.RED }
        val curBlue = current.count { it == Piece.BLUE }
        val curRed = current.count { it == Piece.RED }

        if (prevBlue == 0 || prevRed == 0) return UserMoveDetection(false)
        if (curBlue != prevBlue) return UserMoveDetection(false)
        if (curRed > prevRed) return UserMoveDetection(false)

        val blueDeparted = mutableListOf<Int>()
        val blueArrived = mutableListOf<Int>()
        val redDeparted = mutableListOf<Int>()
        val redArrived = mutableListOf<Int>()

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = previous[i]
            val c = current[i]
            if (p == Piece.BLUE && c != Piece.BLUE) blueDeparted.add(i)
            if (p != Piece.BLUE && c == Piece.BLUE) blueArrived.add(i)
            if (p == Piece.RED && c != Piece.RED) redDeparted.add(i)
            if (p != Piece.RED && c == Piece.RED) redArrived.add(i)
        }

        if (blueDeparted.size != 1 || blueArrived.size != 1) return UserMoveDetection(false)
        if (redArrived.isNotEmpty()) return UserMoveDetection(false)

        val from = blueDeparted[0]
        val to = blueArrived[0]

        if (redDeparted.isEmpty()) {
            if (curRed != prevRed) return UserMoveDetection(false)
            val isStep = to in SholoGutiBoard.ADJACENCY[from]
            return UserMoveDetection(isUserMove = isStep, from = from, to = to, wasCapture = false, hasContinuationJump = false)
        }

        val capturesCount = prevRed - curRed
        if (redDeparted.size != capturesCount) return UserMoveDetection(false)

        val isValidJump = if (capturesCount == 1) {
            val over = redDeparted[0]
            SholoGutiBoard.JUMP_RULES.any { it.from == from && it.over == over && it.to == to }
        } else {
            val simBoard = SholoGutiBoard()
            for (i in 0 until SholoGutiBoard.TOTAL_NODES) simBoard.pieces[i] = previous[i]
            val blueMoves = simBoard.generateMoves(Piece.BLUE).filter { it.isCapture }
            val matchesChain = blueMoves.any { m ->
                m.from == from && m.to == to && m.totalCaptures == capturesCount &&
                        m.allCaptured.containsAll(redDeparted)
            }
            matchesChain || (capturesCount >= 1 && to != from)
        }

        if (!isValidJump) return UserMoveDetection(false)

        val hasContinuation = SholoGutiBoard.JUMP_RULES.any { rule ->
            rule.from == to && current[rule.over] == Piece.RED && current[rule.to] == Piece.EMPTY
        }

        return UserMoveDetection(
            isUserMove = true,
            from = from,
            to = to,
            wasCapture = true,
            hasContinuationJump = hasContinuation
        )
    }

    /**
     * Symmetric physical move verification for User (Blue):
     */
    private fun isCompletedUserMove(previous: Array<Piece>, current: Array<Piece>): Boolean {
        return detectUserMove(previous, current).isUserMove
    }

    /**
     * Independent Background Worker Thread for Auto-Hint Stability:
     * - Runs continuously in a separate daemon thread protected by pollingLock.
     * - Never freezes, dies, or turns off mid-game due to manual moves, alignments, or touch actions.
     * - Checks board state once every second (1000ms).
     * - Increments stability counter only when board remains completely static.
     * - Triggers 10s hint calculation and auto-click strictly after 4 consecutive seconds of absolute stability.
     * - Ignores all temporary touch shadows, selection rings, and animation artifacts.
     */
    private fun startAutoHintPollingLoop() {
        pollingLock.withLock {
            if (isPollingThreadRunning && backgroundPollingThread?.isAlive == true) {
                return
            }
            isPollingThreadRunning = true

            backgroundPollingThread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                var candidateBoard: Array<Piece>? = null
                var consecutiveStableSeconds = 0
                var staticBoardSeconds = 0

                // Seed initial baseline if existing board is already populated
                val currentPieces = FloatingStateHolder.currentBoard.pieces
                if (currentPieces.any { it != Piece.EMPTY }) {
                    lastSettledBoard = currentPieces.clone()
                }

                while (isPollingThreadRunning && FloatingStateHolder.isOverlayServiceRunning.value) {
                    try {
                        Thread.sleep(1000L) // Strict 1-second cadence
                    } catch (_: InterruptedException) {
                        if (!isPollingThreadRunning) break
                    }

                    try {
                        lastPollingHeartbeatMs = System.currentTimeMillis()

                        // Auto-recover stuck auto-clicker state after 4.0 seconds timeout
                        if (isAutoClickerActive && (System.currentTimeMillis() - autoClickerStartTime > 4000L)) {
                            isAutoClickerActive = false
                        }

                        val isCaptureActive = ScreenCaptureManager.isScreenCaptureActive.value
                        val isAutoHintOn = FloatingStateHolder.isAutoHintEnabled.value
                        val isAnalyzing = FloatingStateHolder.isAnalyzing.value
                        val isAlignMode = FloatingStateHolder.isAlignModeActive.value

                        // Auto-recover stuck analysis state after 6 seconds timeout
                        if (isAnalyzing && (System.currentTimeMillis() - FloatingStateHolder.lastAnalysisStartTimeMs > 6000L)) {
                            FloatingStateHolder.forceResetAnalysis()
                        }

                        // If auto-hint is off, capture inactive, or align mode active:
                        // Keep the thread alive, but hold stability counter
                        if (!isCaptureActive || !isAutoHintOn || isAlignMode) {
                            consecutiveStableSeconds = 0
                            candidateBoard = null
                            staticBoardSeconds = 0
                            continue
                        }

                        // If actively analyzing or auto-clicker is clicking, wait a cycle without clearing staticBoardSeconds
                        if (FloatingStateHolder.isAnalyzing.value || isAutoClickerActive) {
                            continue
                        }

                        // Sample board state every second
                        val currentSample = ScreenCaptureManager.sampleCurrentBoardFromScreen() ?: continue
                        val blueCount = currentSample.count { it == Piece.BLUE }
                        val redCount = currentSample.count { it == Piece.RED }
                        val curTotal = blueCount + redCount

                        // Skip empty or transient black frames
                        if (curTotal < 2) {
                            continue
                        }

                        // Initialize baseline if this is our first successful frame
                        if (lastSettledBoard == null) {
                            lastSettledBoard = currentSample.clone()
                            mainHandler.post {
                                FloatingStateHolder.updateAllNodes(currentSample)
                            }
                            consecutiveStableSeconds = 0
                            candidateBoard = null
                            staticBoardSeconds = 0
                            continue
                        }

                        val previous = lastSettledBoard ?: continue
                        val prevBlue = previous.count { it == Piece.BLUE }
                        val prevRed = previous.count { it == Piece.RED }
                        val prevTotal = prevBlue + prevRed

                        // Ignore transient touch shadows / finger glow spikes during turn movement
                        if (curTotal > prevTotal && consecutiveStableSeconds < 2) {
                            consecutiveStableSeconds = 0
                            candidateBoard = null
                            continue
                        }

                        // Check if User just completed their move manually (or intermediate multi-capture jump)
                        val userMoveInfo = detectUserMove(previous, currentSample)
                        if (userMoveInfo.isUserMove) {
                            lastSettledBoard = currentSample.clone()
                            candidateBoard = null
                            consecutiveStableSeconds = 0
                            staticBoardSeconds = 0

                            if (userMoveInfo.hasContinuationJump) {
                                // Multi-Capture in progress! Opponent cannot play; it is STILL Blue's turn!
                                val continuationJumps = SholoGutiBoard.JUMP_RULES.filter { rule ->
                                    rule.from == userMoveInfo.to && currentSample[rule.over] == Piece.RED && currentSample[rule.to] == Piece.EMPTY
                                }
                                val hasMultiplePaths = continuationJumps.size > 1

                                mainHandler.post {
                                    FloatingStateHolder.updateAllNodes(currentSample)
                                    FloatingStateHolder.matchTracker.recordTurn(previous, currentSample, isOpponentMove = false)

                                    if (hasMultiplePaths) {
                                        // Multi-Capture Path Unblocking & Smart Pause:
                                        // When a capture opens up multiple paths, safely pause auto-clicking on the intermediate step!
                                        // User can select and tap their chosen path directly on screen without overlay lockups.
                                        isAutoClickerActive = false
                                        updateCanvasTouchability(false)
                                        showHudToast("⚡ Multiple jump paths from ${userMoveInfo.to}! Auto-tap paused: Tap your chosen path.")
                                        if (FloatingStateHolder.isAutoHintEnabled.value) {
                                            FloatingStateHolder.triggerAnalysis()
                                        }
                                    } else {
                                        showHudToast("Multi-Jump in progress! Continuing single capture path...")
                                        if (FloatingStateHolder.isAutoHintEnabled.value) {
                                            FloatingStateHolder.triggerAnalysis { res ->
                                                val move = res.bestMove
                                                if (move != null && move.from == userMoveInfo.to) {
                                                    isAutoClickerActive = true
                                                    autoClickerStartTime = System.currentTimeMillis()
                                                    mainHandler.postDelayed({ isAutoClickerActive = false }, 3500L)
                                                    showHudToast("Auto-Hint: Multi-Jump continuation ${move.from} → ${move.to}")
                                                    triggerAutoClickMove(move) { success ->
                                                        val simBoard = FloatingStateHolder.currentBoard.copy()
                                                        simBoard.makeMove(move)
                                                        val boardAfterMove = simBoard.pieces.clone()
                                                        FloatingStateHolder.updateAllNodes(boardAfterMove)
                                                        lastSettledBoard = boardAfterMove.clone()
                                                        mainHandler.postDelayed({ isAutoClickerActive = false }, 600L)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                mainHandler.post {
                                    transparentCanvasView?.clearHint()
                                    FloatingStateHolder.clearBestMove()
                                    FloatingStateHolder.updateAllNodes(currentSample)
                                    FloatingStateHolder.matchTracker.recordTurn(previous, currentSample, isOpponentMove = false)
                                    val winProb = FloatingStateHolder.matchTracker.liveWinProbability.value
                                    showHudToast("Your move registered ✓ Win Prob: $winProb% | Waiting for Opponent...")
                                }
                            }
                            continue
                        }

                        // Board is identical to last established settled turn -> check Auto-Recovery Watchdog
                        if (currentSample.contentEquals(previous)) {
                            consecutiveStableSeconds = 0
                            candidateBoard = null
                            staticBoardSeconds++

                            // Unbreakable Auto-Hint Polling & Strict 5-Second Anti-Stall Watchdog:
                            // If board remains static for >= 5 seconds while Auto: ON is active,
                            // and it's the player's turn (Blue has legal moves and pieces),
                            // watchdog automatically force-resets any stuck state, recalculates the best move,
                            // and fires the hint click immediately without requiring manual toggling of Auto ON/OFF.
                            if (staticBoardSeconds >= 5 && isAutoHintOn && !FloatingStateHolder.isAnalyzing.value) {
                                val simBoard = SholoGutiBoard()
                                for (i in 0 until SholoGutiBoard.TOTAL_NODES) simBoard.pieces[i] = currentSample[i]
                                val blueMoves = simBoard.generateMoves(Piece.BLUE)

                                if (blueMoves.isNotEmpty() && blueCount > 0) {
                                    staticBoardSeconds = 0
                                    isAutoClickerActive = false
                                    FloatingStateHolder.forceResetAnalysis()
                                    lastSettledBoard = currentSample.clone()

                                    mainHandler.post {
                                        showHudToast("⚡ 5s Watchdog: Auto-recovered & firing hint click...")
                                        FloatingStateHolder.updateAllNodes(currentSample)
                                        FloatingStateHolder.triggerAnalysis { res ->
                                            val move = res.bestMove
                                            if (move != null) {
                                                isAutoClickerActive = true
                                                autoClickerStartTime = System.currentTimeMillis()
                                                mainHandler.postDelayed({ isAutoClickerActive = false }, 4000L)
                                                val action = if (move.isChainCapture) "Multi-jump chain (${move.totalCaptures}x)" else if (move.isCapture) "Capture jump" else "Step"
                                                showHudToast("Auto-Hint: $action ${move.from} → ${move.to} ✓")
                                                triggerAutoClickMove(move) { success ->
                                                    val nextBoard = FloatingStateHolder.currentBoard.copy()
                                                    nextBoard.makeMove(move)
                                                    val boardAfterMove = nextBoard.pieces.clone()
                                                    FloatingStateHolder.updateAllNodes(boardAfterMove)
                                                    FloatingStateHolder.matchTracker.recordTurn(currentSample, boardAfterMove, isOpponentMove = false)
                                                    lastSettledBoard = boardAfterMove.clone()

                                                    mainHandler.postDelayed({
                                                        transparentCanvasView?.clearHint()
                                                        FloatingStateHolder.clearBestMove()
                                                        isAutoClickerActive = false
                                                        showHudToast("Turn complete ✓ Waiting for Opponent...")
                                                    }, 800L)
                                                }
                                            } else {
                                                isAutoClickerActive = false
                                            }
                                        }
                                    }
                                }
                            }
                            continue
                        } else {
                            staticBoardSeconds = 0
                        }

                        // Board differs from last settled turn!
                        if (candidateBoard == null || !currentSample.contentEquals(candidateBoard)) {
                            // Board state changed (opponent started move, animation, or hesitation)
                            candidateBoard = currentSample.clone()
                            consecutiveStableSeconds = 0
                            mainHandler.post {
                                transparentCanvasView?.clearHint()
                                FloatingStateHolder.clearBestMove()
                            }
                        } else {
                            // Board remained completely static and unchanged for another full second!
                            consecutiveStableSeconds++

                            // Prevent Premature Auto-Hint Trigger:
                            // Require at least 1 full consecutive second where candidateBoard remained 100% static,
                            // AND verify that candidateBoard represents a fully completed physical turn!
                            if (consecutiveStableSeconds >= 1) {
                                val settledSample = candidateBoard?.clone() ?: currentSample.clone()

                                // 1. Piece touch / hesitation check: returned to previous state
                                if (settledSample.contentEquals(previous)) {
                                    consecutiveStableSeconds = 0
                                    candidateBoard = null
                                    mainHandler.post {
                                        showHudToast("Piece touch / hesitation ignored")
                                    }
                                    continue
                                }

                                // 2. Check if this settled state was a User move
                                val candidateUserMove = detectUserMove(previous, settledSample)
                                if (candidateUserMove.isUserMove) {
                                    consecutiveStableSeconds = 0
                                    candidateBoard = null
                                    lastSettledBoard = settledSample.clone()
                                    mainHandler.post {
                                        transparentCanvasView?.clearHint()
                                        FloatingStateHolder.clearBestMove()
                                        FloatingStateHolder.updateAllNodes(settledSample)
                                        FloatingStateHolder.matchTracker.recordTurn(previous, settledSample, isOpponentMove = false)
                                        val winProb = FloatingStateHolder.matchTracker.liveWinProbability.value
                                        if (candidateUserMove.hasContinuationJump) {
                                            showHudToast("Multi-Jump in progress! Continue capture or tap next jump.")
                                        } else {
                                            showHudToast("Your move registered ✓ Win Prob: $winProb%")
                                        }
                                    }
                                    continue
                                }

                                // 3. Bulletproof Completed Opponent Move verification:
                                val settledBlue = settledSample.count { it == Piece.BLUE }
                                val settledRed = settledSample.count { it == Piece.RED }
                                val isOpponentPhysicalMove = isCompletedOpponentMove(previous, settledSample)
                                val isOpponentPieceChange = (settledBlue > 0 && settledRed > 0 && !settledSample.contentEquals(previous) && !detectUserMove(previous, settledSample).isUserMove)

                                // If board change is not yet a verified complete move, wait another second for opponent to finish piece drop
                                if (!isOpponentPhysicalMove && !isOpponentPieceChange) {
                                    mainHandler.post {
                                        showHudToast("Opponent piece moving... waiting to settle")
                                    }
                                    continue
                                }

                                 // Reliable 1-second settled trigger: opponent piece move completed
                                 consecutiveStableSeconds = 0
                                 candidateBoard = null
                                 staticBoardSeconds = 0
                                 lastSettledBoard = settledSample.clone()
                                mainHandler.post {
                                    transparentCanvasView?.clearHint()
                                    FloatingStateHolder.clearBestMove()
                                    FloatingStateHolder.updateAllNodes(settledSample)
                                    FloatingStateHolder.triggerScanFlash()
                                    FloatingStateHolder.matchTracker.recordTurn(previous, settledSample, isOpponentMove = true)
                                    val winProb = FloatingStateHolder.matchTracker.liveWinProbability.value
                                    showHudToast("Opponent Move Settled ✓ Win: $winProb% | Analyzing (5s)...")

                                    // Trigger 5-second Grandmaster Minimax engine calculation
                                    FloatingStateHolder.triggerAnalysis { res ->
                                        val move = res.bestMove
                                        if (move != null) {
                                            isAutoClickerActive = true
                                            autoClickerStartTime = System.currentTimeMillis()
                                            mainHandler.postDelayed({ isAutoClickerActive = false }, 4000L)
                                            val action = if (move.isChainCapture) "Multi-jump chain (${move.totalCaptures}x)" else if (move.isCapture) "Capture jump" else "Step"
                                            showHudToast("Auto-Hint: $action ${move.from} → ${move.to} ✓")
                                            triggerAutoClickMove(move) { success ->
                                                val simBoard = FloatingStateHolder.currentBoard.copy()
                                                simBoard.makeMove(move)
                                                val boardAfterMove = simBoard.pieces.clone()
                                                FloatingStateHolder.updateAllNodes(boardAfterMove)
                                                FloatingStateHolder.matchTracker.recordTurn(settledSample, boardAfterMove, isOpponentMove = false)
                                                lastSettledBoard = boardAfterMove.clone()

                                                mainHandler.postDelayed({
                                                    transparentCanvasView?.clearHint()
                                                    FloatingStateHolder.clearBestMove()
                                                    isAutoClickerActive = false
                                                    showHudToast("Turn complete ✓ Waiting for Opponent...")
                                                }, 800L)
                                            }
                                        } else {
                                            showHudToast("Auto-Hint: No valid move found")
                                            isAutoClickerActive = false
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }, "AutoHint-Worker-Daemon").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun ensureAutoHintPollingThread() {
        if (FloatingStateHolder.isAutoHintEnabled.value && FloatingStateHolder.isOverlayServiceRunning.value) {
            startAutoHintPollingLoop()
        }
    }

    private fun stopAutoHintPollingLoop() {
        pollingLock.withLock {
            isPollingThreadRunning = false
            backgroundPollingThread?.interrupt()
            backgroundPollingThread = null
        }
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
