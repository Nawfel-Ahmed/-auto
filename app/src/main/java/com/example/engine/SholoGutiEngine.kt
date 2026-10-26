package com.example.engine

class SholoGutiEngine(
    var backboneWeight: Int = 60,
    var wingWeight: Int = 75,
    var mobilityWeight: Int = 25,
    var enforceZeroLoss: Boolean = true
) {
    private var nodesCount: Long = 0L
    private var deadlineTimeMs: Long = 0L
    private var isTimeExhausted: Boolean = false

    // Transposition Table (TT) with 524,288 entries (~2^19)
    private class TTEntry(
        var hash: Long = 0L,
        var depth: Int = 0,
        var score: Int = 0,
        var flag: Byte = 0, // 0 = EXACT, 1 = LOWER_BOUND, 2 = UPPER_BOUND
        var bestMove: GutiMove? = null
    )

    private val TT_SIZE = 1 shl 19
    private val TT_MASK = TT_SIZE - 1
    private val transpositionTable = Array(TT_SIZE) { TTEntry() }

    // Precomputed Zobrist table for fast 64-bit board hashing
    private val zobristTable: Array<LongArray> = Array(SholoGutiBoard.TOTAL_NODES) {
        LongArray(3)
    }

    init {
        val rng = java.util.Random(16_37_2026L)
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            for (p in 0..2) {
                zobristTable[i][p] = rng.nextLong()
            }
        }
    }

    private fun computeBoardHash(board: SholoGutiBoard): Long {
        var h = 0L
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val pIdx = when (board.pieces[i]) {
                Piece.EMPTY -> 0
                Piece.BLUE -> 1
                Piece.RED -> 2
            }
            h = h xor zobristTable[i][pIdx]
        }
        return h
    }

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

            // Immediate initial candidate for instant hint overlay before deep search completes
            val initialSafeMove = rootMoves.firstOrNull { isMoveSafeFromImmediateLoss(board, it) } ?: overallBestMove
            overallBestMove = initialSafeMove
            try {
                onProgress?.invoke(1, 1L, overallBestMove)
            } catch (_: Throwable) {}

            // Iterative Deepening with full time utilization across 10-second window
            for (depth in 1..maxDepth) {
                if (System.currentTimeMillis() >= deadlineTimeMs) break

                var currentDepthBestMove: GutiMove? = null
                var currentDepthBestScore = -INFINITE_SCORE
                var currentAlpha = -INFINITE_SCORE
                val beta = INFINITE_SCORE

                // Grandmaster Move Ordering: TT Hash move -> Multi-captures -> Captures -> Center -> Wings -> Shielded
                val sortedMoves = orderMoves(board, rootMoves, overallBestMove)

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

        val boardHash = computeBoardHash(board)
        val ttIndex = (boardHash.toInt() and TT_MASK)
        val ttEntry = transpositionTable[ttIndex]

        // Transposition Table Lookup
        if (ttEntry.hash == boardHash && ttEntry.depth >= depth) {
            when (ttEntry.flag) {
                FLAG_EXACT -> return ttEntry.score
                FLAG_LOWER -> if (ttEntry.score >= beta) return ttEntry.score
                FLAG_UPPER -> if (ttEntry.score <= alpha) return ttEntry.score
            }
        }

        val player = if (isMaximizing) Piece.BLUE else Piece.RED
        val moves = board.generateMoves(player)

        // Strict Winning Condition: If player has no moves or no pieces left
        if (moves.isEmpty()) {
            return if (isMaximizing) -INFINITE_SCORE + (32 - depth) else INFINITE_SCORE - (32 - depth)
        }

        if (depth <= 0) {
            return quiescence(board, alpha, beta, isMaximizing, qDepth = 4)
        }

        var currentAlpha = alpha
        var currentBeta = beta
        var bestScore = if (isMaximizing) -INFINITE_SCORE else INFINITE_SCORE
        var bestMoveFound: GutiMove? = null

        val ttBestMove = if (ttEntry.hash == boardHash) ttEntry.bestMove else null
        val sortedMoves = orderMoves(board, moves, ttBestMove)

        if (isMaximizing) {
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, false)
                board.unmakeMove(move, captured)

                if (evaluation > bestScore) {
                    bestScore = evaluation
                    bestMoveFound = move
                }
                currentAlpha = maxOf(currentAlpha, evaluation)
                if (currentBeta <= currentAlpha) break
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }
            }
        } else {
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, true)
                board.unmakeMove(move, captured)

                if (evaluation < bestScore) {
                    bestScore = evaluation
                    bestMoveFound = move
                }
                currentBeta = minOf(currentBeta, evaluation)
                if (currentBeta <= currentAlpha) break
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }
            }
        }

        // Store result in Transposition Table
        if (!isTimeExhausted) {
            val flag: Byte = when {
                bestScore <= alpha -> FLAG_UPPER
                bestScore >= beta -> FLAG_LOWER
                else -> FLAG_EXACT
            }
            ttEntry.hash = boardHash
            ttEntry.depth = depth
            ttEntry.score = bestScore
            ttEntry.flag = flag
            ttEntry.bestMove = bestMoveFound
        }

        return bestScore
    }

    /**
     * Quiescence search resolving capture chains and preventing horizon blunder effect.
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

            val sortedCaptures = captureMoves.sortedByDescending { it.totalCaptures }
            for (move in sortedCaptures) {
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

            val sortedCaptures = captureMoves.sortedByDescending { it.totalCaptures }
            for (move in sortedCaptures) {
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

    /**
     * Grandmaster Move Ordering:
     * 1. TT Hash Move (from transposition table)
     * 2. Multi-step chain captures (totalCaptures >= 2)
     * 3. Single captures
     * 4. Center control moves (Node 18, then inner ring)
     * 5. Side corner / wing tactical moves
     * 6. Quiet step moves
     */
    private fun orderMoves(
        board: SholoGutiBoard,
        moves: List<GutiMove>,
        ttBestMove: GutiMove?
    ): List<GutiMove> {
        return moves.sortedWith { m1, m2 ->
            // 1. Hash move priority
            if (m1 == ttBestMove) return@sortedWith -1
            if (m2 == ttBestMove) return@sortedWith 1

            // 2. Capture Priority & Multi-step chain captures
            if (m1.isCapture != m2.isCapture) {
                return@sortedWith if (m1.isCapture) -1 else 1
            }
            if (m1.isCapture && m2.isCapture) {
                if (m1.totalCaptures != m2.totalCaptures) {
                    return@sortedWith m2.totalCaptures.compareTo(m1.totalCaptures)
                }
            }

            // 3. Center Control Priority (Node 18 highest, then inner ring)
            val m1CenterScore = getCenterScoreForNode(m1.to)
            val m2CenterScore = getCenterScoreForNode(m2.to)
            if (m1CenterScore != m2CenterScore) {
                return@sortedWith m2CenterScore.compareTo(m1CenterScore)
            }

            // 4. Side Wing / Corner Priority
            val m1Wing = isSideWingOrCorner(m1.to)
            val m2Wing = isSideWingOrCorner(m2.to)
            if (m1Wing != m2Wing) {
                return@sortedWith if (m1Wing) -1 else 1
            }

            0
        }
    }

    private fun getCenterScoreForNode(node: Int): Int {
        return when (node) {
            18 -> 100 // Board Center Pivot
            12, 13, 14, 17, 19, 22, 23, 24 -> 50 // Inner Diamond Ring
            1, 4, 8, 28, 32, 35 -> 30 // Backbone Spine
            else -> 0
        }
    }

    private fun isSideWingOrCorner(node: Int): Boolean {
        return when (node) {
            0, 2, 3, 5, 31, 33, 34, 36 -> true // Triangle Wings
            6, 10, 26, 30 -> true // 5x5 Grid Corners
            8, 28 -> true // Apex Gateways
            11, 15, 16, 20, 21, 25 -> true // Side Flanks
            else -> false
        }
    }

    fun isMoveSafeFromImmediateLoss(board: SholoGutiBoard, blueMove: GutiMove): Boolean {
        return try {
            val captured = board.makeMove(blueMove)
            val opponentCounterMoves = board.generateMoves(Piece.RED)
            val hasImmediateCounterCapture = opponentCounterMoves.any { counter ->
                counter.isCapture && (counter.captured == blueMove.to || blueMove.allCaptured.contains(counter.captured))
            }
            board.unmakeMove(blueMove, captured)
            if (blueMove.isCapture) true else !hasImmediateCounterCapture
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Comprehensive Grandmaster Heuristic Evaluation:
     * - Material Balance: Base piece counts (weighted heavily)
     * - Center Control & Backbone: Dominating central 5x5 nodes and spine
     * - Side Corner & Wing Tactical Bonus: High bonus for controlling side triangle wings and corner gateways
     * - Defensive Backing & Shielding: Formations safe from jumps with friendly pieces behind
     * - Mobility & Suffocation: Rewarding legal move freedom and penalizing trapped enemy pieces
     * - Tactical Baiting & Trapping: Favorable exchanges and capture threats
     */
    fun evaluate(board: SholoGutiBoard): Int {
        val bluePieces = board.countPieces(Piece.BLUE)
        val redPieces = board.countPieces(Piece.RED)

        // Strict Winning Conditions
        if (bluePieces == 0) return -INFINITE_SCORE
        if (redPieces == 0) return INFINITE_SCORE

        val blueMoves = board.generateMoves(Piece.BLUE)
        val redMoves = board.generateMoves(Piece.RED)

        if (blueMoves.isEmpty()) return -INFINITE_SCORE
        if (redMoves.isEmpty()) return INFINITE_SCORE

        // 1. Material Dominance (1200 per piece)
        var materialScore = (bluePieces - redPieces) * 1200

        // 2. Center Control Evaluation
        val centerScore = evaluateCenterControl(board)

        // 3. Side Corner & Wing Tactical Bonus
        val wingScore = evaluateSideWingsAndCorners(board)

        // 4. Defensive Backing & Shielding Evaluation
        val defenseScore = evaluateDefensiveShielding(board)

        // 5. Mobility & Trapping Evaluation
        val mobilityScore = (blueMoves.size - redMoves.size) * mobilityWeight

        // 6. Chain Jump & Threat Evaluation
        var threatScore = 0
        if (blueMoves.any { it.isCapture }) {
            val maxBlueChain = blueMoves.filter { it.isCapture }.maxOfOrNull { it.totalCaptures } ?: 1
            threatScore += maxBlueChain * 350
        }
        if (redMoves.any { it.isCapture }) {
            val maxRedChain = redMoves.filter { it.isCapture }.maxOfOrNull { it.totalCaptures } ?: 1
            threatScore -= maxRedChain * 350
        }

        return materialScore + centerScore + wingScore + defenseScore + mobilityScore + threatScore
    }

    /**
     * Evaluates Center Control across the 37-node graph:
     * - Node 18: Strategic Pivot of 16 Ghuti (connected in 8 directions)
     * - Inner Ring (12, 13, 14, 17, 19, 22, 23, 24): Maximum attack & escape angles
     * - Central Backbone (1, 4, 8, 13, 18, 23, 28, 32, 35): Spine dividing the board
     */
    private fun evaluateCenterControl(board: SholoGutiBoard): Int {
        var score = 0

        // Absolute Center: Node 18
        when (board.pieces[18]) {
            Piece.BLUE -> score += 240
            Piece.RED -> score -= 240
            Piece.EMPTY -> {}
        }

        // Inner 3x3 Diamond Ring
        val innerRing = intArrayOf(12, 13, 14, 17, 19, 22, 23, 24)
        for (node in innerRing) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 95
                Piece.RED -> score -= 95
                Piece.EMPTY -> {}
            }
        }

        // Central Vertical Backbone
        val backbone = intArrayOf(1, 4, 8, 13, 18, 23, 28, 32, 35)
        for (node in backbone) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 45
                Piece.RED -> score -= 45
                Piece.EMPTY -> {}
            }
        }

        return score
    }

    /**
     * Evaluates Side Corner & Wing Tactical Bonus:
     * - Side Triangle Wing Nodes (0, 2, 3, 5, 31, 33, 34, 36):
     *   Crucial tactical gateways to trap opponents or flank the main grid.
     *   Receives bonus when occupied, with extra boost if supported by adjacent friendly pieces.
     * - 5x5 Grid Corner Gateways (6, 10, 26, 30) & Apex Gateways (8, 28):
     *   Chokepoints connecting the triangles to the main grid.
     */
    private fun evaluateSideWingsAndCorners(board: SholoGutiBoard): Int {
        var score = 0

        // Side Triangle Wing Nodes
        val triangleWings = intArrayOf(0, 2, 3, 5, 31, 33, 34, 36)
        for (node in triangleWings) {
            val p = board.pieces[node]
            if (p == Piece.BLUE) {
                val hasFriendlySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.BLUE }
                score += if (hasFriendlySupporter) wingWeight + 45 else wingWeight
            } else if (p == Piece.RED) {
                val hasFriendlySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.RED }
                score -= if (hasFriendlySupporter) wingWeight + 45 else wingWeight
            }
        }

        // Grid Corners & Apex Gateways
        val gateways = intArrayOf(6, 10, 26, 30, 8, 28)
        for (node in gateways) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 80
                Piece.RED -> score -= 80
                Piece.EMPTY -> {}
            }
        }

        // Side Flanks
        val flanks = intArrayOf(11, 15, 16, 20, 21, 25)
        for (node in flanks) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 40
                Piece.RED -> score -= 40
                Piece.EMPTY -> {}
            }
        }

        return score
    }

    /**
     * Evaluates Defensive Backing & Shielding Strategy:
     * In 16 Ghuti, a piece at node 'over' CANNOT be jumped by an opponent at node 'from'
     * if the landing node 'to' directly behind it is occupied by a friendly piece.
     * - Backed pieces with friendly support behind them receive a shielding bonus (+60).
     * - Vulnerable pieces with an empty landing square behind them exposed to enemy attack receive a heavy penalty (-180).
     * - Clustered formations with adjacent friendly neighbors receive mutual protection bonus.
     */
    private fun evaluateDefensiveShielding(board: SholoGutiBoard): Int {
        var score = 0

        // Check jump lines for shielding and direct exposure
        for (rule in SholoGutiBoard.JUMP_RULES) {
            val pFrom = board.pieces[rule.from]
            val pOver = board.pieces[rule.over]
            val pTo = board.pieces[rule.to]

            // Blue piece in the middle
            if (pOver == Piece.BLUE) {
                if (pTo == Piece.BLUE) {
                    // Shielded! Enemy cannot jump Blue from 'from' because 'to' is blocked by Blue
                    score += 60
                } else if (pTo == Piece.EMPTY && pFrom == Piece.RED) {
                    // Exposed! Red can directly jump Blue from 'from' to 'to'
                    score -= 180
                }
            }
            // Red piece in the middle
            else if (pOver == Piece.RED) {
                if (pTo == Piece.RED) {
                    // Red is shielded
                    score -= 60
                } else if (pTo == Piece.EMPTY && pFrom == Piece.BLUE) {
                    // Exposed Red piece! Blue can jump Red
                    score += 180
                }
            }
        }

        // Formation bonus: count adjacent friendly neighbors for mutual cluster protection
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            if (p == Piece.BLUE) {
                val friendlyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.BLUE }
                score += friendlyNeighbors * 25
            } else if (p == Piece.RED) {
                val enemyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.RED }
                score -= enemyNeighbors * 25
            }
        }

        return score
    }

    private fun buildStatusMessage(move: GutiMove, isSafe: Boolean, depth: Int): String {
        return buildString {
            if (move.isChainCapture) {
                append("⚔️ MULTI-JUMP CHAIN (${move.totalCaptures} pieces!): ")
            } else if (move.isCapture) {
                append("⚔️ JUMP CAPTURE node ${move.captured}: ")
            } else if (move.to == 18) {
                append("👑 Center Control Pivot: ")
            } else if (isSideWingOrCorner(move.to)) {
                append("🦅 Side Wing Advantage: ")
            } else {
                append("Strategic Advance: ")
            }

            append("${move.from} → ${move.to}")
            if (move.chainSequence.isNotEmpty()) {
                append(" (via ${move.chainSequence.joinToString("→")})")
            }

            if (isSafe) {
                append(" (Grandmaster d=$depth ✓)")
            } else {
                append(" (Tactical Strike, d=$depth)")
            }
        }
    }

    companion object {
        const val INFINITE_SCORE = 1_000_000
        const val SUICIDAL_PENALTY = 500_000
        const val DEFAULT_TIME_LIMIT_MS = 10_000L

        private const val FLAG_EXACT: Byte = 0
        private const val FLAG_LOWER: Byte = 1
        private const val FLAG_UPPER: Byte = 2
    }
}
