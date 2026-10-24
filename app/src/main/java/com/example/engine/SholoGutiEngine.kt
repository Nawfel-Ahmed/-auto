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
        maxDepth: Int = 32,
        onProgress: ((depth: Int, nodes: Long, bestMove: GutiMove?) -> Unit)? = null
    ): EngineResult {
        return try {
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

            var overallBestMove = rootMoves.first()
            var overallBestScore = -INFINITE_SCORE
            var depthCompleted = 0

            // Initial immediate candidate update so UI instantly has a hint arrow
            val initialSafeMove = rootMoves.firstOrNull { isMoveSafeFromImmediateLoss(board, it) } ?: overallBestMove
            overallBestMove = initialSafeMove
            try {
                onProgress?.invoke(1, 1L, overallBestMove)
            } catch (_: Throwable) {}

            // Iterative Deepening with full time utilization
            // Iterative Deepening with full time utilization
            for (depth in 1..maxDepth) {
                if (System.currentTimeMillis() >= deadlineTimeMs) break

                var currentDepthBestMove: GutiMove? = null
                var currentDepthBestScore = -INFINITE_SCORE
                var currentAlpha = -INFINITE_SCORE
                val beta = INFINITE_SCORE

                // Move ordering: Prioritize jumps, then center node 18, then backbone nodes
                val sortedMoves = rootMoves.sortedWith(
                    compareByDescending<GutiMove> { it.isCapture }
                        .thenByDescending { it.to == 18 }
                        .thenByDescending { SholoGutiBoard.NODE_POINTS.getOrNull(it.to)?.isBackbone == true }
                )

                for (move in sortedMoves) {
                    if (System.currentTimeMillis() >= deadlineTimeMs) {
                        isTimeExhausted = true
                        break
                    }

                    val captured = board.makeMove(move)
                    nodesCount++

                    // Blue made move, next is Red minimizing (isMaximizing = false)
                    val score = minimax(
                        board = board,
                        depth = depth - 1,
                        alpha = currentAlpha,
                        beta = beta,
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
                    currentAlpha = maxOf(currentAlpha, adjustedScore)
                }

                if (!isTimeExhausted && currentDepthBestMove != null) {
                    overallBestMove = currentDepthBestMove
                    overallBestScore = currentDepthBestScore
                    depthCompleted = depth
                    try {
                        onProgress?.invoke(depth, nodesCount, overallBestMove)
                    } catch (_: Throwable) {}
                }
            }

            val duration = System.currentTimeMillis() - startTime
            val safe = !enforceZeroLoss || isMoveSafeFromImmediateLoss(board, overallBestMove)

            EngineResult(
                bestMove = overallBestMove,
                score = overallBestScore,
                depthReached = depthCompleted.coerceAtLeast(1),
                nodesEvaluated = nodesCount,
                durationMs = duration,
                isSafe = safe,
                statusMessage = buildStatusMessage(overallBestMove, safe, depthCompleted.coerceAtLeast(1))
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            val fallbackMove = try { board.generateMoves(Piece.BLUE).firstOrNull() } catch (_: Throwable) { null }
            EngineResult(
                bestMove = fallbackMove,
                score = 0,
                depthReached = 1,
                nodesEvaluated = 1L,
                durationMs = 0L,
                isSafe = true,
                statusMessage = fallbackMove?.let { "Move: ${it.from} → ${it.to}" } ?: "No moves found"
            )
        }
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

        if (moves.isEmpty()) {
            return if (isMaximizing) -INFINITE_SCORE else INFINITE_SCORE
        }

        if (depth <= 0) {
            return quiescence(board, alpha, beta, isMaximizing, qDepth = 3)
        }

        var currentAlpha = alpha
        var currentBeta = beta

        if (isMaximizing) {
            var maxEval = -INFINITE_SCORE
            val sortedMoves = moves.sortedWith(
                compareByDescending<GutiMove> { it.isCapture }
                    .thenByDescending { it.to == 18 || SholoGutiBoard.NODE_POINTS.getOrNull(it.to)?.isBackbone == true }
            )
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, false)
                board.unmakeMove(move, captured)

                maxEval = maxOf(maxEval, evaluation)
                currentAlpha = maxOf(currentAlpha, evaluation)
                if (currentBeta <= currentAlpha) break
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }
            }
            return maxEval
        } else {
            var minEval = INFINITE_SCORE
            val sortedMoves = moves.sortedWith(
                compareByDescending<GutiMove> { it.isCapture }
                    .thenByDescending { it.to == 18 || SholoGutiBoard.NODE_POINTS.getOrNull(it.to)?.isBackbone == true }
            )
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, true)
                board.unmakeMove(move, captured)

                minEval = minOf(minEval, evaluation)
                currentBeta = minOf(currentBeta, evaluation)
                if (currentBeta <= currentAlpha) break
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }
            }
            return minEval
        }
    }

    /**
     * Quiescence search on jump captures to resolve horizon effect and ensure piece safety.
     */
    private fun quiescence(
        board: SholoGutiBoard,
        alpha: Int,
        beta: Int,
        isMaximizing: Boolean,
        qDepth: Int
    ): Int {
        if (System.currentTimeMillis() >= deadlineTimeMs) {
            isTimeExhausted = true
            return evaluate(board)
        }

        val standPat = evaluate(board)
        if (qDepth <= 0) return standPat

        val player = if (isMaximizing) Piece.BLUE else Piece.RED
        val captureMoves = board.generateMoves(player).filter { it.isCapture }
        if (captureMoves.isEmpty()) return standPat

        var currentAlpha = alpha
        var currentBeta = beta

        if (isMaximizing) {
            if (standPat >= currentBeta) return currentBeta
            if (standPat > currentAlpha) currentAlpha = standPat

            for (move in captureMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val score = quiescence(board, currentAlpha, currentBeta, false, qDepth - 1)
                board.unmakeMove(move, captured)

                if (score >= currentBeta) return currentBeta
                if (score > currentAlpha) currentAlpha = score
            }
            return currentAlpha
        } else {
            if (standPat <= currentAlpha) return currentAlpha
            if (standPat < currentBeta) currentBeta = standPat

            for (move in captureMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val score = quiescence(board, currentAlpha, currentBeta, true, qDepth - 1)
                board.unmakeMove(move, captured)

                if (score <= currentAlpha) return currentAlpha
                if (score < currentBeta) currentBeta = score
            }
            return currentBeta
        }
    }

    fun isMoveSafeFromImmediateLoss(board: SholoGutiBoard, blueMove: GutiMove): Boolean {
        return try {
            val captured = board.makeMove(blueMove)
            val opponentCounterMoves = board.generateMoves(Piece.RED)
            val hasImmediateCounterCapture = opponentCounterMoves.any { it.isCapture && it.captured == blueMove.to }
            board.unmakeMove(blueMove, captured)
            // If the move was a capture, trading is acceptable; if it was a step, losing it immediately is a blunder
            if (blueMove.isCapture) true else !hasImmediateCounterCapture
        } catch (_: Throwable) {
            true
        }
    }

    fun evaluate(board: SholoGutiBoard): Int {
        val bluePieces = board.countPieces(Piece.BLUE)
        val redPieces = board.countPieces(Piece.RED)

        if (bluePieces == 0) return -INFINITE_SCORE
        if (redPieces == 0) return INFINITE_SCORE

        var materialScore = (bluePieces - redPieces) * 1000
        var backboneScore = 0
        var wingScore = 0
        var defenseScore = 0

        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            if (p == Piece.EMPTY) continue
            val pt = SholoGutiBoard.NODE_POINTS[i]

            // Center node 18 is the primary strategic pivot in 16 Ghuti
            val isCenter = (i == 18)
            val backboneWeightForNode = if (isCenter) backboneWeight * 2 else backboneWeight

            if (p == Piece.BLUE) {
                if (pt.isBackbone) backboneScore += backboneWeightForNode
                if (pt.isWing) {
                    val hasWingSupporter = SholoGutiBoard.ADJACENCY[i].any { board.pieces[it] == Piece.BLUE }
                    wingScore += if (hasWingSupporter) wingWeight else -wingWeight
                }
                val friendlyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.BLUE }
                defenseScore += friendlyNeighbors * 15
            } else {
                if (pt.isBackbone) backboneScore -= backboneWeightForNode
                if (pt.isWing) {
                    val hasWingSupporter = SholoGutiBoard.ADJACENCY[i].any { board.pieces[it] == Piece.RED }
                    wingScore -= if (hasWingSupporter) wingWeight else -wingWeight
                }
                val enemyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.RED }
                defenseScore -= enemyNeighbors * 15
            }
        }

        val blueMobility = board.generateMoves(Piece.BLUE).size
        val redMobility = board.generateMoves(Piece.RED).size
        val mobilityScore = (blueMobility - redMobility) * mobilityWeight

        return materialScore + backboneScore + wingScore + defenseScore + mobilityScore
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
