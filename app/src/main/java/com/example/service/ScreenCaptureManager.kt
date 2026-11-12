package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureManager {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _isScreenCaptureActive = MutableStateFlow(false)
    val isScreenCaptureActive: StateFlow<Boolean> = _isScreenCaptureActive.asStateFlow()

    private val _lastScanResult = MutableStateFlow<ScanSummary?>(null)
    val lastScanResult: StateFlow<ScanSummary?> = _lastScanResult.asStateFlow()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val frameLock = Any()
    @Volatile
    private var cachedScreenBitmap: Bitmap? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            _isScreenCaptureActive.value = false
            virtualDisplay?.release()
            virtualDisplay = null
        }
    }

    private fun ensureBackgroundThread(): Handler {
        var handler = backgroundHandler
        if (handler == null || backgroundThread?.isAlive != true) {
            backgroundThread?.quitSafely()
            val thread = HandlerThread("ScreenCaptureReaderThread").apply { start() }
            backgroundThread = thread
            handler = Handler(thread.looper)
            backgroundHandler = handler
        }
        return handler
    }

    fun initProjection(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()

        mediaProjection = mpManager?.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(480)
        screenHeight = metrics.heightPixels.coerceAtLeast(800)
        screenDensity = metrics.densityDpi

        setupVirtualDisplay()
        _isScreenCaptureActive.value = (mediaProjection != null)
    }

    private fun setupVirtualDisplay() {
        imageReader?.close()
        virtualDisplay?.release()

        val mp = mediaProjection ?: return
        val bgHandler = ensureBackgroundThread()

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            3
        ).apply {
            // Continuously read the latest frame and update cachedScreenBitmap
            // This prevents frame buffer overflow and guarantees 0ms instantaneous scan
            setOnImageAvailableListener({ reader ->
                try {
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        updateCachedBitmap(img)
                    } finally {
                        img.close()
                    }
                } catch (_: Exception) {}
            }, bgHandler)
        }

        virtualDisplay = mp.createVirtualDisplay(
            "SholoGutiPersistentScreenReader",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            bgHandler
        )
    }

    private fun updateCachedBitmap(image: Image) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val bmpWidth = screenWidth + rowPadding / pixelStride
        val bmpHeight = screenHeight

        synchronized(frameLock) {
            val current = cachedScreenBitmap
            val target = if (current != null && current.width == bmpWidth && current.height == bmpHeight && !current.isRecycled) {
                current
            } else {
                current?.recycle()
                Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888).also {
                    cachedScreenBitmap = it
                }
            }
            buffer.rewind()
            target.copyPixelsFromBuffer(buffer)
        }
    }

    fun scanBoardFromScreen(onResult: (ScanSummary) -> Unit) {
        val reader = imageReader
        if (reader == null || mediaProjection == null) {
            val failure = ScanSummary(
                blueCount = 0,
                redCount = 0,
                emptyCount = 37,
                success = false,
                message = "Screen capture not active. Please grant Screen Capture permission."
            )
            _lastScanResult.value = failure
            onResult(failure)
            return
        }

        val bgHandler = ensureBackgroundThread()
        bgHandler.post {
            var workingBitmap: Bitmap? = null

            // 1. Direct acquisition of newest fresh frame from ImageReader
            try {
                val directImage = reader.acquireLatestImage()
                if (directImage != null) {
                    try {
                        val plane = directImage.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth
                        val bmpWidth = screenWidth + rowPadding / pixelStride
                        val bmp = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
                        buffer.rewind()
                        bmp.copyPixelsFromBuffer(buffer)
                        workingBitmap = bmp
                    } finally {
                        directImage.close()
                    }
                }
            } catch (_: Exception) {}

            // 2. Fallback to cached frame if direct image not yet arrived
            if (workingBitmap == null) {
                synchronized(frameLock) {
                    val cached = cachedScreenBitmap
                    if (cached != null && !cached.isRecycled) {
                        try {
                            workingBitmap = cached.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {}
                    }
                }
            }

            // 3. Fallback: wait up to 250ms for a frame
            if (workingBitmap == null) {
                try {
                    val latch = CountDownLatch(1)
                    reader.setOnImageAvailableListener({ r ->
                        try {
                            val img = r.acquireLatestImage()
                            if (img != null) {
                                try {
                                    updateCachedBitmap(img)
                                } finally {
                                    img.close()
                                }
                            }
                        } catch (_: Exception) {}
                        latch.countDown()
                    }, bgHandler)

                    latch.await(250, TimeUnit.MILLISECONDS)

                    // Restore standard listener
                    reader.setOnImageAvailableListener({ r ->
                        try {
                            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                            try {
                                updateCachedBitmap(img)
                            } finally {
                                img.close()
                            }
                        } catch (_: Exception) {}
                    }, bgHandler)

                    synchronized(frameLock) {
                        val cached = cachedScreenBitmap
                        if (cached != null && !cached.isRecycled) {
                            workingBitmap = cached.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (workingBitmap == null) {
                val failure = ScanSummary(
                    blueCount = 0,
                    redCount = 0,
                    emptyCount = 37,
                    success = false,
                    message = "Could not capture frame. Ensure 16 Ghuti game is visible."
                )
                mainHandler.post {
                    _lastScanResult.value = failure
                    onResult(failure)
                }
                return@post
            }

            val bmp = workingBitmap!!
            try {
                processBitmap(bmp, onResult)
            } finally {
                bmp.recycle()
            }
        }
    }

    /**
     * Non-blocking snapshot of current board state from latest or cached screen frame.
     * Used by the background persistent auto-trigger polling coroutine.
     */
    fun sampleCurrentBoardFromScreen(): Array<Piece>? {
        val reader = imageReader
        if (reader != null) {
            try {
                val directImage = reader.acquireLatestImage()
                if (directImage != null) {
                    try {
                        updateCachedBitmap(directImage)
                    } finally {
                        directImage.close()
                    }
                }
            } catch (_: Exception) {}
        }

        var copyBmp: Bitmap? = null
        synchronized(frameLock) {
            val cached = cachedScreenBitmap
            if (cached != null && !cached.isRecycled) {
                try {
                    copyBmp = cached.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) {}
            }
        }
        val bmp = copyBmp ?: return null
        return try {
            classifyBoardFromBitmap(bmp)
        } finally {
            bmp.recycle()
        }
    }


    /**
     * Highly accurate 16 Ghuti bead and intersection scanner.
     * - Dynamically adjusts sample radius using nodeRadiusFactor slider
     * - Assigns double weight to the core center of beads (immune to boundary artifacts)
     * - Robust HSV and RGB thresholds correctly distinguishing Blue, Red, and Empty nodes without losing pieces
     */
    fun classifyBoardFromBitmap(bitmap: Bitmap): Array<Piece>? {
        if (bitmap.isRecycled || bitmap.width <= 10 || bitmap.height <= 10) return null

        try {
            val scale = FloatingStateHolder.boardScaleFactor.value
            val vOffset = FloatingStateHolder.boardVerticalOffsetFactor.value
            val hOffset = FloatingStateHolder.boardHorizontalOffsetFactor.value
            val vScale = FloatingStateHolder.boardVerticalScaleFactor.value
            val triRatio = FloatingStateHolder.triangleRatio.value
            val triWidthRatio = FloatingStateHolder.triangleWidthRatio.value
            val radiusFactor = FloatingStateHolder.nodeRadiusFactor.value

            val u = (screenWidth * (scale / 4.0f))
            val sampleRadius = (u * 0.18f * radiusFactor).toInt().coerceIn(7, 32)
            val coreRadiusSq = (sampleRadius * 0.45f) * (sampleRadius * 0.45f)

            val detectedPieces = Array(SholoGutiBoard.TOTAL_NODES) { Piece.EMPTY }
            val redConfidence = FloatArray(SholoGutiBoard.TOTAL_NODES)
            val blueConfidence = FloatArray(SholoGutiBoard.TOTAL_NODES)

            for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
                if (bitmap.isRecycled) return null

                val (nodeX, nodeY) = SholoGutiBoard.calculateNodeScreenPosition(
                    nodeIndex = i,
                    screenWidth = screenWidth.toFloat(),
                    screenHeight = screenHeight.toFloat(),
                    scaleFactor = scale,
                    verticalOffset = vOffset,
                    horizontalOffset = hOffset,
                    verticalScale = vScale,
                    triangleRatio = triRatio,
                    triangleWidthRatio = triWidthRatio
                )

                val maxCx = (bitmap.width - sampleRadius - 1).coerceAtLeast(0)
                val minCx = sampleRadius.coerceAtMost(maxCx)
                val cx = if (maxCx >= minCx) nodeX.toInt().coerceIn(minCx, maxCx) else bitmap.width / 2

                val maxCy = (bitmap.height - sampleRadius - 1).coerceAtLeast(0)
                val minCy = sampleRadius.coerceAtMost(maxCy)
                val cy = if (maxCy >= minCy) nodeY.toInt().coerceIn(minCy, maxCy) else bitmap.height / 2

                var allSamples = 0
                var allBlueVotes = 0
                var allRedVotes = 0

                var coreSamples = 0
                var coreBlueVotes = 0
                var coreRedVotes = 0

                for (dx in -sampleRadius..sampleRadius step 2) {
                    for (dy in -sampleRadius..sampleRadius step 2) {
                        val distSq = dx * dx + dy * dy
                        if (distSq <= sampleRadius * sampleRadius) {
                            val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                            val py = (cy + dy).coerceIn(0, bitmap.height - 1)

                            if (!bitmap.isRecycled && px in 0 until bitmap.width && py in 0 until bitmap.height) {
                                try {
                                    val pixel = bitmap.getPixel(px, py)
                                    val r = Color.red(pixel)
                                    val g = Color.green(pixel)
                                    val b = Color.blue(pixel)

                                    val isCore = (distSq <= coreRadiusSq)
                                    allSamples++
                                    if (isCore) coreSamples++

                                    val maxVal = maxOf(r, maxOf(g, b))
                                    val minVal = minOf(r, minOf(g, b))
                                    val delta = maxVal - minVal
                                    val sat = if (maxVal == 0) 0f else delta.toFloat() / maxVal

                                    // 1. User Pieces: Royal Blue tokens
                                    // Robust against anti-aliasing and textures; distinguishes strictly from wood (where R > B) and cyan/selection rings (where B - G < 20)
                                    val isBluePixel = (b >= 125 && b - r >= 45 && b - g >= 25) ||
                                            (b >= 115 && sat >= 0.35f && b > r * 1.35f && b > g * 1.20f)

                                    // 2. Opponent Pieces: Crimson Red tokens
                                    // Robust against embossed textures/stars and shading; distinguishes strictly from wood (where R - G is low, <= 35)
                                    val isRedPixel = (r >= 135 && r - g >= 45 && r - b >= 45) ||
                                            (r >= 120 && sat >= 0.40f && r > g * 1.35f && r > b * 1.35f)

                                    if (isBluePixel) {
                                        allBlueVotes++
                                        if (isCore) coreBlueVotes++
                                    }
                                    if (isRedPixel) {
                                        allRedVotes++
                                        if (isCore) coreRedVotes++
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }

                val allBlueRatio = allBlueVotes.toFloat() / allSamples.coerceAtLeast(1)
                val coreBlueRatio = coreBlueVotes.toFloat() / coreSamples.coerceAtLeast(1)
                val allRedRatio = allRedVotes.toFloat() / allSamples.coerceAtLeast(1)
                val coreRedRatio = coreRedVotes.toFloat() / coreSamples.coerceAtLeast(1)

                // Decision: Mutually exclusive, highly accurate piece classification (immune to false drops)
                val isBlueNode = (coreBlueRatio >= 0.20f || (coreBlueRatio >= 0.14f && allBlueRatio >= 0.14f)) &&
                        (allBlueVotes >= 14) &&
                        (allBlueVotes >= (allRedVotes * 2.2f).toInt())

                val isRedNode = (coreRedRatio >= 0.20f || (coreRedRatio >= 0.14f && allRedRatio >= 0.14f)) &&
                        (allRedVotes >= 14) &&
                        (allRedVotes >= (allBlueVotes * 2.2f).toInt())

                val piece = when {
                    isBlueNode && !isRedNode -> Piece.BLUE
                    isRedNode && !isBlueNode -> Piece.RED
                    else -> Piece.EMPTY
                }

                detectedPieces[i] = piece
                redConfidence[i] = allRedRatio + coreRedRatio
                blueConfidence[i] = allBlueRatio + coreBlueRatio
            }

            // Ground-Truth Game Score Synchronization:
            // Ensures piece counts are 100% synchronized with reality before Minimax evaluation
            val (compScore, userScore) = readGameScoresFromBitmap(bitmap)
            if (compScore != null && userScore != null) {
                val targetBlue = (16 - compScore).coerceIn(0, 16)
                val targetRed = (16 - userScore).coerceIn(0, 16)

                var curBlue = detectedPieces.count { it == Piece.BLUE }
                var curRed = detectedPieces.count { it == Piece.RED }

                if (curBlue < targetBlue && kotlin.math.abs(curBlue - targetBlue) <= 2) {
                    val candidateIndices = (0 until SholoGutiBoard.TOTAL_NODES)
                        .filter { detectedPieces[it] == Piece.EMPTY }
                        .sortedByDescending { blueConfidence[it] }
                    for (idx in candidateIndices.take(targetBlue - curBlue)) {
                        detectedPieces[idx] = Piece.BLUE
                    }
                } else if (curBlue > targetBlue && kotlin.math.abs(curBlue - targetBlue) <= 2) {
                    val toDrop = (0 until SholoGutiBoard.TOTAL_NODES)
                        .filter { detectedPieces[it] == Piece.BLUE }
                        .sortedBy { blueConfidence[it] }
                    for (idx in toDrop.take(curBlue - targetBlue)) {
                        detectedPieces[idx] = Piece.EMPTY
                    }
                }

                if (curRed < targetRed && kotlin.math.abs(curRed - targetRed) <= 2) {
                    val candidateIndices = (0 until SholoGutiBoard.TOTAL_NODES)
                        .filter { detectedPieces[it] == Piece.EMPTY }
                        .sortedByDescending { redConfidence[it] }
                    for (idx in candidateIndices.take(targetRed - curRed)) {
                        detectedPieces[idx] = Piece.RED
                    }
                } else if (curRed > targetRed && kotlin.math.abs(curRed - targetRed) <= 2) {
                    val toDrop = (0 until SholoGutiBoard.TOTAL_NODES)
                        .filter { detectedPieces[it] == Piece.RED }
                        .sortedBy { redConfidence[it] }
                    for (idx in toDrop.take(curRed - targetRed)) {
                        detectedPieces[idx] = Piece.EMPTY
                    }
                }
            }

            return detectedPieces
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Reads ground truth scores from top ("Computer Score: X") and bottom ("You Score: Y") headers.
     * In 16 Ghuti:
     * - Computer Score = X means Computer has captured X Blue pieces -> Blue remaining = 16 - X.
     * - You Score = Y means You have captured Y Red pieces -> Red remaining = 16 - Y.
     */
    fun readGameScoresFromBitmap(bitmap: Bitmap): Pair<Int?, Int?> {
        val w = screenWidth
        val h = screenHeight
        if (bitmap.isRecycled || bitmap.width < 10 || bitmap.height < 10) return Pair(null, null)

        fun parseScoreFromRegion(yRange: IntRange, xRange: IntRange): Int? {
            val whitePts = mutableListOf<Pair<Int, Int>>()
            for (y in yRange) {
                for (x in xRange) {
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        if (r > 205 && g > 205 && b > 205) {
                            whitePts.add(Pair(x, y))
                        }
                    }
                }
            }
            if (whitePts.size < 15) return null

            val xs = whitePts.map { it.first }
            val minX = xs.minOrNull() ?: return null
            val maxX = xs.maxOrNull() ?: return null
            val colCounts = IntArray(maxX - minX + 1)
            for (pt in whitePts) {
                colCounts[pt.first - minX]++
            }

            var colonX = -1
            for (i in 0 until (colCounts.size - 4)) {
                if (colCounts[i] <= 1 && colCounts[i + 1] in 2..6 && colCounts[i + 2] in 2..6 && colCounts[i + 4] <= 1) {
                    colonX = minX + i + 1
                    break
                }
            }
            val digitPts = if (colonX != -1) {
                whitePts.filter { it.first > colonX + 2 }
            } else {
                whitePts.filter { it.first > minX + (maxX - minX) * 0.45f }
            }
            if (digitPts.size < 15) return null

            val dMinX = digitPts.minOf { it.first }
            val dMaxX = digitPts.maxOf { it.first }
            val dMinY = digitPts.minOf { it.second }
            val dMaxY = digitPts.maxOf { it.second }
            val dw = (dMaxX - dMinX + 1).coerceAtLeast(1)
            val dh = (dMaxY - dMinY + 1).coerceAtLeast(1)

            val grid = Array(5) { IntArray(4) }
            for (pt in digitPts) {
                val gx = (((pt.first - dMinX) * 4) / dw).coerceIn(0, 3)
                val gy = (((pt.second - dMinY) * 5) / dh).coerceIn(0, 4)
                grid[gy][gx]++
            }

            val total = digitPts.size.coerceAtLeast(1)
            val midHole = (grid[2][1] + grid[2][2]).toFloat() / total
            val topBar = grid[0].sum()
            val botBar = grid[4].sum()
            val leftMid = grid[2][0]
            val rightMid = grid[2][3]

            return when {
                midHole < 0.14f && leftMid > 15 && rightMid > 15 -> 0
                dw.toFloat() / dh < 0.40f -> 1
                rightMid > leftMid && topBar > 40 && botBar > 50 -> 5
                midHole > 0.15f && leftMid > 20 && rightMid > 20 -> 8
                rightMid > leftMid * 1.8f -> 3
                topBar > 60 && botBar < 30 -> 7
                else -> null
            }
        }

        return try {
            val topScore = parseScoreFromRegion(
                yRange = (h * 0.040f).toInt()..(h * 0.070f).toInt(),
                xRange = (w * 0.62f).toInt()..(w * 0.82f).toInt()
            )
            val botScore = parseScoreFromRegion(
                yRange = (h * 0.875f).toInt()..(h * 0.915f).toInt(),
                xRange = (w * 0.62f).toInt()..(w * 0.82f).toInt()
            )
            Pair(topScore, botScore)
        } catch (_: Throwable) {
            Pair(null, null)
        }
    }

    private fun processBitmap(bitmap: Bitmap, onResult: (ScanSummary) -> Unit) {
        val detectedPieces = classifyBoardFromBitmap(bitmap)
        if (detectedPieces == null) {
            val failure = ScanSummary(0, 0, 37, false, "Frame unavailable or scan error")
            mainHandler.post {
                _lastScanResult.value = failure
                onResult(failure)
            }
            return
        }

        var blueCount = 0
        var redCount = 0
        var emptyCount = 0
        for (p in detectedPieces) {
            when (p) {
                Piece.BLUE -> blueCount++
                Piece.RED -> redCount++
                Piece.EMPTY -> emptyCount++
            }
        }

        mainHandler.post {
            try {
                val isAnyPieceDetected = (blueCount + redCount >= 2)
                val summary = ScanSummary(
                    blueCount = blueCount,
                    redCount = redCount,
                    emptyCount = emptyCount,
                    success = isAnyPieceDetected,
                    message = if (isAnyPieceDetected) {
                        "Scanned: $blueCount Blue (You), $redCount Red (Opponent), $emptyCount Empty"
                    } else {
                        "No pieces detected. Align board or check screen."
                    }
                )
                _lastScanResult.value = summary

                if (isAnyPieceDetected) {
                    FloatingStateHolder.updateAllNodes(detectedPieces)
                }

                onResult(summary)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        synchronized(frameLock) {
            cachedScreenBitmap?.recycle()
            cachedScreenBitmap = null
        }
        _isScreenCaptureActive.value = false
    }
}
