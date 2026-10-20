package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FloatingAccessibilityService : AccessibilityService() {

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

    companion object {
        private const val TAG = "FloatingAccessibility"
        var instance: FloatingAccessibilityService? = null
            private set
    }
}
