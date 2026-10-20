package com.example.service

import com.example.engine.EngineResult
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import com.example.engine.SholoGutiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object FloatingStateHolder {
    private val scope = CoroutineScope(Dispatchers.Default)

    // Permissions & Service Status
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isOverlayPermissionGranted = MutableStateFlow(false)
    val isOverlayPermissionGranted: StateFlow<Boolean> = _isOverlayPermissionGranted.asStateFlow()

    private val _isOverlayServiceRunning = MutableStateFlow(false)
    val isOverlayServiceRunning: StateFlow<Boolean> = _isOverlayServiceRunning.asStateFlow()

    private val _isAutoPlayActive = MutableStateFlow(false)
    val isAutoPlayActive: StateFlow<Boolean> = _isAutoPlayActive.asStateFlow()

    private val _lastActionMessage = MutableStateFlow("Ready")
    val lastActionMessage: StateFlow<String> = _lastActionMessage.asStateFlow()

    private val _bubbleSizeDp = MutableStateFlow(56)
    val bubbleSizeDp: StateFlow<Int> = _bubbleSizeDp.asStateFlow()

    private val _isHapticEnabled = MutableStateFlow(true)
    val isHapticEnabled: StateFlow<Boolean> = _isHapticEnabled.asStateFlow()

    // 16 Ghuti Board State
    val currentBoard = SholoGutiBoard()

    private val _boardState = MutableStateFlow<Array<Piece>>(currentBoard.pieces.clone())
    val boardState: StateFlow<Array<Piece>> = _boardState.asStateFlow()

    private val _bestMove = MutableStateFlow<GutiMove?>(null)
    val bestMove: StateFlow<GutiMove?> = _bestMove.asStateFlow()

    // Analysis State
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisCountdownSec = MutableStateFlow(0)
    val analysisCountdownSec: StateFlow<Int> = _analysisCountdownSec.asStateFlow()

    private val _analysisResult = MutableStateFlow<EngineResult?>(null)
    val analysisResult: StateFlow<EngineResult?> = _analysisResult.asStateFlow()

    private val _analysisTimeLimitSec = MutableStateFlow(10)
    val analysisTimeLimitSec: StateFlow<Int> = _analysisTimeLimitSec.asStateFlow()

    // Calibration & Alignment State (Interactive Move, Scale, and Ratio)
    private val _boardVerticalOffsetFactor = MutableStateFlow(0.50f)
    val boardVerticalOffsetFactor: StateFlow<Float> = _boardVerticalOffsetFactor.asStateFlow()

    private val _boardHorizontalOffsetFactor = MutableStateFlow(0.50f)
    val boardHorizontalOffsetFactor: StateFlow<Float> = _boardHorizontalOffsetFactor.asStateFlow()

    private val _boardScaleFactor = MutableStateFlow(0.92f)
    val boardScaleFactor: StateFlow<Float> = _boardScaleFactor.asStateFlow()

    private val _boardVerticalScaleFactor = MutableStateFlow(1.00f)
    val boardVerticalScaleFactor: StateFlow<Float> = _boardVerticalScaleFactor.asStateFlow()

    private val _triangleRatio = MutableStateFlow(1.00f)
    val triangleRatio: StateFlow<Float> = _triangleRatio.asStateFlow()

    // Interactive Align & Modes
    private val _isAlignModeActive = MutableStateFlow(false)
    val isAlignModeActive: StateFlow<Boolean> = _isAlignModeActive.asStateFlow()

    private val _isSyncModeActive = MutableStateFlow(false)
    val isSyncModeActive: StateFlow<Boolean> = _isSyncModeActive.asStateFlow()

    private val _showNodeIndicators = MutableStateFlow(false)
    val showNodeIndicators: StateFlow<Boolean> = _showNodeIndicators.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private var analysisJob: Job? = null
    val engine = SholoGutiEngine()

    fun updateAccessibilityStatus(enabled: Boolean) {
        _isAccessibilityEnabled.value = enabled
    }

    fun updateOverlayPermissionStatus(granted: Boolean) {
        _isOverlayPermissionGranted.value = granted
    }

    fun updateServiceRunningStatus(running: Boolean) {
        _isOverlayServiceRunning.value = running
    }

    fun setAutoPlay(active: Boolean, message: String = "Auto-play: $active") {
        _isAutoPlayActive.value = active
        _lastActionMessage.value = message
    }

    fun setBubbleSize(sizeDp: Int) {
        _bubbleSizeDp.value = sizeDp
    }

    fun setHapticEnabled(enabled: Boolean) {
        _isHapticEnabled.value = enabled
    }

    fun setAnalysisTimeLimitSec(sec: Int) {
        _analysisTimeLimitSec.value = sec.coerceIn(1, 30)
    }

    fun setBoardVerticalOffset(factor: Float) {
        _boardVerticalOffsetFactor.value = factor.coerceIn(0.10f, 0.90f)
    }

    fun setBoardHorizontalOffset(factor: Float) {
        _boardHorizontalOffsetFactor.value = factor.coerceIn(0.10f, 0.90f)
    }

    fun setBoardScale(factor: Float) {
        _boardScaleFactor.value = factor.coerceIn(0.40f, 1.40f)
    }

    fun setBoardVerticalScale(factor: Float) {
        _boardVerticalScaleFactor.value = factor.coerceIn(0.50f, 1.80f)
    }

    fun setTriangleRatio(ratio: Float) {
        _triangleRatio.value = ratio.coerceIn(0.50f, 1.80f)
    }

    fun nudgeOffset(dx: Float, dy: Float) {
        _boardHorizontalOffsetFactor.value = (_boardHorizontalOffsetFactor.value + dx).coerceIn(0.10f, 0.90f)
        _boardVerticalOffsetFactor.value = (_boardVerticalOffsetFactor.value + dy).coerceIn(0.10f, 0.90f)
    }

    fun resetCalibrationToDefault() {
        _boardHorizontalOffsetFactor.value = 0.50f
        _boardVerticalOffsetFactor.value = 0.50f
        _boardScaleFactor.value = 0.92f
        _boardVerticalScaleFactor.value = 1.00f
        _triangleRatio.value = 1.00f
    }

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    fun toggleAlignMode(active: Boolean? = null) {
        val nextState = active ?: !_isAlignModeActive.value
        _isAlignModeActive.value = nextState
        // When align mode is active, show alignment indicators and wireframe
        _showNodeIndicators.value = nextState
    }

    fun toggleSyncMode(active: Boolean? = null) {
        val nextState = active ?: !_isSyncModeActive.value
        _isSyncModeActive.value = nextState
        _showNodeIndicators.value = nextState
    }

    fun toggleNodeIndicators(show: Boolean? = null) {
        _showNodeIndicators.value = show ?: !_showNodeIndicators.value
    }

    fun cycleNodePiece(index: Int) {
        if (index in 0 until SholoGutiBoard.TOTAL_NODES) {
            val next = when (currentBoard.pieces[index]) {
                Piece.EMPTY -> Piece.BLUE
                Piece.BLUE -> Piece.RED
                Piece.RED -> Piece.EMPTY
            }
            currentBoard.pieces[index] = next
            _boardState.value = currentBoard.pieces.clone()
        }
    }

    fun setNodePiece(index: Int, piece: Piece) {
        if (index in 0 until SholoGutiBoard.TOTAL_NODES) {
            currentBoard.pieces[index] = piece
            _boardState.value = currentBoard.pieces.clone()
        }
    }

    fun resetBoard() {
        currentBoard.setupDefaultBoard()
        _boardState.value = currentBoard.pieces.clone()
        _bestMove.value = null
        _analysisResult.value = null
        _lastActionMessage.value = "Board reset to default"
    }

    fun clearBoard() {
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            currentBoard.pieces[i] = Piece.EMPTY
        }
        _boardState.value = currentBoard.pieces.clone()
        _bestMove.value = null
        _analysisResult.value = null
        _lastActionMessage.value = "Board cleared"
    }

    fun clearBestMove() {
        _bestMove.value = null
    }

    fun triggerAnalysis(onComplete: ((EngineResult) -> Unit)? = null) {
        if (_isAnalyzing.value) return

        analysisJob?.cancel()
        _isAnalyzing.value = true
        val limitSec = _analysisTimeLimitSec.value
        _analysisCountdownSec.value = limitSec

        analysisJob = scope.launch {
            val countdownJob = launch {
                for (s in limitSec downTo 1) {
                    _analysisCountdownSec.value = s
                    delay(1000L)
                }
                _analysisCountdownSec.value = 0
            }

            val result = engine.findBestMove(
                board = currentBoard.copy(),
                timeLimitMs = limitSec * 1000L,
                maxDepth = 9
            )

            countdownJob.cancel()
            _isAnalyzing.value = false
            _analysisCountdownSec.value = 0
            _analysisResult.value = result
            _bestMove.value = result.bestMove
            _lastActionMessage.value = result.statusMessage

            onComplete?.invoke(result)
        }
    }

    fun applyBestMove() {
        val move = _bestMove.value ?: return
        currentBoard.makeMove(move)
        _boardState.value = currentBoard.pieces.clone()
        _bestMove.value = null
        _lastActionMessage.value = "Applied move ${move.from} → ${move.to}"
    }
}
