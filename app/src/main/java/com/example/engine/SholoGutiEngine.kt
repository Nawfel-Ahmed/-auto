package com.example.engine

class SholoGutiEngine(
    var backboneWeight: Int = 60,
    var wingWeight: Int = 35,
    var mobilityWeight: Int = 20,
    var enforceZeroLoss: Boolean = true
) {
    private var nodesCount: Long = 0L
    private var deadlineTimeMs: Long = 0L
    private var isTimeExhausted: Boolean = false

    fun findBestMove(
        board: SholoGutiBoard,
        timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
        maxDepth: Int = 8,
        onProgress: ((depth: Int, nodes: Long, bestMove: GutiMove?) -> Unit)? = null
    ): EngineResult {
        val startTime = System.currentTimeMillis()
        deadlineTimeMs = startTime + timeLimitMs
        nodesCount = 0L
        isTimeExhausted = false

        val rootMoves = board.generateMoves(Piece.BLUE)
        if (rootMoves.isEmpty()) {
            return EngineResult(
                bestMove = null,
                score = -INFINITE_SCORE,
                depthReached = 0,
                nodesEvaluated = 0L,
                durationMs = 0L,
                isSafe = false,
                statusMessage = "No legal moves available"
            )
        }

        if (rootMoves.size == 1) {
            val soleMove = rootMoves.first()
            return EngineResult(
                bestMove = soleMove,
                score = 1000,
                depthReached = 1,
                nodesEvaluated = 1L,
                durationMs = System.currentTimeMillis() - startTime,
                isSafe = true,
                statusMessage = "Forced move: ${soleMove.from} -> ${soleMove.to}"
            )
        }

        var overallBestMove = rootMoves.first()
        var overallBestScore = -INFINITE_SCORE
        var depthCompleted = 0

        // Iterative Deepening
        for (depth in 1..maxDepth) {
            if (System.currentTimeMillis() >= deadlineTimeMs) break

            var currentDepthBestMove: GutiMove? = null
            var currentDepthBestScore = -INFINITE_SCORE

            // Sort moves: Captures first, then center backbone moves
            val sortedMoves = rootMoves.sortedWith(
                compareByDescending<GutiMove> { it.isCapture }
                    .thenByDescending { SholoGutiBoard.NODE_POINTS[it.to].isBackbone }
            )

            for (move in sortedMoves) {
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }

                val captured = board.makeMove(move)
                nodesCount++

                val score = -minimax(
                    board = board,
                    depth = depth - 1,
                    alpha = -INFINITE_SCORE,
                    beta = -currentDepthBestScore,
                    isMaximizing = false
                )

                board.unmakeMove(move, captured)

                val adjustedScore = if (enforceZeroLoss && !isMoveSafeFromImmediateLoss(board, move)) {
                    score - SUICIDAL_PENALTY
                } else {
                    score
                }

                if (adjustedScore > currentDepthBestScore) {
                    currentDepthBestScore = adjustedScore
                    currentDepthBestMove = move
                }
            }

            if (!isTimeExhausted && currentDepthBestMove != null) {
                overallBestMove = currentDepthBestMove
                overallBestScore = currentDepthBestScore
                depthCompleted = depth
                onProgress?.invoke(depth, nodesCount, overallBestMove)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val safe = !enforceZeroLoss || isMoveSafeFromImmediateLoss(board, overallBestMove)

        return EngineResult(
            bestMove = overallBestMove,
            score = overallBestScore,
            depthReached = depthCompleted.coerceAtLeast(1),
            nodesEvaluated = nodesCount,
            durationMs = duration,
            isSafe = safe,
            statusMessage = buildStatusMessage(overallBestMove, safe, depthCompleted)
        )
    }

    private fun minimax(
        board: SholoGutiBoard,
        depth: Int,
        alpha: Int,
        beta: Int,
        isMaximizing: Boolean
    ): Int {
        if (System.currentTimeMillis() >= deadlineTimeMs) {
            isTimeExhausted = true
            return evaluate(board)
        }

        val player = if (isMaximizing) Piece.BLUE else Piece.RED
        val moves = board.generateMoves(player)

        if (depth == 0 || moves.isEmpty()) {
            return evaluate(board)
        }

        var currentAlpha = alpha
        var currentBeta = beta

        if (isMaximizing) {
            var maxEval = -INFINITE_SCORE
            val sortedMoves = moves.sortedByDescending { it.isCapture }
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, false)
                board.unmakeMove(move, captured)

                maxEval = maxOf(maxEval, evaluation)
                currentAlpha = maxOf(currentAlpha, evaluation)
                if (currentBeta <= currentAlpha) break
            }
            return maxEval
        } else {
            var minEval = INFINITE_SCORE
            val sortedMoves = moves.sortedByDescending { it.isCapture }
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, true)
                board.unmakeMove(move, captured)

                minEval = minOf(minEval, evaluation)
                currentBeta = minOf(currentBeta, evaluation)
                if (currentBeta <= currentAlpha) break
            }
            return minEval
        }
    }

    fun isMoveSafeFromImmediateLoss(board: SholoGutiBoard, blueMove: GutiMove): Boolean {
        val captured = board.makeMove(blueMove)
        val opponentCounterMoves = board.generateMoves(Piece.RED)
        val hasImmediateCounterCapture = opponentCounterMoves.any { it.isCapture && it.captured == blueMove.to }
        board.unmakeMove(blueMove, captured)
        return !hasImmediateCounterCapture
    }

    fun evaluate(board: SholoGutiBoard): Int {
        val bluePieces = board.countPieces(Piece.BLUE)
        val redPieces = board.countPieces(Piece.RED)

        if (bluePieces == 0) return -INFINITE_SCORE
        if (redPieces == 0) return INFINITE_SCORE

        var materialScore = (bluePieces - redPieces) * 1000
        var backboneScore = 0
        var wingScore = 0

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            val pt = SholoGutiBoard.NODE_POINTS[i]
            if (p == Piece.BLUE) {
                if (pt.isBackbone) backboneScore += backboneWeight
                if (pt.isWing) wingScore += wingWeight
            } else if (p == Piece.RED) {
                if (pt.isBackbone) backboneScore -= backboneWeight
                if (pt.isWing) wingScore -= wingWeight
            }
        }

        val blueMobility = board.generateMoves(Piece.BLUE).size
        val redMobility = board.generateMoves(Piece.RED).size
        val mobilityScore = (blueMobility - redMobility) * mobilityWeight

        return materialScore + backboneScore + wingScore + mobilityScore
    }

    private fun buildStatusMessage(move: GutiMove, isSafe: Boolean, depth: Int): String {
        return buildString {
            if (move.isCapture) {
                append("⚔️ JUMP CAPTURE node ${move.captured}! ")
            } else {
                append("Tactical Advance: ")
            }
            append("${move.from} → ${move.to}")
            if (isSafe) {
                append(" (Zero-Loss Safe ✓, d=$depth)")
            } else {
                append(" (High Pressure)")
            }
        }
    }

    companion object {
        const val INFINITE_SCORE = 1_000_000
        const val SUICIDAL_PENALTY = 500_000
        const val DEFAULT_TIME_LIMIT_MS = 10_000L
    }
}
