package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.engine.EngineResult
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.service.FloatingOverlayService
import com.example.service.FloatingStateHolder
import com.example.service.ScanSummary
import com.example.service.ScreenCaptureManager
import com.example.util.PermissionHelper
import kotlinx.coroutines.flow.StateFlow

class FloatingViewModel : ViewModel() {

    val isAccessibilityGranted: StateFlow<Boolean> = FloatingStateHolder.isAccessibilityEnabled
    val isOverlayGranted: StateFlow<Boolean> = FloatingStateHolder.isOverlayPermissionGranted
    val isServiceRunning: StateFlow<Boolean> = FloatingStateHolder.isOverlayServiceRunning
    val isAutoPlayActive: StateFlow<Boolean> = FloatingStateHolder.isAutoPlayActive
    val isAutoHintEnabled: StateFlow<Boolean> = FloatingStateHolder.isAutoHintEnabled
    val isAutoClickerEnabled: StateFlow<Boolean> = FloatingStateHolder.isAutoClickerEnabled
    val lastActionMessage: StateFlow<String> = FloatingStateHolder.lastActionMessage
    val bubbleSizeDp: StateFlow<Int> = FloatingStateHolder.bubbleSizeDp
    val isHapticEnabled: StateFlow<Boolean> = FloatingStateHolder.isHapticEnabled

    val boardState: StateFlow<Array<Piece>> = FloatingStateHolder.boardState
    val bestMove: StateFlow<GutiMove?> = FloatingStateHolder.bestMove
    val isAnalyzing: StateFlow<Boolean> = FloatingStateHolder.isAnalyzing
    val analysisCountdownSec: StateFlow<Int> = FloatingStateHolder.analysisCountdownSec
    val analysisResult: StateFlow<EngineResult?> = FloatingStateHolder.analysisResult
    val analysisTimeLimitSec: StateFlow<Int> = FloatingStateHolder.analysisTimeLimitSec

    // Calibration & interactive alignment parameters
    val boardVerticalOffsetFactor: StateFlow<Float> = FloatingStateHolder.boardVerticalOffsetFactor
    val boardHorizontalOffsetFactor: StateFlow<Float> = FloatingStateHolder.boardHorizontalOffsetFactor
    val boardScaleFactor: StateFlow<Float> = FloatingStateHolder.boardScaleFactor
    val boardVerticalScaleFactor: StateFlow<Float> = FloatingStateHolder.boardVerticalScaleFactor
    val triangleRatio: StateFlow<Float> = FloatingStateHolder.triangleRatio
    val triangleWidthRatio: StateFlow<Float> = FloatingStateHolder.triangleWidthRatio
    val nodeRadiusFactor: StateFlow<Float> = FloatingStateHolder.nodeRadiusFactor
    val isAlignModeActive: StateFlow<Boolean> = FloatingStateHolder.isAlignModeActive
    val isSyncModeActive: StateFlow<Boolean> = FloatingStateHolder.isSyncModeActive
    val showNodeIndicators: StateFlow<Boolean> = FloatingStateHolder.showNodeIndicators

    val isScreenCaptureActive: StateFlow<Boolean> = ScreenCaptureManager.isScreenCaptureActive
    val lastScanResult: StateFlow<ScanSummary?> = ScreenCaptureManager.lastScanResult

    fun refreshPermissions(context: Context) {
        FloatingStateHolder.initPrefs(context)
        val overlayGranted = PermissionHelper.isOverlayPermissionGranted(context)
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(context)
        FloatingStateHolder.updateOverlayPermissionStatus(overlayGranted)
        FloatingStateHolder.updateAccessibilityStatus(accessibilityEnabled)
    }

    fun requestAccessibility(context: Context) {
        PermissionHelper.openAccessibilitySettings(context)
    }

    fun requestOverlay(context: Context) {
        PermissionHelper.openOverlaySettings(context)
    }

    fun toggleService(context: Context) {
        if (isServiceRunning.value) {
            FloatingOverlayService.stop(context)
        } else {
            FloatingOverlayService.start(context)
        }
    }

    fun triggerScan(onComplete: ((ScanSummary) -> Unit)? = null) {
        ScreenCaptureManager.scanBoardFromScreen { summary ->
            FloatingStateHolder.triggerScanFlash()
            onComplete?.invoke(summary)
        }
    }

    fun triggerAnalysis() {
        FloatingStateHolder.triggerAnalysis()
    }

    fun cycleNodePiece(index: Int) {
        FloatingStateHolder.cycleNodePiece(index)
    }

    fun applyBestMove() {
        FloatingStateHolder.applyBestMove()
    }

    fun resetBoard() {
        FloatingStateHolder.resetBoard()
    }

    fun clearBoard() {
        FloatingStateHolder.clearBoard()
    }

    fun setAnalysisTimeLimit(sec: Int) {
        FloatingStateHolder.setAnalysisTimeLimitSec(sec)
    }

    fun toggleAutoHint() {
        FloatingStateHolder.toggleAutoHint()
    }

    fun toggleAutoClicker() {
        FloatingStateHolder.toggleAutoClicker()
    }

    fun setBoardVerticalOffset(factor: Float) {
        FloatingStateHolder.setBoardVerticalOffset(factor)
    }

    fun setBoardHorizontalOffset(factor: Float) {
        FloatingStateHolder.setBoardHorizontalOffset(factor)
    }

    fun setBoardScale(scale: Float) {
        FloatingStateHolder.setBoardScale(scale)
    }

    fun setBoardVerticalScale(scale: Float) {
        FloatingStateHolder.setBoardVerticalScale(scale)
    }

    fun setTriangleRatio(ratio: Float) {
        FloatingStateHolder.setTriangleRatio(ratio)
    }

    fun setTriangleWidthRatio(ratio: Float) {
        FloatingStateHolder.setTriangleWidthRatio(ratio)
    }

    fun setNodeRadiusFactor(factor: Float) {
        FloatingStateHolder.setNodeRadiusFactor(factor)
    }

    fun nudgeOffset(dx: Float, dy: Float) {
        FloatingStateHolder.nudgeOffset(dx, dy)
    }

    fun applyPreset(presetName: String) {
        FloatingStateHolder.applyPreset(presetName)
    }

    fun resetCalibrationToDefault() {
        FloatingStateHolder.resetCalibrationToDefault()
    }

    fun toggleAlignMode(active: Boolean? = null) {
        FloatingStateHolder.toggleAlignMode(active)
    }

    fun toggleSyncMode(active: Boolean? = null) {
        FloatingStateHolder.toggleSyncMode(active)
    }

    fun toggleNodeIndicators(show: Boolean? = null) {
        FloatingStateHolder.toggleNodeIndicators(show)
    }

    fun toggleAutoPlay() {
        FloatingStateHolder.setAutoPlay(!isAutoPlayActive.value)
    }

    fun setBubbleSize(sizeDp: Int) {
        FloatingStateHolder.setBubbleSize(sizeDp)
    }

    fun toggleHaptic(enabled: Boolean) {
        FloatingStateHolder.setHapticEnabled(enabled)
    }
}
