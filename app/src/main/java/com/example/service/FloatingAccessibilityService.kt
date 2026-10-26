package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FloatingAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FloatingAccessibilityService connected successfully")
        FloatingStateHolder.updateAccessibilityStatus(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive monitoring
    }

    override fun onInterrupt() {
        Log.w(TAG, "FloatingAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        FloatingStateHolder.updateAccessibilityStatus(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        FloatingStateHolder.updateAccessibilityStatus(false)
        super.onDestroy()
    }

    /**
     * Performs a single tap gesture at screen coordinates (x, y) using dispatchGesture.
     * Duration is 50ms for a natural touch event.
     */
    fun performTap(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires Android N (API 24) or higher")
            onComplete?.invoke(false)
            return false
        }

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap gesture completed at ($x, $y)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
                onComplete?.invoke(false)
            }
        }, null)

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture returned false for ($x, $y)")
            onComplete?.invoke(false)
        }
        return dispatched
    }

    /**
     * Automatically executes the move sequence (including multi-step chain captures):
     * 1. Tap on the source node (piece to move).
     * 2. Sequentially tap intermediate chain nodes if multi-jump with 400ms delay.
     * 3. Tap on the final destination node (target square).
     */
    fun executeMoveTaps(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        chainPoints: List<Pair<Float, Float>> = emptyList(),
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        val tapSequence = mutableListOf<Pair<Float, Float>>()
        tapSequence.add(Pair(fromX, fromY))
        tapSequence.addAll(chainPoints)
        tapSequence.add(Pair(toX, toY))

        fun dispatchStep(index: Int) {
            if (index >= tapSequence.size) {
                onFinished?.invoke(true)
                return
            }
            val (px, py) = tapSequence[index]
            val dispatched = performTap(px, py) { ok ->
                if (ok && index + 1 < tapSequence.size) {
                    mainHandler.postDelayed({
                        dispatchStep(index + 1)
                    }, 400L)
                } else {
                    onFinished?.invoke(ok)
                }
            }
            if (!dispatched) {
                onFinished?.invoke(false)
            }
        }

        dispatchStep(0)
    }

    companion object {
        private const val TAG = "FloatingAccessibility"
        var instance: FloatingAccessibilityService? = null
            private set
    }
}

