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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

object ScreenCaptureManager {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isScreenCaptureActive = MutableStateFlow(false)
    val isScreenCaptureActive: StateFlow<Boolean> = _isScreenCaptureActive.asStateFlow()

    private val _lastScanResult = MutableStateFlow<ScanSummary?>(null)
    val lastScanResult: StateFlow<ScanSummary?> = _lastScanResult.asStateFlow()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    fun initProjection(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mediaProjection = mpManager?.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        setupVirtualDisplay()
        _isScreenCaptureActive.value = (mediaProjection != null)
    }

    private fun setupVirtualDisplay() {
        imageReader?.close()
        virtualDisplay?.release()

        val mp = mediaProjection ?: return

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mp.createVirtualDisplay(
            "SholoGutiScreenReader",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    fun scanBoardFromScreen(onResult: (ScanSummary) -> Unit) {
        val reader = imageReader
        if (reader == null || mediaProjection == null) {
            val failure = ScanSummary(
                blueCount = 0,
                redCount = 0,
                emptyCount = 37,
                success = false,
                message = "Screen capture not active. Please start screen projection first."
            )
            _lastScanResult.value = failure
            onResult(failure)
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                // Try acquireNextImage as fallback
                image = reader.acquireNextImage()
            }

            if (image == null) {
                val failure = ScanSummary(
                    blueCount = 0,
                    redCount = 0,
                    emptyCount = 37,
                    success = false,
                    message = "Could not capture frame. Ensure 16 Ghuti game is visible."
                )
                _lastScanResult.value = failure
                onResult(failure)
                return
            }

            processImage(image, onResult)
        } catch (e: Exception) {
            val failure = ScanSummary(
                blueCount = 0,
                redCount = 0,
                emptyCount = 37,
                success = false,
                message = "Scan error: ${e.localizedMessage ?: "Unknown"}"
            )
            _lastScanResult.value = failure
            onResult(failure)
        } finally {
            image?.close()
        }
    }

    private fun processImage(image: Image, onResult: (ScanSummary) -> Unit) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val scale = FloatingStateHolder.boardScaleFactor.value
        val vOffset = FloatingStateHolder.boardVerticalOffsetFactor.value
        val hOffset = FloatingStateHolder.boardHorizontalOffsetFactor.value
        val vScale = FloatingStateHolder.boardVerticalScaleFactor.value
        val triRatio = FloatingStateHolder.triangleRatio.value

        val sampleRadius = (screenWidth * scale * 0.035f).toInt().coerceIn(6, 24)

        var blueCount = 0
        var redCount = 0
        var emptyCount = 0

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (nodeX, nodeY) = SholoGutiBoard.calculateNodeScreenPosition(
                nodeIndex = i,
                screenWidth = screenWidth.toFloat(),
                screenHeight = screenHeight.toFloat(),
                scaleFactor = scale,
                verticalOffset = vOffset,
                horizontalOffset = hOffset,
                verticalScale = vScale,
                triangleRatio = triRatio
            )

            val cx = nodeX.toInt().coerceIn(sampleRadius, bitmap.width - sampleRadius - 1)
            val cy = nodeY.toInt().coerceIn(sampleRadius, bitmap.height - sampleRadius - 1)

            // Sample pixels in radius
            var totalRed = 0L
            var totalGreen = 0L
            var totalBlue = 0L
            var pixelCount = 0

            for (dx in -sampleRadius..sampleRadius step 2) {
                for (dy in -sampleRadius..sampleRadius step 2) {
                    if (dx * dx + dy * dy <= sampleRadius * sampleRadius) {
                        val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                        val py = (cy + dy).coerceIn(0, bitmap.height - 1)
                        val pixel = bitmap.getPixel(px, py)
                        totalRed += Color.red(pixel)
                        totalGreen += Color.green(pixel)
                        totalBlue += Color.blue(pixel)
                        pixelCount++
                    }
                }
            }

            val avgR = if (pixelCount > 0) (totalRed / pixelCount).toInt() else 0
            val avgG = if (pixelCount > 0) (totalGreen / pixelCount).toInt() else 0
            val avgB = if (pixelCount > 0) (totalBlue / pixelCount).toInt() else 0

            val detectedPiece = when {
                // Blue piece (User): High blue prominence
                avgB > avgR * 1.30f && avgB > avgG * 1.20f && avgB > 75 -> Piece.BLUE
                // Red piece (Opponent): High red prominence
                avgR > avgB * 1.35f && avgR > avgG * 1.20f && avgR > 75 -> Piece.RED
                // Empty node / board background
                else -> Piece.EMPTY
            }

            FloatingStateHolder.setNodePiece(i, detectedPiece)

            when (detectedPiece) {
                Piece.BLUE -> blueCount++
                Piece.RED -> redCount++
                Piece.EMPTY -> emptyCount++
            }
        }

        bitmap.recycle()

        val summary = ScanSummary(
            blueCount = blueCount,
            redCount = redCount,
            emptyCount = emptyCount,
            success = true,
            message = "Scan aligned: $blueCount Blue (You), $redCount Red (Opponent), $emptyCount Empty"
        )
        _lastScanResult.value = summary
        onResult(summary)
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        _isScreenCaptureActive.value = false
    }
}
