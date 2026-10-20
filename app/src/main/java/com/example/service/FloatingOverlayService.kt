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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.aistudio.floatingbubble.kxpzqa.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

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
    private var syncBtnLayout: LinearLayout? = null
    private var syncBtnIcon: ImageView? = null
    private var alignBtnLayout: LinearLayout? = null
    private var hudToastView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        setup100PercentTransparentCanvasOverlay()
        setupMinimalistFloatingControlBar()
        setupAlignFloatingPopup()

        observeState()
        FloatingStateHolder.updateServiceRunningStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
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

    /**
     * Creates the 100% Transparent Fullscreen Overlay.
     * Stays completely transparent during game play, rendering only the glowing move vector when hint is requested.
     */
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

    /**
     * Minimalist Floating Toolbar with Hint, Scan, Align, and Reset.
     */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupMinimalistFloatingControlBar() {
        val wm = windowManager ?: return

        val container = FrameLayout(this)
        controlBarContainer = container

        // Modern Glassmorphism Pill Container
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121824")) // 90% opaque dark navy
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
                if (FloatingStateHolder.isAnalyzing.value) return@setOnClickListener

                // Deactivate align mode so hint arrow is clean on the real board
                if (FloatingStateHolder.isAlignModeActive.value) {
                    FloatingStateHolder.toggleAlignMode(false)
                }

                showHudToast("Thinking best 16 Ghuti move...")
                FloatingStateHolder.triggerAnalysis { result ->
                    val move = result.bestMove
                    if (move != null) {
                        showHudToast("Hint: Move ${move.from} → ${move.to}")
                    } else {
                        showHudToast("No move found. Try Scan first!")
                    }
                }
            }
        }
        toolbar.addView(hintBtn)

        // 2. "Scan" Button (Live screen sampling)
        val scanBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            syncBtnLayout = this

            val scanBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = scanBg

            val icon = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(Color.parseColor("#38BDF8"))
                syncBtnIcon = this
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
                showHudToast("Scanning real game screen...")
                ScreenCaptureManager.scanBoardFromScreen { summary ->
                    if (summary.success) {
                        showHudToast("Scanned: ${summary.blueCount} Blue, ${summary.redCount} Red ✓")
                    } else {
                        showHudToast(summary.message)
                    }
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

        // 3. "Align" Button (Interactive alignment tool toggle)
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

            val label = TextView(this@FloatingOverlayService).apply {
                text = "Align"
                setTextColor(Color.parseColor("#FACC15"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(label)

            setOnClickListener {
                FloatingStateHolder.toggleAlignMode()
                val isNowActive = FloatingStateHolder.isAlignModeActive.value
                if (isNowActive) {
                    showHudToast("Align Mode ON: Drag screen or use controls to fit board")
                } else {
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

        // 4. "Reset" / Clear Move Button
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
                FloatingStateHolder.clearBestMove()
                showHudToast("Overlay cleared")
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

        // Window Layout Params for Floating Control Bar
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
     * Floating Calibration Tool Panel:
     * Nudge D-Pad (Up, Down, Left, Right), Size (+/-), Wings (+/-), Reset & Lock.
     */
    @SuppressLint("SetTextI18n")
    private fun setupAlignFloatingPopup() {
        val wm = windowManager ?: return

        alignPopupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(12))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F172A")) // Slate 900
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#FACC15")) // Gold border in align mode
            }
            background = bg
            elevation = dp(12).toFloat()
        }

        // Header Title
        val header = TextView(this).apply {
            text = "🎯 Board Alignment & Calibration"
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        alignPopupContainer?.addView(header)

        // Subtitle instructions
        val sub = TextView(this).apply {
            text = "Drag screen directly or use arrows to fit 37 nodes"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        alignPopupContainer?.addView(sub)

        // 1. D-Pad Directional Controls for fine pixel nudging
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun createNudgeBtn(text: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(4), dp(10), dp(4))
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#475569"))
                }
                background = btnBg
                setOnClickListener { onClick() }
            }
        }

        // Up button
        val upRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(createNudgeBtn("▲ Up") {
                FloatingStateHolder.nudgeOffset(0f, -0.01f)
            })
        }
        dpad.addView(upRow)

        // Left, Center, Right row
        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))

            addView(createNudgeBtn("◀ Left") {
                FloatingStateHolder.nudgeOffset(-0.01f, 0f)
            })

            val resetNudge = createNudgeBtn("Reset") {
                FloatingStateHolder.resetCalibrationToDefault()
                showHudToast("Reset to default layout")
            }
            val resetParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            addView(resetNudge, resetParams)

            addView(createNudgeBtn("Right ▶") {
                FloatingStateHolder.nudgeOffset(0.01f, 0f)
            })
        }
        dpad.addView(midRow)

        // Down button
        val downRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(createNudgeBtn("▼ Down") {
                FloatingStateHolder.nudgeOffset(0f, 0.01f)
            })
        }
        dpad.addView(downRow)

        alignPopupContainer?.addView(dpad)

        // 2. Size & Wings Controls Row
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(8))

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

                        val minusBtn = createNudgeBtn(" - ") { onMinus() }
                        addView(minusBtn)

                        val plusBtn = createNudgeBtn(" + ") { onPlus() }
                        val pParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(6) }
                        addView(plusBtn, pParams)
                    }
                    addView(btnRow)
                }
            }

            val sizeGroup = createControlGroup("Board Size",
                onMinus = {
                    val current = FloatingStateHolder.boardScaleFactor.value
                    FloatingStateHolder.setBoardScale(current - 0.02f)
                },
                onPlus = {
                    val current = FloatingStateHolder.boardScaleFactor.value
                    FloatingStateHolder.setBoardScale(current + 0.02f)
                }
            )
            addView(sizeGroup)

            val wingGroup = createControlGroup("Triangle Wings",
                onMinus = {
                    val current = FloatingStateHolder.triangleRatio.value
                    FloatingStateHolder.setTriangleRatio(current - 0.05f)
                },
                onPlus = {
                    val current = FloatingStateHolder.triangleRatio.value
                    FloatingStateHolder.setTriangleRatio(current + 0.05f)
                }
            )
            val wingParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) }
            addView(wingGroup, wingParams)
        }
        alignPopupContainer?.addView(controlsRow)

        // 3. "Done & Lock" Button
        val doneBtn = TextView(this).apply {
            text = "✓ Lock & Save Alignment"
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
                FloatingStateHolder.toggleAlignMode(false)
                showHudToast("Alignment saved! Background is 100% transparent ✓")
            }
        }
        val doneParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        }
        alignPopupContainer?.addView(doneBtn, doneParams)

        // Layout parameters for alignment popup
        alignPopupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }

        try {
            wm.addView(alignPopupContainer, alignPopupParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeState() {
        // Observe best move and countdown
        serviceScope.launch {
            FloatingStateHolder.bestMove.collect {
                refreshCanvasData()
            }
        }

        serviceScope.launch {
            FloatingStateHolder.isAnalyzing.collect { analyzing ->
                if (analyzing) {
                    hintBtnLabel?.text = "Thinking..."
                } else {
                    hintBtnLabel?.text = "10s Hint"
                }
            }
        }

        serviceScope.launch {
            FloatingStateHolder.analysisCountdownSec.collect { sec ->
                if (sec > 0) {
                    hintBtnLabel?.text = "Hint (${sec}s)"
                }
            }
        }

        // Observe calibration changes
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

        // Observe align mode active state
        serviceScope.launch {
            FloatingStateHolder.isAlignModeActive.collect { isAlign ->
                alignPopupContainer?.visibility = if (isAlign) View.VISIBLE else View.GONE
                updateCanvasTouchability(isAlign || FloatingStateHolder.isSyncModeActive.value)
                refreshCanvasData()

                // Update Align button visuals in toolbar
                val bg = alignBtnLayout?.background as? GradientDrawable
                if (isAlign) {
                    bg?.setColor(Color.parseColor("#422006")) // Amber dark
                    bg?.setStroke(dp(1), Color.parseColor("#FACC15"))
                } else {
                    bg?.setColor(Color.parseColor("#1E293B"))
                    bg?.setStroke(dp(1), Color.parseColor("#334155"))
                }
            }
        }

        // Observe sync mode
        serviceScope.launch {
            FloatingStateHolder.isSyncModeActive.collect { isSync ->
                updateCanvasTouchability(isSync || FloatingStateHolder.isAlignModeActive.value)
                refreshCanvasData()
            }
        }

        // Observe App Foreground State: Hide floating canvas when user is inside the MainActivity settings!
        serviceScope.launch {
            FloatingStateHolder.isAppInForeground.collect { inForeground ->
                val canvas = transparentCanvasView
                if (inForeground) {
                    // Hide overlay so 37-node board doesn't float over settings page
                    canvas?.visibility = View.GONE
                    alignPopupContainer?.visibility = View.GONE
                } else {
                    // Show transparent canvas over the real 16 Ghuti game
                    canvas?.visibility = View.VISIBLE
                    if (FloatingStateHolder.isAlignModeActive.value) {
                        alignPopupContainer?.visibility = View.VISIBLE
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
            triRatio = FloatingStateHolder.triangleRatio.value
        )
    }

    private fun showHudToast(message: String) {
        hudToastView?.let { view ->
            view.text = message
            view.visibility = View.VISIBLE
            view.removeCallbacks(hideHudToastRunnable)
            view.postDelayed(hideHudToastRunnable, 2600L)
        }
    }

    private val hideHudToastRunnable = Runnable {
        hudToastView?.visibility = View.GONE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
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
    }
}
