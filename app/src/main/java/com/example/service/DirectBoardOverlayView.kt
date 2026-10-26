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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class DirectBoardOverlayView(context: Context) : View(context) {

    private var boardPieces: Array<Piece> = Array(SholoGutiBoard.TOTAL_NODES) { Piece.EMPTY }
    private var bestMove: GutiMove? = null
    private var isSyncMode = false
    private var isAlignMode = false
    private var showNodeIndicators = false
    private var scanFlash = false

    private var verticalOffset = 0.50f
    private var horizontalOffset = 0.50f
    private var scaleFactor = 0.88f
    private var verticalScale = 1.00f
    private var triangleRatio = 0.50f
    private var triangleWidthRatio = 1.00f
    private var nodeRadiusFactor = 1.00f

    // Interactive Drag and Pinch Scale Tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isAlignMode) {
                val scaleChange = (detector.scaleFactor - 1.0f) * 0.75f
                val newScale = (FloatingStateHolder.boardScaleFactor.value + scaleChange).coerceIn(0.40f, 1.40f)
                FloatingStateHolder.setBoardScale(newScale)
                return true
            }
            return false
        }
    })

    // Glowing Neon Move Vector Paints
    private val arrowGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Neon Green
        strokeWidth = 20f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 140
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 10f
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
        strokeWidth = 6f
    }

    private val toHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00B0FF") // Cyan target
        style = Paint.Style.FILL
        alpha = 150
    }

    private val toHaloRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val captureXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Alignment Mode Guide Paints
    private val alignLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        alpha = 180
    }

    private val alignDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        alpha = 140
    }

    private val alignNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val backboneNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15") // Gold
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val alignTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val scanFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.FILL
        alpha = 140
    }

    // Interactive Sync Mode Paints (Persistent On-Screen Map)
    private val syncLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 56, 189, 248) // Translucent cyan connecting grid lines
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val nodeEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255) // Hollow white ring for empty nodes
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val nodeBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Crisp Cyan indicator ring
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val nodeRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Crisp Ruby Red indicator ring
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private var pulseRadiusOffset = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 16f).apply {
        duration = 900L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseRadiusOffset = it.animatedValue as Float
            if (bestMove != null || isAlignMode || scanFlash || isSyncMode) {
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
        triRatio: Float,
        triWidthRatio: Float,
        radiusFactor: Float = 1.0f,
        flash: Boolean
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
        this.triangleWidthRatio = triWidthRatio
        this.nodeRadiusFactor = radiusFactor
        this.scanFlash = flash
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
            triangleRatio = triangleRatio,
            triangleWidthRatio = triangleWidthRatio
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isAlignMode) return false

        // Handle multi-touch pinch to scale
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            isDragging = false
            return true
        }

        // Handle single-touch drag to reposition
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1 && width > 0 && height > 0) {
                    val dx = (event.rawX - lastTouchX) / width.toFloat()
                    val dy = (event.rawY - lastTouchY) / height.toFloat()
                    FloatingStateHolder.nudgeOffset(dx, dy)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    fun clearHint() {
        this.bestMove = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            // Clear entire transparent canvas buffer to prevent dirty-rect trails or double arrows
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

            // 1. If in ALIGN MODE: Draw complete authentic 16 Ghuti board wireframe, node targets & drag handles
            if (isAlignMode) {
                drawAlignmentGuide(canvas)
            }

            // 2. If SCAN FLASH is active: Draw brief green pulse rings on all nodes
            if (scanFlash) {
                drawScanFlash(canvas)
            }

            // 3. If in SYNC MODE: Only draw beads if explicitly requested by user (default is 100% transparent)
            if (isSyncMode) {
                drawSyncBeads(canvas)
            }

            // 4. BEST MOVE HINT OVERLAY: Glowing vector arrow & target indicators
            val move = bestMove
            if (move != null && move.from in 0 until SholoGutiBoard.TOTAL_NODES && move.to in 0 until SholoGutiBoard.TOTAL_NODES) {
                drawBestMoveHint(canvas, move)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun drawAlignmentGuide(canvas: Canvas) {
        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val u = w * (scaleFactor / 4.0f)
        val gutiRadius = (u * 0.24f * nodeRadiusFactor).coerceIn(8f, 65f)

        // Draw exact authentic 16 Ghuti connecting grid lines
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

        // Draw 5x5 grid boundary bracket corners
        val (tlX, tlY) = getNodeCoordinates(6)
        val (trX, trY) = getNodeCoordinates(10)
        val (blX, blY) = getNodeCoordinates(26)
        val (brX, brY) = getNodeCoordinates(30)
        canvas.drawLine(tlX, tlY, trX, trY, alignDashedPaint)
        canvas.drawLine(trX, trY, brX, brY, alignDashedPaint)
        canvas.drawLine(brX, brY, blX, blY, alignDashedPaint)
        canvas.drawLine(blX, blY, tlX, tlY, alignDashedPaint)

        // Draw node circles & numbers on all 37 points
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = getNodeCoordinates(i)
            val isBackbone = SholoGutiBoard.NODE_POINTS[i].isBackbone
            val isCenter = (i == 18)
            val paint = if (isCenter) backboneNodePaint else if (isBackbone) backboneNodePaint else alignNodePaint

            canvas.drawCircle(cx, cy, gutiRadius, paint)
            // Crosshairs for center alignment
            canvas.drawLine(cx - gutiRadius * 1.3f, cy, cx + gutiRadius * 1.3f, cy, paint)
            canvas.drawLine(cx, cy - gutiRadius * 1.3f, cx, cy + gutiRadius * 1.3f, paint)

            canvas.drawText("$i", cx, cy + 9f, alignTextPaint)
        }
    }

    private fun drawScanFlash(canvas: Canvas) {
        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val u = w * (scaleFactor / 4.0f)
        val flashRadius = (u * 0.28f * nodeRadiusFactor) + pulseRadiusOffset * 0.4f

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = getNodeCoordinates(i)
            canvas.drawCircle(cx, cy, flashRadius, scanFlashPaint)
        }
    }

    private fun drawSyncBeads(canvas: Canvas) {
        if (!showNodeIndicators) return
        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val u = w * (scaleFactor / 4.0f)
        val nodeRadius = (u * 0.23f * nodeRadiusFactor).coerceIn(8f, 65f)

        // Draw non-occluding outer accent rings only (keeps the real game pieces 100% visible)
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = getNodeCoordinates(i)
            val piece = boardPieces[i]
            when (piece) {
                Piece.BLUE -> {
                    // User Piece: Outer accent ring around bead perimeter
                    canvas.drawCircle(cx, cy, nodeRadius * 1.05f, nodeBluePaint)
                }
                Piece.RED -> {
                    // Opponent Piece: Outer accent ring around bead perimeter
                    canvas.drawCircle(cx, cy, nodeRadius * 1.05f, nodeRedPaint)
                }
                Piece.EMPTY -> {
                    // Empty node: 100% transparent to prevent background contamination
                }
            }
        }
    }

    private fun drawBestMoveHint(canvas: Canvas, move: GutiMove) {
        val (fromX, fromY) = getNodeCoordinates(move.from)
        val (toX, toY) = getNodeCoordinates(move.to)
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 12f) return

        val unitX = dx / dist
        val unitY = dy / dist

        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val u = w * (scaleFactor / 4.0f)
        val baseRadius = (u * 0.25f * nodeRadiusFactor).coerceIn(10f, 70f)

        // 1. Pulsating Source Node Halo (Source marker)
        canvas.drawCircle(fromX, fromY, baseRadius + pulseRadiusOffset, fromHaloPaint)
        canvas.drawCircle(fromX, fromY, baseRadius * 0.85f, toHaloRingPaint)

        // 2. Target Destination Node (Cyan target ring)
        canvas.drawCircle(toX, toY, baseRadius, toHaloPaint)
        canvas.drawCircle(toX, toY, baseRadius + 4f, toHaloRingPaint)

        // 3. Arrow Geometry: Clean single shaft stopping at the arrowhead base
        val arrowHeadLength = (baseRadius * 1.15f).coerceAtMost(dist * 0.45f)
        val startX = fromX + unitX * (baseRadius * 0.90f)
        val startY = fromY + unitY * (baseRadius * 0.90f)
        val tipX = toX - unitX * (baseRadius * 0.25f)
        val tipY = toY - unitY * (baseRadius * 0.25f)
        val shaftEndX = tipX - unitX * (arrowHeadLength * 0.70f)
        val shaftEndY = tipY - unitY * (arrowHeadLength * 0.70f)

        // Draw SINGLE shaft (one glowing underlay, one crisp primary line)
        canvas.drawLine(startX, startY, shaftEndX, shaftEndY, arrowGlowPaint)
        canvas.drawLine(startX, startY, shaftEndX, shaftEndY, arrowPaint)

        // 4. Single Arrowhead at Destination
        val angle = atan2(dy.toDouble(), dx.toDouble())
        val arrowAngle = Math.PI / 6.2 // ~29 degrees

        val headPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(
                (tipX - arrowHeadLength * cos(angle - arrowAngle)).toFloat(),
                (tipY - arrowHeadLength * sin(angle - arrowAngle)).toFloat()
            )
            lineTo(
                (tipX - (arrowHeadLength * 0.65f) * cos(angle)).toFloat(),
                (tipY - (arrowHeadLength * 0.65f) * sin(angle)).toFloat()
            )
            lineTo(
                (tipX - arrowHeadLength * cos(angle + arrowAngle)).toFloat(),
                (tipY - arrowHeadLength * sin(angle + arrowAngle)).toFloat()
            )
            close()
        }
        canvas.drawPath(headPath, arrowHeadPaint)

        // 5. Chain Jump Waypoints
        if (move.chainSequence.isNotEmpty()) {
            for (stepNode in move.chainSequence) {
                if (stepNode in 0 until SholoGutiBoard.TOTAL_NODES) {
                    val (sx, sy) = getNodeCoordinates(stepNode)
                    canvas.drawCircle(sx, sy, baseRadius * 0.85f, toHaloRingPaint)
                }
            }
        }

        // 6. If Jump Capture: Draw prominent Red 'X' on all captured pieces (including chain captures)
        val targetsToCross = if (move.allCaptured.isNotEmpty()) move.allCaptured else if (move.isCapture) listOf(move.captured) else emptyList()
        val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B71C1C")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        for (capNode in targetsToCross) {
            if (capNode in 0 until SholoGutiBoard.TOTAL_NODES) {
                val (capX, capY) = getNodeCoordinates(capNode)
                val crossSize = baseRadius * 0.90f

                canvas.drawCircle(capX, capY, crossSize * 1.30f, warningPaint)
                // Red X
                canvas.drawLine(capX - crossSize, capY - crossSize, capX + crossSize, capY + crossSize, captureXPaint)
                canvas.drawLine(capX + crossSize, capY - crossSize, capX - crossSize, capY + crossSize, captureXPaint)
            }
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
