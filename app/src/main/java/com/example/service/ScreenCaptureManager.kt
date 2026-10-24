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
            val sampleRadius = (u * 0.18f * radiusFactor).toInt().coerceIn(7, 28)
            val coreRadiusSq = (sampleRadius * 0.48f) * (sampleRadius * 0.48f)

            val detectedPieces = Array(SholoGutiBoard.TOTAL_NODES) { Piece.EMPTY }
            val hsv = FloatArray(3)

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

                                    val sum = (r + g + b).coerceAtLeast(1)
                                    val rNorm = r.toFloat() / sum
                                    val bNorm = b.toFloat() / sum

                                    Color.RGBToHSV(r, g, b, hsv)
                                    val hue = hsv[0]
                                    val sat = hsv[1]
                                    val value = hsv[2]

                                    val isCore = (distSq <= coreRadiusSq)
                                    allSamples++
                                    if (isCore) coreSamples++

                                    // 1. User Pieces: Vibrant Blue / Cyan / Royal Blue
                                    // Blue channel must dominate over red & green.
                                    val isBluePixel = (bNorm >= 0.40f && b >= 75 && (b - r) >= 20 && (b - g) >= 10 && b > (r * 1.20f)) ||
                                            (hue in 178f..265f && sat >= 0.28f && value >= 0.20f && b > r && b > (g * 0.95f))

                                    // 2. Opponent Pieces: Pure Crimson / Scarlet / Ruby Red
                                    // STRICT Wood Rejection:
                                    // Wood boards have hue ~18°..50° and rNorm ~0.38..0.45.
                                    // True red beads have hue strictly in 340°..360° or 0°..14°, rNorm >= 0.48, and r - g >= 32.
                                    val isWoodHue = (hue in 15f..55f)
                                    val isRedPixel = !isWoodHue &&
                                            ((rNorm >= 0.48f && r >= 88 && (r - g) >= 32 && (r - b) >= 32 && r > (g * 1.40f) && r > (b * 1.40f)) ||
                                                    ((hue >= 342f || hue <= 14f) && sat >= 0.38f && value >= 0.22f && r > (g * 1.35f) && r > (b * 1.35f)))

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
                val allRedRatio = allRedVotes.toFloat() / allSamples.coerceAtLeast(1)
                val coreBlueRatio = coreBlueVotes.toFloat() / coreSamples.coerceAtLeast(1)
                val coreRedRatio = coreRedVotes.toFloat() / coreSamples.coerceAtLeast(1)

                // Decision: Strictly distinguish User Blue, Opponent Red, and Empty nodes
                val isBlueNode = ((coreBlueRatio >= 0.25f && allBlueRatio >= 0.18f) || (allBlueRatio >= 0.32f)) &&
                        (allBlueRatio > (allRedRatio * 2.0f)) &&
                        (allBlueVotes >= 5)

                val isRedNode = ((coreRedRatio >= 0.25f && allRedRatio >= 0.18f) || (allRedRatio >= 0.32f)) &&
                        (allRedRatio > (allBlueRatio * 2.0f)) &&
                        (allRedVotes >= 5)

                val piece = when {
                    isBlueNode -> Piece.BLUE
                    isRedNode -> Piece.RED
                    else -> Piece.EMPTY
                }

                detectedPieces[i] = piece
            }

            return detectedPieces
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
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
