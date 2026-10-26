package com.example.service

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.withContext

object FloatingStateHolder {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var prefs: SharedPreferences? = null

    // Permissions & Service Status
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isOverlayPermissionGranted = MutableStateFlow(false)
    val isOverlayPermissionGranted: StateFlow<Boolean> = _isOverlayPermissionGranted.asStateFlow()

    private val _isOverlayServiceRunning = MutableStateFlow(false)
    val isOverlayServiceRunning: StateFlow<Boolean> = _isOverlayServiceRunning.asStateFlow()

    private val _isAutoPlayActive = MutableStateFlow(false)
    val isAutoPlayActive: StateFlow<Boolean> = _isAutoPlayActive.asStateFlow()

    private val _isAutoHintEnabled = MutableStateFlow(true)
    val isAutoHintEnabled: StateFlow<Boolean> = _isAutoHintEnabled.asStateFlow()

    private val _isAutoClickerEnabled = MutableStateFlow(true)
    val isAutoClickerEnabled: StateFlow<Boolean> = _isAutoClickerEnabled.asStateFlow()

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

    // Calibrated Alignment Parameters (Percentage-based & Persistent)
    const val DEFAULT_SCALE = 0.886f
    const val DEFAULT_V_OFFSET = 0.4764f
    const val DEFAULT_H_OFFSET = 0.5000f
    const val DEFAULT_V_SCALE = 1.0000f
    const val DEFAULT_TRI_RATIO = 1.0000f
    const val DEFAULT_TRI_WIDTH_RATIO = 1.0000f
    const val DEFAULT_NODE_RADIUS_FACTOR = 1.0000f

    private val _boardVerticalOffsetFactor = MutableStateFlow(DEFAULT_V_OFFSET)
    val boardVerticalOffsetFactor: StateFlow<Float> = _boardVerticalOffsetFactor.asStateFlow()

    private val _boardHorizontalOffsetFactor = MutableStateFlow(DEFAULT_H_OFFSET)
    val boardHorizontalOffsetFactor: StateFlow<Float> = _boardHorizontalOffsetFactor.asStateFlow()

    private val _boardScaleFactor = MutableStateFlow(DEFAULT_SCALE)
    val boardScaleFactor: StateFlow<Float> = _boardScaleFactor.asStateFlow()

    private val _boardVerticalScaleFactor = MutableStateFlow(DEFAULT_V_SCALE)
    val boardVerticalScaleFactor: StateFlow<Float> = _boardVerticalScaleFactor.asStateFlow()

    private val _triangleRatio = MutableStateFlow(DEFAULT_TRI_RATIO)
    val triangleRatio: StateFlow<Float> = _triangleRatio.asStateFlow()

    private val _triangleWidthRatio = MutableStateFlow(DEFAULT_TRI_WIDTH_RATIO)
    val triangleWidthRatio: StateFlow<Float> = _triangleWidthRatio.asStateFlow()

    private val _nodeRadiusFactor = MutableStateFlow(DEFAULT_NODE_RADIUS_FACTOR)
    val nodeRadiusFactor: StateFlow<Float> = _nodeRadiusFactor.asStateFlow()

    // Interactive Align & Modes
    private val _isAlignModeActive = MutableStateFlow(false)
    val isAlignModeActive: StateFlow<Boolean> = _isAlignModeActive.asStateFlow()

    private val _isSyncModeActive = MutableStateFlow(false)
    val isSyncModeActive: StateFlow<Boolean> = _isSyncModeActive.asStateFlow()

    private val _showNodeIndicators = MutableStateFlow(false)
    val showNodeIndicators: StateFlow<Boolean> = _showNodeIndicators.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _scanFlashActive = MutableStateFlow(false)
    val scanFlashActive: StateFlow<Boolean> = _scanFlashActive.asStateFlow()

    private var analysisJob: Job? = null
    val engine = SholoGutiEngine()

    fun initPrefs(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("sholo_guti_align_prefs", Context.MODE_PRIVATE)
            prefs?.let { p ->
                _boardScaleFactor.value = p.getFloat("scale", DEFAULT_SCALE)
                _boardVerticalOffsetFactor.value = p.getFloat("vOffset", DEFAULT_V_OFFSET)
                _boardHorizontalOffsetFactor.value = p.getFloat("hOffset", DEFAULT_H_OFFSET)
                _boardVerticalScaleFactor.value = p.getFloat("vScale", DEFAULT_V_SCALE)
                _triangleRatio.value = p.getFloat("triRatio", DEFAULT_TRI_RATIO)
                _triangleWidthRatio.value = p.getFloat("triWidthRatio", DEFAULT_TRI_WIDTH_RATIO)
                _nodeRadiusFactor.value = p.getFloat("nodeRadiusFactor", DEFAULT_NODE_RADIUS_FACTOR)
                _isAutoHintEnabled.value = p.getBoolean("autoHintEnabled", true)
                _isAutoClickerEnabled.value = p.getBoolean("autoClickerEnabled", true)
            }
        }
    }

    fun savePrefs() {
        prefs?.edit()?.apply {
            putFloat("scale", _boardScaleFactor.value)
            putFloat("vOffset", _boardVerticalOffsetFactor.value)
            putFloat("hOffset", _boardHorizontalOffsetFactor.value)
            putFloat("vScale", _boardVerticalScaleFactor.value)
            putFloat("triRatio", _triangleRatio.value)
            putFloat("triWidthRatio", _triangleWidthRatio.value)
            putFloat("nodeRadiusFactor", _nodeRadiusFactor.value)
            putBoolean("autoHintEnabled", _isAutoHintEnabled.value)
            putBoolean("autoClickerEnabled", _isAutoClickerEnabled.value)
            apply()
        }
    }

    fun toggleAutoHint(enabled: Boolean? = null) {
        val next = enabled ?: !_isAutoHintEnabled.value
        _isAutoHintEnabled.value = next
        savePrefs()
        _lastActionMessage.value = if (next) "Auto-Hint ON: Triggers 3s after opponent moves" else "Auto-Hint OFF"
    }

    fun toggleAutoClicker(enabled: Boolean? = null) {
        val next = enabled ?: !_isAutoClickerEnabled.value
        _isAutoClickerEnabled.value = next
        savePrefs()
        _lastActionMessage.value = if (next) "Auto-Clicker Enabled (Tap automation)" else "Auto-Clicker Disabled"
    }

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
        savePrefs()
    }

    fun setBoardHorizontalOffset(factor: Float) {
        _boardHorizontalOffsetFactor.value = factor.coerceIn(0.10f, 0.90f)
        savePrefs()
    }

    fun setBoardScale(factor: Float) {
        _boardScaleFactor.value = factor.coerceIn(0.40f, 1.40f)
        savePrefs()
    }

    fun setBoardVerticalScale(factor: Float) {
        _boardVerticalScaleFactor.value = factor.coerceIn(0.50f, 1.80f)
        savePrefs()
    }

    fun setTriangleRatio(ratio: Float) {
        _triangleRatio.value = ratio.coerceIn(0.20f, 1.20f)
        savePrefs()
    }

    fun setTriangleWidthRatio(ratio: Float) {
        _triangleWidthRatio.value = ratio.coerceIn(0.50f, 1.50f)
        savePrefs()
    }

    fun setNodeRadiusFactor(factor: Float) {
        _nodeRadiusFactor.value = factor.coerceIn(0.40f, 2.20f)
        savePrefs()
    }

    fun nudgeOffset(dx: Float, dy: Float) {
        _boardHorizontalOffsetFactor.value = (_boardHorizontalOffsetFactor.value + dx).coerceIn(0.10f, 0.90f)
        _boardVerticalOffsetFactor.value = (_boardVerticalOffsetFactor.value + dy).coerceIn(0.10f, 0.90f)
        savePrefs()
    }

    fun triggerScanFlash() {
        scope.launch {
            _scanFlashActive.value = true
            delay(1200L)
            _scanFlashActive.value = false
        }
    }

    fun resetCalibrationToDefault() {
        _boardHorizontalOffsetFactor.value = DEFAULT_H_OFFSET
        _boardVerticalOffsetFactor.value = DEFAULT_V_OFFSET
        _boardScaleFactor.value = DEFAULT_SCALE
        _boardVerticalScaleFactor.value = DEFAULT_V_SCALE
        _triangleRatio.value = DEFAULT_TRI_RATIO
        _triangleWidthRatio.value = DEFAULT_TRI_WIDTH_RATIO
        _nodeRadiusFactor.value = DEFAULT_NODE_RADIUS_FACTOR
        savePrefs()
    }

    fun applyPreset(presetName: String) {
        when (presetName) {
            "AlignIt" -> {
                // Preset for authentic 16 Guti
                _boardHorizontalOffsetFactor.value = DEFAULT_H_OFFSET
                _boardVerticalOffsetFactor.value = DEFAULT_V_OFFSET
                _boardScaleFactor.value = DEFAULT_SCALE
                _boardVerticalScaleFactor.value = DEFAULT_V_SCALE
                _triangleRatio.value = DEFAULT_TRI_RATIO
                _triangleWidthRatio.value = DEFAULT_TRI_WIDTH_RATIO
            }
            "Compact" -> {
                _boardHorizontalOffsetFactor.value = 0.50f
                _boardVerticalOffsetFactor.value = 0.47f
                _boardScaleFactor.value = 0.82f
                _boardVerticalScaleFactor.value = 0.98f
                _triangleRatio.value = 1.00f
                _triangleWidthRatio.value = 1.00f
            }
            "Expanded" -> {
                _boardHorizontalOffsetFactor.value = 0.50f
                _boardVerticalOffsetFactor.value = 0.48f
                _boardScaleFactor.value = 0.94f
                _boardVerticalScaleFactor.value = 1.02f
                _triangleRatio.value = 1.02f
                _triangleWidthRatio.value = 1.00f
            }
        }
        savePrefs()
    }

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    fun toggleAlignMode(active: Boolean? = null) {
        val nextState = active ?: !_isAlignModeActive.value
        _isAlignModeActive.value = nextState
        _showNodeIndicators.value = nextState
    }

    fun toggleSyncMode(active: Boolean? = null) {
        val nextState = active ?: !_isSyncModeActive.value
        _isSyncModeActive.value = nextState
        _showNodeIndicators.value = nextState
    }

    fun setSyncMode(active: Boolean) {
        _isSyncModeActive.value = active
        _showNodeIndicators.value = active
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

    fun updateAllNodes(newPieces: Array<Piece>) {
        val blueCount = newPieces.count { it == Piece.BLUE }
        val redCount = newPieces.count { it == Piece.RED }

        // Sanity check: In 16 Ghuti, players start with 16 pieces each (32 total).
        // Only update board if at least 2 pieces total were detected.
        // If an accidental or obscured scan happens, keep existing pieces intact!
        if (blueCount + redCount >= 2) {
            for (i in 0 until SholoGutiBoard.TOTAL_NODES.coerceAtMost(newPieces.size)) {
                currentBoard.pieces[i] = newPieces[i]
            }
            _boardState.value = currentBoard.pieces.clone()
        }

        // Clear previous stale move so that new scan enables fresh move calculation
        _bestMove.value = null
        _analysisResult.value = null
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
        _analysisResult.value = null
    }

    fun triggerAnalysis(onComplete: ((EngineResult) -> Unit)? = null) {
        if (_isAnalyzing.value) return

        try {
            analysisJob?.cancel()
            _isAnalyzing.value = true
            // Strictly clear any prior hint arrow so it does not appear on screen during countdown
            _bestMove.value = null
            _analysisResult.value = null

            val limitSec = 10
            _analysisTimeLimitSec.value = limitSec
            _analysisCountdownSec.value = limitSec
            _lastActionMessage.value = "Analyzing board deeply (10s)..."

            analysisJob = scope.launch(Dispatchers.Default) {
                val startMs = System.currentTimeMillis()

                try {
                    // Strictly timed 1-second countdown ticker
                    val countdownJob = launch {
                        try {
                            for (s in limitSec downTo 1) {
                                _analysisCountdownSec.value = s
                                delay(1000L)
                            }
                        } catch (_: Exception) {}
                    }

                    val boardCopy = currentBoard.copy()
                    // Fallback: If board has no Blue pieces, initialize default board so hint arrow is always generated
                    if (boardCopy.countPieces(Piece.BLUE) == 0) {
                        boardCopy.setupDefaultBoard()
                    }

                    // Run deep Minimax engine across the 10-second window
                    var latestFoundMove: GutiMove? = null
                    val engineResult = engine.findBestMove(
                        board = boardCopy,
                        timeLimitMs = limitSec * 1000L,
                        maxDepth = 64,
                        onProgress = { _, _, moveFound ->
                            if (moveFound != null) {
                                latestFoundMove = moveFound
                            }
                        }
                    )

                    // STRICT 10-SECOND GUARANTEE: Wait for the full 10,000ms duration
                    val elapsedMs = System.currentTimeMillis() - startMs
                    val remainingMs = (limitSec * 1000L) - elapsedMs
                    if (remainingMs > 0) {
                        delay(remainingMs)
                    }

                    try { countdownJob.cancel() } catch (_: Exception) {}
                    _analysisCountdownSec.value = 0
                    _isAnalyzing.value = false

                    val finalBestMove = engineResult.bestMove ?: latestFoundMove
                    val finalResult = if (engineResult.bestMove != null) {
                        engineResult
                    } else if (finalBestMove != null) {
                        engineResult.copy(bestMove = finalBestMove)
                    } else {
                        engineResult
                    }

                    _analysisResult.value = finalResult
                    // Publish best move ONLY after the full 10-second countdown reaches 0
                    _bestMove.value = finalResult.bestMove
                    _lastActionMessage.value = finalResult.statusMessage

                    // CRITICAL: onComplete must be invoked on Main thread so view operations do not crash
                    withContext(Dispatchers.Main) {
                        try {
                            onComplete?.invoke(finalResult)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    _isAnalyzing.value = false
                    _analysisCountdownSec.value = 0
                    val errResult = EngineResult(
                        bestMove = null,
                        score = 0,
                        depthReached = 0,
                        nodesEvaluated = 0,
                        durationMs = System.currentTimeMillis() - startMs,
                        isSafe = false,
                        statusMessage = "Analysis completed: no moves found"
                    )
                    withContext(Dispatchers.Main) {
                        try {
                            onComplete?.invoke(errResult)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            _isAnalyzing.value = false
            _analysisCountdownSec.value = 0
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
