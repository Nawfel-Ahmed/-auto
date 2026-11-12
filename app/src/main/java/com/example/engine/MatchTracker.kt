package com.example.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historical record of a completed turn in the match.
 */
data class MatchMoveRecord(
    val moveNumber: Int,
    val player: Piece,
    val description: String,
    val blueRemaining: Int,
    val redRemaining: Int,
    val piecesCaptured: Int,
    val winProbability: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Match History & Advantage Tracker:
 * - Logs all moves played by both user (Blue) and opponent (Red)
 * - Tracks material balance, captured pieces, and score differentials
 * - Computes real-time Live Win Probability based on board dominance and center control
 */
class MatchTracker {
    private val _moveHistory = MutableStateFlow<List<MatchMoveRecord>>(emptyList())
    val moveHistory: StateFlow<List<MatchMoveRecord>> = _moveHistory.asStateFlow()

    private val _liveWinProbability = MutableStateFlow(50) // 50% default at start
    val liveWinProbability: StateFlow<Int> = _liveWinProbability.asStateFlow()

    private val _advantageText = MutableStateFlow("Balanced (50% Win)")
    val advantageText: StateFlow<String> = _advantageText.asStateFlow()

    private val _blueCapturedCount = MutableStateFlow(0)
    val blueCapturedCount: StateFlow<Int> = _blueCapturedCount.asStateFlow()

    private val _redCapturedCount = MutableStateFlow(0)
    val redCapturedCount: StateFlow<Int> = _redCapturedCount.asStateFlow()

    private var moveCounter = 0

    fun resetMatch() {
        moveCounter = 0
        _moveHistory.value = emptyList()
        _liveWinProbability.value = 50
        _advantageText.value = "Match Reset (50% Win)"
        _blueCapturedCount.value = 0
        _redCapturedCount.value = 0
    }

    /**
     * Records a board transition, updates captured counts, calculates live win probability,
     * and appends a human-readable entry to move history.
     */
    fun recordTurn(
        previousBoard: Array<Piece>,
        currentBoard: Array<Piece>,
        isOpponentMove: Boolean,
        hintMove: GutiMove? = null
    ) {
        val prevBlue = previousBoard.count { it == Piece.BLUE }
        val prevRed = previousBoard.count { it == Piece.RED }
        val curBlue = currentBoard.count { it == Piece.BLUE }
        val curRed = currentBoard.count { it == Piece.RED }

        moveCounter++
        val movingPlayer = if (isOpponentMove) Piece.RED else Piece.BLUE

        val blueLost = (prevBlue - curBlue).coerceAtLeast(0)
        val redLost = (prevRed - curRed).coerceAtLeast(0)

        val captures = if (isOpponentMove) blueLost else redLost
        if (isOpponentMove) {
            _blueCapturedCount.value += blueLost
        } else {
            _redCapturedCount.value += redLost
        }

        // Calculate live win probability based on material and positional dominance
        val winProb = calculateWinProbability(currentBoard)
        _liveWinProbability.value = winProb

        val diff = curBlue - curRed
        val advantageStr = when {
            curRed == 0 -> "Guaranteed Win! (100%)"
            curBlue == 0 -> "Defeat (0%)"
            diff > 0 -> "Blue Advantage +$diff ($winProb% Win)"
            diff < 0 -> "Red Advantage $diff ($winProb% Win)"
            else -> "Evenly Balanced ($winProb% Win)"
        }
        _advantageText.value = advantageStr

        // Generate descriptive move summary
        val desc = buildString {
            if (isOpponentMove) {
                append("Opponent (Red): ")
                if (blueLost > 1) {
                    append("Chain-jumped -$blueLost Blue pieces!")
                } else if (blueLost == 1) {
                    append("Jumped & captured 1 Blue piece")
                } else {
                    append("Positional advance")
                }
            } else {
                append("You (Blue): ")
                if (hintMove != null && hintMove.isChainCapture) {
                    append("Chain jump ${hintMove.from}→${hintMove.to} (+$redLost Red)")
                } else if (hintMove != null && hintMove.isCapture) {
                    append("Jump ${hintMove.from}→${hintMove.to} (+$redLost Red)")
                } else if (redLost > 0) {
                    append("Captured $redLost Red piece(s)")
                } else if (hintMove != null) {
                    append("Step ${hintMove.from}→${hintMove.to}")
                } else {
                    append("Strategic move")
                }
            }
            append(" [Blue: $curBlue, Red: $curRed]")
        }

        val record = MatchMoveRecord(
            moveNumber = moveCounter,
            player = movingPlayer,
            description = desc,
            blueRemaining = curBlue,
            redRemaining = curRed,
            piecesCaptured = captures,
            winProbability = winProb
        )

        _moveHistory.value = _moveHistory.value + record
    }

    /**
     * Computes the live win probability for Blue (0 to 100):
     * - Material balance: each piece differential represents ~7.5% win swing
     * - Center control: controlling node 18 and inner ring provides up to ±10%
     * - Wing fortress: controlling wings provides up to ±5%
     * - Immediate threat / capture bonus
     */
    fun calculateWinProbability(board: Array<Piece>): Int {
        val blueCount = board.count { it == Piece.BLUE }
        val redCount = board.count { it == Piece.RED }

        if (redCount == 0) return 100
        if (blueCount == 0) return 0

        // 1. Base Material Probability
        val diff = blueCount - redCount
        var prob = 50.0 + (diff * 7.5)

        // 2. Center Pivot Control (Node 18)
        if (board.size > 18) {
            when (board[18]) {
                Piece.BLUE -> prob += 5.0
                Piece.RED -> prob -= 5.0
                Piece.EMPTY -> {}
            }
        }

        // 3. Inner Ring Control (Nodes 12, 13, 14, 17, 19, 22, 23, 24)
        val innerRing = intArrayOf(12, 13, 14, 17, 19, 22, 23, 24)
        var blueCenter = 0
        var redCenter = 0
        for (idx in innerRing) {
            if (idx in board.indices) {
                if (board[idx] == Piece.BLUE) blueCenter++
                else if (board[idx] == Piece.RED) redCenter++
            }
        }
        prob += (blueCenter - redCenter) * 1.5

        return prob.toInt().coerceIn(2, 98)
    }

    fun getRecentHistorySummary(maxEntries: Int = 3): String {
        val history = _moveHistory.value
        if (history.isEmpty()) return "No moves logged yet"
        return history.takeLast(maxEntries).joinToString(" | ") { record ->
            "#${record.moveNumber} ${record.description}"
        }
    }
}
