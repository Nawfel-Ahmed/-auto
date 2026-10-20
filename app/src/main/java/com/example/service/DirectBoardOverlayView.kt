package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class DirectBoardOverlayView(context: Context) : View(context) {

    private var boardPieces: Array<Piece> = Array(SholoGutiBoard.TOTAL_NODES) { Piece.EMPTY }
    private var bestMove: GutiMove? = null
    private var isSyncMode = false
    private var isAlignMode = false
    private var showNodeIndicators = false

    private var verticalOffset = 0.50f
    private var horizontalOffset = 0.50f
    private var scaleFactor = 0.92f
    private var verticalScale = 1.00f
    private var triangleRatio = 1.00f

    // Interactive Drag Tracking for Align Mode
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingBoard = false

    // Paints for glowing hint move vector
    private val arrowGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Vibrant Neon Green
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 130
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.FILL
    }

    private val fromHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val toHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00B0FF") // Electric Cyan
        style = Paint.Style.FILL
        alpha = 160
    }

    private val toHaloRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val captureXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Red
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Alignment and Sync Paints
    private val alignLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        alpha = 150
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val alignNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00") // Yellow crosshairs
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val alignTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val nodeEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val nodeBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF") // Blue bead
        style = Paint.Style.FILL
    }

    private val nodeRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Red bead
        style = Paint.Style.FILL
    }

    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var pulseRadiusOffset = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 16f).apply {
        duration = 1000L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseRadiusOffset = it.animatedValue as Float
            if (bestMove != null || isAlignMode) {
                invalidate()
            }
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        pulseAnimator.start()
    }

    fun updateData(
        pieces: Array<Piece>,
        move: GutiMove?,
        isSync: Boolean,
        isAlign: Boolean,
        showNodes: Boolean,
        vOffset: Float,
        hOffset: Float,
        scale: Float,
        vScale: Float,
        triRatio: Float
    ) {
        this.boardPieces = pieces
        this.bestMove = move
        this.isSyncMode = isSync
        this.isAlignMode = isAlign
        this.showNodeIndicators = showNodes
        this.verticalOffset = vOffset
        this.horizontalOffset = hOffset
        this.scaleFactor = scale
        this.verticalScale = vScale
        this.triangleRatio = triRatio
        invalidate()
    }

    fun getNodeCoordinates(index: Int): Pair<Float, Float> {
        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        return SholoGutiBoard.calculateNodeScreenPosition(
            nodeIndex = index,
            screenWidth = w,
            screenHeight = h,
            scaleFactor = scaleFactor,
            verticalOffset = verticalOffset,
            horizontalOffset = horizontalOffset,
            verticalScale = verticalScale,
            triangleRatio = triangleRatio
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isAlignMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDraggingBoard = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingBoard && width > 0 && height > 0) {
                    val dx = (event.rawX - lastTouchX) / width.toFloat()
                    val dy = (event.rawY - lastTouchY) / height.toFloat()
                    FloatingStateHolder.nudgeOffset(dx, dy)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingBoard = false
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. If in ALIGN MODE: Draw complete board wireframe, node markers, and drag hint
        if (isAlignMode) {
            drawAlignmentGuide(canvas)
        }

        // 2. If in SYNC MODE: Draw beads on nodes so user can tap & inspect board state
        if (isSyncMode) {
            drawSyncBeads(canvas)
        }

        // 3. BEST MOVE HINT OVERLAY: Glowing vector arrow & target indicators directly over the real board
        val move = bestMove
        if (move != null && move.from in 0 until SholoGutiBoard.TOTAL_NODES && move.to in 0 until SholoGutiBoard.TOTAL_NODES) {
            drawBestMoveHint(canvas, move)
        }
    }

    private fun drawAlignmentGuide(canvas: Canvas) {
        val w = width.toFloat()
        val baseRadius = (w * scaleFactor * 0.038f).coerceIn(16f, 36f)

        // Draw connecting board grid lines
        val drawnConnections = HashSet<String>()
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (x1, y1) = getNodeCoordinates(i)
            for (neighbor in SholoGutiBoard.ADJACENCY[i]) {
                val key = if (i < neighbor) "$i-$neighbor" else "$neighbor-$i"
                if (drawnConnections.add(key)) {
                    val (x2, y2) = getNodeCoordinates(neighbor)
                    canvas.drawLine(x1, y1, x2, y2, alignLinePaint)
                }
            }
        }

        // Draw crosshair targets & node numbers on all 37 points
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = getNodeCoordinates(i)
            val isBackbone = SholoGutiBoard.NODE_POINTS[i].isBackbone

            alignNodePaint.color = if (isBackbone) Color.parseColor("#FFD600") else Color.parseColor("#00E676")
            canvas.drawCircle(cx, cy, baseRadius, alignNodePaint)
            canvas.drawLine(cx - baseRadius * 1.3f, cy, cx + baseRadius * 1.3f, cy, alignNodePaint)
            canvas.drawLine(cx, cy - baseRadius * 1.3f, cx, cy + baseRadius * 1.3f, alignNodePaint)

            canvas.drawText("$i", cx, cy + 9f, alignTextPaint)
        }
    }

    private fun drawSyncBeads(canvas: Canvas) {
        val w = width.toFloat()
        val nodeRadius = (w * scaleFactor * 0.040f).coerceIn(16f, 40f)

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = getNodeCoordinates(i)
            val piece = boardPieces[i]
            when (piece) {
                Piece.BLUE -> {
                    canvas.drawCircle(cx, cy, nodeRadius, nodeBluePaint)
                    canvas.drawCircle(cx, cy, nodeRadius, nodeBorderPaint)
                }
                Piece.RED -> {
                    canvas.drawCircle(cx, cy, nodeRadius, nodeRedPaint)
                    canvas.drawCircle(cx, cy, nodeRadius, nodeBorderPaint)
                }
                Piece.EMPTY -> {
                    canvas.drawCircle(cx, cy, nodeRadius * 0.45f, nodeEmptyPaint)
                }
            }
        }
    }

    private fun drawBestMoveHint(canvas: Canvas, move: GutiMove) {
        val (fromX, fromY) = getNodeCoordinates(move.from)
        val (toX, toY) = getNodeCoordinates(move.to)
        val baseRadius = (width.toFloat() * scaleFactor * 0.042f).coerceIn(18f, 42f)

        // Pulsating Source Node Halo (Where to pick the guti from)
        canvas.drawCircle(fromX, fromY, baseRadius + pulseRadiusOffset, fromHaloPaint)
        canvas.drawCircle(fromX, fromY, baseRadius * 0.8f, toHaloRingPaint)

        // Target Destination Node (Where to move the guti to)
        canvas.drawCircle(toX, toY, baseRadius, toHaloPaint)
        canvas.drawCircle(toX, toY, baseRadius + 4f, toHaloRingPaint)

        // Glowing Move Arrow
        canvas.drawLine(fromX, fromY, toX, toY, arrowGlowPaint)
        canvas.drawLine(fromX, fromY, toX, toY, arrowPaint)

        // Arrow Head at Destination
        val angle = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val arrowHeadLength = baseRadius * 1.1f
        val arrowAngle = Math.PI / 6.0 // 30 degrees

        val headPath = Path().apply {
            moveTo(toX, toY)
            lineTo(
                (toX - arrowHeadLength * Math.cos(angle - arrowAngle)).toFloat(),
                (toY - arrowHeadLength * Math.sin(angle - arrowAngle)).toFloat()
            )
            lineTo(
                (toX - (arrowHeadLength * 0.65f) * Math.cos(angle)).toFloat(),
                (toY - (arrowHeadLength * 0.65f) * Math.sin(angle)).toFloat()
            )
            lineTo(
                (toX - arrowHeadLength * Math.cos(angle + arrowAngle)).toFloat(),
                (toY - arrowHeadLength * Math.sin(angle + arrowAngle)).toFloat()
            )
            close()
        }
        canvas.drawPath(headPath, arrowHeadPaint)

        // If Jump Capture: Draw prominent Red 'X' on the captured piece
        if (move.isCapture && move.captured in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (capX, capY) = getNodeCoordinates(move.captured)
            val crossSize = baseRadius * 0.85f

            // Red warning circle
            val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B71C1C")
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawCircle(capX, capY, crossSize * 1.25f, warningPaint)

            // Red X
            canvas.drawLine(capX - crossSize, capY - crossSize, capX + crossSize, capY + crossSize, captureXPaint)
            canvas.drawLine(capX + crossSize, capY - crossSize, capX - crossSize, capY + crossSize, captureXPaint)
        }
    }

    fun findTouchedNode(touchX: Float, touchY: Float): Int? {
        val hitRadius = (width.toFloat() * scaleFactor * 0.075f).coerceIn(28f, 65f)
        var closestIndex: Int? = null
        var minDistance = Float.MAX_VALUE

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (nx, ny) = getNodeCoordinates(i)
            val d = hypot((touchX - nx).toDouble(), (touchY - ny).toDouble()).toFloat()
            if (d <= hitRadius && d < minDistance) {
                minDistance = d
                closestIndex = i
            }
        }
        return closestIndex
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }
}
