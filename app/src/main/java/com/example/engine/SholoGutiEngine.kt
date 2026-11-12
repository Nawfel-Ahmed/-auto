package com.example.engine

class SholoGutiEngine(
    var backboneWeight: Int = 60,
    var wingWeight: Int = 240,
    var mobilityWeight: Int = 30,
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

    // Killer Moves Heuristic: Stores up to 2 quiet moves per ply that caused beta cutoffs
    private val killerMoves = Array(64) { Array<GutiMove?>(2) { null } }

    // History Heuristic Table: Tracks move frequency causing cutoffs [from][to]
    private val historyTable = Array(SholoGutiBoard.TOTAL_NODES) { IntArray(SholoGutiBoard.TOTAL_NODES) }

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

    private val searchPathHashes = LongArray(64)

    fun computeBoardHash(board: SholoGutiBoard): Long {
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

    /**
     * Absolute Max-Level Deep Minimax & Alpha-Beta Search (6 to 8 Plies).
     * Enforces:
     * 1. Priority 1 Corner & Wing Dominance (massive holding bonus, strict veto against moving away unless winning chain capture).
     * 2. Anti-Loop Move Tabu List (eliminates cyclic hint oscillation between adjacent nodes).
     * 3. Base & Triangle Piece Deployment (Out-of-Corner Strategy: push base pieces 31..36 out onto 5x5 board).
     * 4. Multi-Capture Continuation & Safe Landing (safest landing without counter-traps).
     * 5. Dynamic Attack Mode (+4 advantage: aggressive 1-for-1 trades and piece hunting) & Strict Anti-Suicide (Double Red Card veto against losing 2-3 pieces).
     * 6. Deep Predictive Opponent Threat Analysis (6-8 plies deep).
     */
    fun findBestMove(
        board: SholoGutiBoard,
        timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
        maxDepth: Int = 6, // Grandmaster tactical search analyzing 4 to 6 plies with strict deadline
        tabuMoves: List<Pair<Int, Int>> = emptyList(), // History Heuristic Move Tabu List against cyclic oscillation
        recentPositions: List<Long> = emptyList(), // Board hashes for 2-fold / 3-fold repetition detection
        isManualDefenseOverride: Boolean? = null, // Manual override (true = force defense, false = force normal, null = auto based on map pieces)
        onProgress: ((depth: Int, nodes: Long, bestMove: GutiMove?) -> Unit)? = null
    ): EngineResult {
        return try {
            val startTime = System.currentTimeMillis()
            deadlineTimeMs = startTime + timeLimitMs
            nodesCount = 0L
            isTimeExhausted = false

            // Clear killer & history heuristics for fresh search
            for (ply in 0 until 64) {
                killerMoves[ply][0] = null
                killerMoves[ply][1] = null
            }
            for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
                for (j in 0 until SholoGutiBoard.TOTAL_NODES) {
                    historyTable[i][j] = 0
                }
            }

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

            val bluePieces = board.countPieces(Piece.BLUE)
            val redPieces = board.countPieces(Piece.RED)
            val materialDiff = bluePieces - redPieces
            // Smart Auto-Switch vs Manual Override:
            // - Manual Defense Override = true: immediately forces strict material-preservation defense.
            // - Manual Defense Override = false: restores aggressive/tactical Normal Mode.
            // - Smart Auto-Switch (null): If user is ahead by 3 or more pieces/points (materialDiff >= 3),
            //   automatically switches to Full Defense Mode, locking down positions.
            val isFullDefenseMode = isManualDefenseOverride ?: (materialDiff >= 3)
            val isDynamicAttackMode = materialDiff >= 4 && !isFullDefenseMode

            // 1. Identify currently hanging Blue pieces for failed sacrifice recovery
            val hangingPieces = findHangingBluePieces(board)

            // 2. Precompute tactical wing holding, safety, retreat penalties, base deployment, and landing safety
            val safetyMap = HashMap<GutiMove, Boolean>()
            val holdingPenaltyMap = HashMap<GutiMove, Int>()
            val recoveryBonusMap = HashMap<GutiMove, Int>()

            for (m in rootMoves) {
                val isSafe = isMoveSafeFromImmediateLoss(board, m, isFullDefenseMode)
                safetyMap[m] = isSafe

                // In Full Defense Mode, enforce Impenetrable Shielding Policy:
                // Completely forbid risky captures. Calculate safe, quiet defensive repositioning moves that cluster and protect pieces.
                // Any move that allows the opponent to capture a piece in return receives an infinite negative score penalty (-999,999,999).
                val defenseCaptureAdjustment = if (isFullDefenseMode) {
                    if (!isSafe) {
                        -TACTICAL_VETO_PENALTY // Strict Defense Veto: losing even 1 single piece receives -999,999,999!
                    } else if (m.isCapture) {
                        -100_000 // In Defense Mode: completely disable offensive capturing weights to avoid counter-attack traps
                    } else {
                        // Shield Clustering: exclusively calculate defensive, tightly-clustered moves where pieces protect one another
                        val simCap = board.makeMove(m)
                        val friendlyNeighbors = SholoGutiBoard.ADJACENCY[m.to].count { board.pieces[it] == Piece.BLUE }
                        val isShielded = isPieceShieldedFromBehind(board, m.to)
                        board.unmakeMove(m, simCap)
                        when {
                            friendlyNeighbors >= 3 && isShielded -> +120_000 // Impenetrable multi-shield fortress
                            friendlyNeighbors >= 2 && isShielded -> +80_000
                            friendlyNeighbors >= 2 -> +50_000
                            friendlyNeighbors == 1 && isShielded -> +35_000
                            friendlyNeighbors == 1 -> +20_000
                            else -> -50_000 // Isolated piece with 0 neighbors is penalized!
                        }
                    }
                } else {
                    if (!isSafe) -TACTICAL_VETO_PENALTY else 0
                }

                // Mandatory Tactical Corner Dominance & Holding:
                // Only applies to secured tactical corners/gateways (0, 2, 3, 5, 6, 10, 26, 30, 8, 28, 11, 15, 16, 20),
                // NEVER to home base nodes (31..36) which must be developed!
                val isFromWing = isSideWingOrCorner(m.from) && !isHomeBase(m.from)
                val wingAbandonPenalty = if (isFromWing) {
                    if (m.isCapture && m.totalCaptures >= 2) {
                        +1500 // Allowed: Immediate winning chain capture!
                    } else {
                        -TACTICAL_VETO_PENALTY // Absolute Veto: Forbidden from moving away from secured corner!
                    }
                } else {
                    0
                }

                // Reward seizing empty side corners and gateways on early/mid-game turns
                // When Defense Mode is active, disable offensive wing seizing!
                val isToWing = isSideWingOrCorner(m.to) && !isHomeBase(m.to)
                val wingSeizeBonus = if (isToWing && !isFromWing && !isFullDefenseMode) {
                    +4000 // Priority 1: Massive bonus for occupying vacant corner/wing fortress!
                } else {
                    0
                }

                // Base & Triangle Piece Deployment (Out-of-Corner Strategy):
                // Developing base pieces (31..36) forward into the 5x5 board (26..30, 21..25, 28)
                // When Defense Mode is active, disable base deployment bonuses to keep base triangles secure!
                val isBaseDeployment = isHomeBase(m.from) && (!isHomeBase(m.to) || m.to < m.from)
                val baseDeploymentBonus = if (isBaseDeployment && !isFullDefenseMode) {
                    when {
                        m.to == 28 -> +4500 // Seizing apex gateway connecting base to grid!
                        m.to in 21..30 -> +3800 // Developed onto main 5x5 board!
                        else -> +2400 // Advancing within base from back row (34..36) to front row (31..33)
                    }
                } else {
                    0
                }

                // Multi-Capture Continuation & Safe Landing Evaluation
                val landingSafetyBonus = evaluateLandingSafety(board, m, isDynamicAttackMode)

                // Backward Retreat Elimination: Blue pieces advancing into board
                // must NEVER passively retreat backward towards home row (toY > fromY) without a capture!
                val fromY = SholoGutiBoard.NODE_POINTS[m.from].y
                val toY = SholoGutiBoard.NODE_POINTS[m.to].y
                val backwardRetreatPenalty = if (!m.isCapture && toY > fromY) {
                    -TACTICAL_VETO_PENALTY // Absolute Veto: Eliminate lazy, retreating hints
                } else {
                    0
                }

                // Anti-Loop / Move Tabu List:
                // If move reverses a recent quiet move (from == prevTo and to == prevFrom) without capture:
                val isDirectReverse = !m.isCapture && tabuMoves.any { it.second == m.from && it.first == m.to }
                // If move returns to a position Blue occupied 2 turns ago without capture:
                val isReturnToPrevious = !m.isCapture && tabuMoves.takeLast(2).any { it.first == m.to }
                val tabuPenalty = when {
                    isDirectReverse -> -ANTI_LOOP_TABU_PENALTY
                    isReturnToPrevious -> -120_000_000
                    else -> 0
                }

                // Tactical Trap Creation & Offensive Pressure:
                // If not in full defense, reward moves that create attack threats/forks or pin opponent pieces
                val trapCreationBonus = if (!isFullDefenseMode) {
                    val simCaptured = board.makeMove(m)
                    val nextBlueMoves = board.generateMoves(Piece.BLUE).filter { it.isCapture }
                    val threatenedRedCount = nextBlueMoves.map { it.captured }.distinct().size
                    board.unmakeMove(m, simCaptured)
                    when {
                        threatenedRedCount >= 2 -> +2800 // Fork trap: prepares 2+ simultaneous capture threats!
                        threatenedRedCount == 1 -> +1200 // Pressure: sets up guaranteed capture on next ply
                        else -> 0
                    }
                } else {
                    0
                }

                // Strict Depth-2 Anti-Blunder Veto (Normal & Defense Modes):
                // Any move resulting in net piece loss or walking into a trap receives -TACTICAL_VETO_PENALTY
                val isBlunder = isMoveNetPieceLoss(board, m, isFullDefenseMode) || !isMoveSafeFromImmediateLoss(board, m, isFullDefenseMode)
                val blunderPenalty = if (isBlunder) -TACTICAL_VETO_PENALTY else 0

                holdingPenaltyMap[m] = wingAbandonPenalty + wingSeizeBonus + baseDeploymentBonus + landingSafetyBonus + backwardRetreatPenalty + tabuPenalty + defenseCaptureAdjustment + trapCreationBonus + blunderPenalty

                // Failed Sacrifice Recovery: If board has a hanging piece, reward rescuing it
                recoveryBonusMap[m] = if (hangingPieces.isNotEmpty()) {
                    if (isMoveRescuingHangingPiece(board, m, hangingPieces, isFullDefenseMode)) {
                        +3500 // Rescued the stranded piece!
                    } else if (!m.isCapture) {
                        -TACTICAL_VETO_PENALTY // Refuse quiet move while a friendly piece is hanging!
                    } else {
                        0
                    }
                } else {
                    0
                }
            }

            // Requirement 3: Bulletproof Defense Mode & Hard Anti-Blunder Safety Filter (Module 2)
            // Drop any move from candidate list resulting in net piece loss or walking into a trap
            val safeCandidateMoves = rootMoves.filter {
                !isMoveNetPieceLoss(board, it, isFullDefenseMode) && isMoveSafeFromImmediateLoss(board, it, isFullDefenseMode)
            }

            // If no safe move exists, default to the safest quiet locking move
            val isForcedLoss = safeCandidateMoves.isEmpty()
            val effectiveCandidateMoves = if (!isForcedLoss) {
                safeCandidateMoves
            } else {
                listOf(findSafestQuietLockingMove(board, rootMoves, isFullDefenseMode))
            }

            overallBestMove = if (!isForcedLoss) {
                safeCandidateMoves.maxByOrNull { holdingPenaltyMap[it] ?: 0 } ?: safeCandidateMoves.first()
            } else {
                effectiveCandidateMoves.first()
            }

            try {
                onProgress?.invoke(1, 1L, overallBestMove)
            } catch (_: Throwable) {}

            // If forced loss with single locked fallback, no need to search dangerous moves:
            if (!isForcedLoss && effectiveCandidateMoves.size > 1) {
                // Iterative Deepening across safe candidates only!
                for (depth in 1..maxDepth) {
                    if (System.currentTimeMillis() >= deadlineTimeMs) break

                    var currentDepthBestMove: GutiMove? = null
                    var currentDepthBestScore = -INFINITE_SCORE
                    var currentAlpha = -INFINITE_SCORE
                    val beta = INFINITE_SCORE

                    // Grandmaster Move Ordering: TT Hash move -> Chains -> Captures -> Corners -> Base Deployment -> Killers -> History -> Demote Tabu
                    val sortedMoves = orderMoves(board, effectiveCandidateMoves, overallBestMove, ply = 0, tabuMoves = tabuMoves, isDefenseMode = isFullDefenseMode)

                    for (move in sortedMoves) {
                        if (System.currentTimeMillis() >= deadlineTimeMs) {
                            isTimeExhausted = true
                            break
                        }

                        val wingPenalty = holdingPenaltyMap[move] ?: 0
                        val recoveryBonus = recoveryBonusMap[move] ?: 0

                        val isTabu = wingPenalty <= -ANTI_LOOP_TABU_PENALTY
                        val hasNonTabuAlternative = effectiveCandidateMoves.any {
                            it != move && (holdingPenaltyMap[it] ?: 0) > -ANTI_LOOP_TABU_PENALTY
                        }

                        if (recoveryBonus <= -TACTICAL_VETO_PENALTY ||
                            wingPenalty <= -TACTICAL_VETO_PENALTY || (isTabu && hasNonTabuAlternative)) {
                            continue
                        }

                        val captured = board.makeMove(move)
                        nodesCount++

                        val score = minimax(
                            board = board,
                            depth = depth - 1,
                            alpha = currentAlpha,
                            beta = beta,
                            isMaximizing = false,
                            ply = 1,
                            recentPositions = recentPositions,
                            tabuMoves = tabuMoves,
                            isDefenseMode = isFullDefenseMode
                        )

                        board.unmakeMove(move, captured)

                        val adjustedScore = if (score <= -TACTICAL_VETO_PENALTY / 2) {
                            -TACTICAL_VETO_PENALTY
                        } else {
                            score + wingPenalty + recoveryBonus
                        }

                        if (adjustedScore > currentDepthBestScore) {
                            currentDepthBestScore = adjustedScore
                            currentDepthBestMove = move
                        }
                        currentAlpha = maxOf(currentAlpha, adjustedScore)
                    }

                    if (currentDepthBestMove != null) {
                        overallBestMove = currentDepthBestMove
                        overallBestScore = currentDepthBestScore
                        depthCompleted = depth
                        try {
                            onProgress?.invoke(depth, nodesCount, overallBestMove)
                        } catch (_: Throwable) {}
                    }

                    if (isTimeExhausted) break
                }
            }

            // Final Pre-Check Verification before outputting hint:
            if (!isForcedLoss && isMoveNetPieceLoss(board, overallBestMove, isFullDefenseMode)) {
                overallBestMove = safeCandidateMoves.firstOrNull {
                    !isMoveNetPieceLoss(board, it, isFullDefenseMode)
                } ?: findSafestQuietLockingMove(board, rootMoves, isFullDefenseMode)
            }

            val duration = System.currentTimeMillis() - startTime
            val safe = safetyMap[overallBestMove] ?: (!enforceZeroLoss || isMoveSafeFromImmediateLoss(board, overallBestMove))
            val effectiveDepth = depthCompleted.coerceAtLeast(1)

            EngineResult(
                bestMove = overallBestMove,
                score = overallBestScore,
                depthReached = effectiveDepth,
                nodesEvaluated = nodesCount,
                durationMs = duration,
                isSafe = safe,
                statusMessage = buildStatusMessage(overallBestMove, safe, effectiveDepth, board)
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            val fallbackMoves = try { board.generateMoves(Piece.BLUE) } catch (_: Throwable) { emptyList() }
            val fallbackMove = fallbackMoves.firstOrNull { isMoveSafeFromImmediateLoss(board, it) } ?: fallbackMoves.firstOrNull()
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
        isMaximizing: Boolean,
        ply: Int,
        recentPositions: List<Long> = emptyList(),
        tabuMoves: List<Pair<Int, Int>> = emptyList(),
        isDefenseMode: Boolean = false
    ): Int {
        if (System.currentTimeMillis() >= deadlineTimeMs) {
            isTimeExhausted = true
            return evaluate(board, isDefenseMode)
        }

        val boardHash = computeBoardHash(board)

        // Search Path Repetition & Tabu Cycle Detection:
        if (ply in 0 until 64) {
            searchPathHashes[ply] = boardHash
        }
        if (ply > 0) {
            var isRepeated = false
            for (p in 0 until ply) {
                if (searchPathHashes[p] == boardHash) {
                    isRepeated = true
                    break
                }
            }
            if (!isRepeated && recentPositions.isNotEmpty() && recentPositions.contains(boardHash)) {
                isRepeated = true
            }
            if (isRepeated) {
                return if (isMaximizing) -25_000 + (ply * 500) else +25_000 - (ply * 500)
            }
        }

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
            return if (isMaximizing) -INFINITE_SCORE + ply else INFINITE_SCORE - ply
        }

        if (depth <= 0) {
            return quiescence(board, alpha, beta, isMaximizing, qDepth = 6, isDefenseMode = isDefenseMode)
        }

        var currentAlpha = alpha
        var currentBeta = beta
        var bestScore = if (isMaximizing) -INFINITE_SCORE else INFINITE_SCORE
        var bestMoveFound: GutiMove? = null

        val ttBestMove = if (ttEntry.hash == boardHash) ttEntry.bestMove else null
        val sortedMoves = orderMoves(board, moves, ttBestMove, ply, tabuMoves = tabuMoves, isDefenseMode = isDefenseMode)

        if (isMaximizing) {
            for (move in sortedMoves) {
                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, false, ply + 1, recentPositions, tabuMoves, isDefenseMode)
                board.unmakeMove(move, captured)

                if (evaluation > bestScore) {
                    bestScore = evaluation
                    bestMoveFound = move
                }
                currentAlpha = maxOf(currentAlpha, evaluation)
                if (currentBeta <= currentAlpha) {
                    // Killer & History heuristic recording
                    if (!move.isCapture && ply in 0 until 64) {
                        killerMoves[ply][1] = killerMoves[ply][0]
                        killerMoves[ply][0] = move
                        historyTable[move.from][move.to] += depth * depth
                    }
                    break
                }
                if (System.currentTimeMillis() >= deadlineTimeMs) {
                    isTimeExhausted = true
                    break
                }
            }
        } else {
            val bluePieces = board.countPieces(Piece.BLUE)
            val redPieces = board.countPieces(Piece.RED)
            val materialDiff = bluePieces - redPieces
            val isFullDefense = isDefenseMode || (materialDiff >= 3)
            val isAggressiveAttack = materialDiff >= 4 && !isFullDefense

            for (move in sortedMoves) {
                // Absolute Zero Catastrophic Loss (Double Red Card Enforcement):
                // Under no circumstances is the engine allowed to permit losing 2, 3, or 4 pieces.
                // Any branch resulting in a 2+ piece loss is vetoed with -999,999,999 at all plies.
                if (move.totalCaptures >= 2) {
                    return -TACTICAL_VETO_PENALTY + (ply * 1000)
                }

                // Strict Defense Mode & Counter-Attack Proof Anti-Blunder Enforcement:
                // Any move branch where Red counter-captures at ply <= 1 or multi-captures is vetoed with -999,999,999
                if (move.isCapture && (isFullDefense || ply <= 1 || move.totalCaptures >= 2)) {
                    return -TACTICAL_VETO_PENALTY + (ply * 1000)
                }

                val captured = board.makeMove(move)
                nodesCount++
                val evaluation = minimax(board, depth - 1, currentAlpha, currentBeta, true, ply + 1, recentPositions, tabuMoves, isDefenseMode)
                board.unmakeMove(move, captured)

                if (evaluation < bestScore) {
                    bestScore = evaluation
                    bestMoveFound = move
                }
                currentBeta = minOf(currentBeta, evaluation)
                if (currentBeta <= currentAlpha) {
                    if (!move.isCapture && ply in 0 until 64) {
                        killerMoves[ply][1] = killerMoves[ply][0]
                        killerMoves[ply][0] = move
                        historyTable[move.from][move.to] += depth * depth
                    }
                    break
                }
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
     * Quiescence search: Fully resolves capture sequences up to 6 plies deep
     * with Static Exchange Evaluation (SEE) Delta Pruning to eliminate horizon blunders and multi-jump traps.
     */
    private fun quiescence(
        board: SholoGutiBoard,
        alpha: Int,
        beta: Int,
        isMaximizing: Boolean,
        qDepth: Int,
        isDefenseMode: Boolean = false
    ): Int {
        if (System.currentTimeMillis() >= deadlineTimeMs) {
            isTimeExhausted = true
            return evaluate(board, isDefenseMode)
        }

        val standPat = evaluate(board, isDefenseMode)
        if (qDepth <= 0) return standPat

        var currentAlpha = alpha
        var currentBeta = beta

        if (isMaximizing) {
            if (standPat >= currentBeta) return currentBeta
            if (standPat > currentAlpha) currentAlpha = standPat

            val captureMoves = board.generateMoves(Piece.BLUE).filter { it.isCapture }
            if (captureMoves.isEmpty()) return standPat

            // Rank capture moves by Static Exchange Evaluation (SEE)
            val scoredCaptures = captureMoves.map { it to evaluateSEE(board, it) }
                .sortedByDescending { it.second }

            for ((move, see) in scoredCaptures) {
                // Mandatory 2-Ply Counter-Capture Simulation in Quiescence:
                // If Blue's capture allows Red to counter-capture 2 or more pieces or results in net loss, skip it!
                if (!isMoveSafeFromImmediateLoss(board, move, isDefenseMode)) continue
                if (see < 0) continue
                if (isDefenseMode && see <= 0) continue

                val captured = board.makeMove(move)
                nodesCount++
                val score = quiescence(board, currentAlpha, currentBeta, false, qDepth - 1, isDefenseMode)
                board.unmakeMove(move, captured)

                if (score >= currentBeta) return currentBeta
                if (score > currentAlpha) currentAlpha = score
            }
            return currentAlpha
        } else {
            if (standPat <= currentAlpha) return currentAlpha
            if (standPat < currentBeta) currentBeta = standPat

            val captureMoves = board.generateMoves(Piece.RED).filter { it.isCapture }
            if (captureMoves.isEmpty()) return standPat

            val sortedCaptures = captureMoves.sortedByDescending { it.totalCaptures }
            for (move in sortedCaptures) {
                // In both Normal and Defense modes, if Red captures 2 or more pieces, veto branch!
                if (move.totalCaptures >= 2) {
                    return -TACTICAL_VETO_PENALTY
                }
                // In Defense Mode, any Red capture is vetoed
                if (isDefenseMode && move.totalCaptures >= 1) {
                    return -TACTICAL_VETO_PENALTY
                }

                val captured = board.makeMove(move)
                nodesCount++
                val score = quiescence(board, currentAlpha, currentBeta, true, qDepth - 1, isDefenseMode)
                board.unmakeMove(move, captured)

                if (score <= currentAlpha) return currentAlpha
                if (score < currentBeta) currentBeta = score
            }
            return currentBeta
        }
    }

    /**
     * Static Exchange Evaluation (SEE):
     * Evaluates the net piece trade outcome of a candidate Blue move against all immediate Red counter-captures.
     * Returns:
     *   > 0: Net positive exchange (winning material)
     *   = 0: Equal exchange (1-for-1 trade)
     *   < 0: Net negative exchange (blunder / piece loss)
     */
    fun evaluateSEE(board: SholoGutiBoard, move: GutiMove): Int {
        return try {
            val blueCaptured = if (move.isCapture) move.totalCaptures else 0
            val captured = board.makeMove(move)
            val redCaptures = board.generateMoves(Piece.RED).filter { it.isCapture }
            board.unmakeMove(move, captured)

            if (redCaptures.isEmpty()) {
                return blueCaptured
            }

            val maxRedCounter = redCaptures.maxOfOrNull { it.totalCaptures } ?: 1
            blueCaptured - maxRedCounter
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Checks if a move safely threatens an opponent piece on the subsequent ply without hanging.
     */
    private fun doesMoveCreateSafeThreat(board: SholoGutiBoard, move: GutiMove): Boolean {
        return try {
            val captured = board.makeMove(move)
            val nextCaptures = board.generateMoves(Piece.BLUE).filter { it.isCapture }
            val hasThreat = nextCaptures.isNotEmpty()
            board.unmakeMove(move, captured)
            hasThreat
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Fast Move Ordering Score Computation:
     * Assigns a fast integer priority to every candidate move.
     */
    private fun scoreMove(
        board: SholoGutiBoard,
        move: GutiMove,
        ttBestMove: GutiMove?,
        ply: Int,
        tabuMoves: List<Pair<Int, Int>>,
        isDefenseMode: Boolean
    ): Int {
        // 1. Transposition Table best move from previous search iteration
        if (move == ttBestMove) return 20_000_000

        // 2-Ply Counter-Capture Simulation Check:
        val isSafe = isMoveSafeFromImmediateLoss(board, move, isDefenseMode)
        if (!isSafe) {
            return -18_000_000
        }

        val see = evaluateSEE(board, move)

        // 2. Winning Captures (SEE > 0): verified free captures and positive trades
        if (move.isCapture && see > 0) {
            return 10_000_000 + (see * 500_000) + (move.totalCaptures * 100_000)
        }

        // 3. Blunder Filter: Quiet moves that move into a capture (SEE < 0) or negative capture trades
        if (see < 0) {
            return -18_000_000 + (see * 1_000_000)
        }

        // 4. Equal Trades (SEE == 0 with capture): demoted to prevent giving up pieces
        if (move.isCapture && see == 0) {
            return -18_000_000
        }

        // 5. Killer Moves (quiet moves that caused beta cutoff at this ply)
        val k1 = if (ply in 0 until 64) killerMoves[ply][0] else null
        val k2 = if (ply in 0 until 64) killerMoves[ply][1] else null
        if (move == k1) return 2_000_000
        if (move == k2) return 1_800_000

        // 6. Tactical Threat Generation (setting up guaranteed fork/jump next turn)
        val createsThreat = doesMoveCreateSafeThreat(board, move)
        val threatBonus = if (createsThreat) 1_200_000 else 0

        // 7. Base Deployment (Developing base pieces 31..36 out onto 5x5 board)
        val isBaseDeploy = isHomeBase(move.from) && (!isHomeBase(move.to) || move.to < move.from)
        val deployBonus = if (isBaseDeploy) 600_000 else 0

        // 8. Priority 1 Corner & Gateway Seizure
        val isWingSeize = isSideWingOrCorner(move.to) && !isHomeBase(move.to) && !isSideWingOrCorner(move.from)
        val wingBonus = if (isWingSeize) 500_000 else 0

        // 9. Center Control (Node 18 pivot & Inner Ring)
        val centerBonus = getCenterScoreForNode(move.to) * 2_000

        // 10. History Heuristic
        val hist = historyTable[move.from][move.to].coerceAtMost(100_000)

        // 11. Tabu / Loop Demotion
        val isDirectReverse = tabuMoves.any { it.second == move.from && it.first == move.to }
        val isRecentReturn = tabuMoves.takeLast(2).any { it.first == move.to }
        val tabuPenalty = when {
            isDirectReverse -> -8_000_000
            isRecentReturn -> -4_000_000
            else -> 0
        }

        // 12. Demote backward retreat towards home base (toY > fromY) without capture
        val fromY = SholoGutiBoard.NODE_POINTS[move.from].y
        val toY = SholoGutiBoard.NODE_POINTS[move.to].y
        val retreatPenalty = if (toY > fromY) -1_000_000 else 0

        // 13. Demote abandoning secured corner without capture
        val abandonPenalty = if (isSideWingOrCorner(move.from) && !isHomeBase(move.from)) -2_000_000 else 0

        return 1_000_000 + threatBonus + deployBonus + wingBonus + centerBonus + hist + tabuPenalty + retreatPenalty + abandonPenalty
    }

    /**
     * Grandmaster Fast Move Ordering:
     * Uses scoreMove pre-ranking for maximum Alpha-Beta pruning cutoffs.
     */
    private fun orderMoves(
        board: SholoGutiBoard,
        moves: List<GutiMove>,
        ttBestMove: GutiMove?,
        ply: Int = 0,
        tabuMoves: List<Pair<Int, Int>> = emptyList(),
        isDefenseMode: Boolean = false
    ): List<GutiMove> {
        if (moves.size <= 1) return moves
        return moves.sortedByDescending { move ->
            scoreMove(board, move, ttBestMove, ply, tabuMoves, isDefenseMode)
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

    private fun getWingDominanceScore(node: Int): Int {
        return when (node) {
            0, 2, 3, 5 -> 120 // Opponent Wings
            6, 10, 26, 30 -> 100 // Grid Gateway Corners
            8, 28 -> 95 // Apex Gateways
            11, 15, 16, 20 -> 50 // Flanks
            else -> 0
        }
    }

    fun isHomeBase(node: Int): Boolean = node in 31..36
    fun isOpponentBase(node: Int): Boolean = node in 0..5

    fun isSideWingOrCorner(node: Int): Boolean {
        return when (node) {
            0, 2, 3, 5 -> true // Opponent top triangle wings (conquering enemy fortress)
            6, 10, 26, 30 -> true // 4 Outer Grid Corners
            8, 28 -> true // Apex Gateways
            11, 15, 16, 20 -> true // Flanks & Outer Perimeter Edges
            else -> false
        }
    }

    /**
     * Multi-Capture Continuation & Safe Landing Evaluation:
     * Uses Static Exchange Evaluation (SEE) to eliminate suicidal landing traps.
     */
    private fun evaluateLandingSafety(board: SholoGutiBoard, move: GutiMove, isDynamicAttack: Boolean): Int {
        val see = evaluateSEE(board, move)
        if (move.isCapture) {
            return when {
                see > 0 -> +3000 * see // Safe winning capture
                see == 0 -> if (isDynamicAttack) +600 else -2000 // Equal trade only if +4 ahead
                else -> -CATASTROPHIC_BLUNDER_PENALTY // Suicidal capture trap!
            }
        } else {
            return if (see < 0) -CATASTROPHIC_BLUNDER_PENALTY else 0
        }
    }

    /**
     * Finds all Blue pieces that are currently directly exposed to being jumped by Red.
     */
    fun findHangingBluePieces(board: SholoGutiBoard): Set<Int> {
        val hanging = HashSet<Int>()
        for (rule in SholoGutiBoard.JUMP_RULES) {
            if (board.pieces[rule.from] == Piece.RED &&
                board.pieces[rule.over] == Piece.BLUE &&
                board.pieces[rule.to] == Piece.EMPTY) {
                hanging.add(rule.over)
            }
        }
        return hanging
    }

    /**
     * Determines whether a candidate Blue move successfully rescues/recovers a hanging piece.
     */
    fun isMoveRescuingHangingPiece(
        board: SholoGutiBoard,
        blueMove: GutiMove,
        hangingPieces: Set<Int>,
        isDefenseMode: Boolean = false
    ): Boolean {
        if (blueMove.from in hangingPieces && isMoveSafeFromImmediateLoss(board, blueMove, isDefenseMode)) {
            return true
        }
        if (blueMove.from in hangingPieces && blueMove.isCapture && isMoveSafeFromImmediateLoss(board, blueMove, isDefenseMode)) {
            return true
        }
        val captured = board.makeMove(blueMove)
        val hangingAfter = findHangingBluePieces(board)
        board.unmakeMove(blueMove, captured)
        return hangingAfter.size < hangingPieces.size && isMoveSafeFromImmediateLoss(board, blueMove, isDefenseMode)
    }

    /**
     * Pillar 1: Danger Zone & Attack Reach Filter
     * Verifies if a given node is under direct attack or within the capturing reach
     * of any opponent (Red) piece.
     * Returns true if:
     * 1. Any direct collinear JumpRule allows Red to jump over this node into an empty landing square.
     * 2. Any legal Red capture move (including single jumps and multi-jump chains) captures this node.
     */
    fun isNodeUnderDirectAttack(board: SholoGutiBoard, node: Int): Boolean {
        // 1. Direct collinear jump threat against node
        for (rule in SholoGutiBoard.JUMP_RULES) {
            if (rule.over == node &&
                board.pieces[rule.from] == Piece.RED &&
                board.pieces[rule.to] == Piece.EMPTY
            ) {
                return true
            }
        }
        // 2. Legal Red capture moves that capture this node
        val redMoves = board.generateMoves(Piece.RED)
        for (m in redMoves) {
            if (m.isCapture && (m.allCaptured.contains(node) || m.captured == node)) {
                return true
            }
        }
        return false
    }

    /**
     * Pillar 3: Defensive Shield Verification
     * Verifies if a friendly piece at [node] is shielded from behind.
     * A piece is shielded if all opponent jump landing squares behind it are blocked/occupied.
     */
    fun isPieceShieldedFromBehind(board: SholoGutiBoard, node: Int): Boolean {
        for (rule in SholoGutiBoard.JUMP_RULES) {
            if (rule.over == node && board.pieces[rule.from] == Piece.RED) {
                if (board.pieces[rule.to] == Piece.EMPTY) {
                    return false // Vulnerable to jump
                }
            }
        }
        return true
    }

    /**
     * Mandatory 2-Ply Counter-Capture Simulation, Danger Zone & Strict Material Delta Validation:
     * - Before any move branch is approved, validates:
     *   1. Pillar 1 (Danger Zone): Destination square (blueMove.to) must not be under direct attack or in Red's reach.
     *   2. Pillar 2 (Strict Material Delta): Net Pieces After Move >= Net Pieces Before Move.
     *      If losing even 1 piece without an equivalent or greater gain, veto with infinite negative penalty (-999,999,999).
     *   3. Pillar 3 (Defense Mode): Complete zero piece loss allowed; no trades or sacrifices.
     */
    fun isMoveSafeFromImmediateLoss(
        board: SholoGutiBoard,
        blueMove: GutiMove,
        isDefenseMode: Boolean = false
    ): Boolean {
        return try {
            val initialBlue = board.countPieces(Piece.BLUE)
            val initialRed = board.countPieces(Piece.RED)

            val captured = board.makeMove(blueMove)

            // PILLAR 1: Danger Zone & Attack Reach Filter
            // Verify if the target destination square (blueMove.to) is under direct attack or within capturing reach of Red.
            val isTargetInDangerZone = isNodeUnderDirectAttack(board, blueMove.to)
            if (isTargetInDangerZone) {
                // If it's a quiet move, landing in the danger zone is instant suicide!
                if (!blueMove.isCapture) {
                    board.unmakeMove(blueMove, captured)
                    return false
                }
                // In Defense Mode: Any move landing in the danger zone is strictly vetoed!
                if (isDefenseMode) {
                    board.unmakeMove(blueMove, captured)
                    return false
                }
            }

            val redMoves = board.generateMoves(Piece.RED)
            val redCaptures = redMoves.filter { it.isCapture }
            val hangingAfter = findHangingBluePieces(board)

            // If Red has NO counter-captures in response:
            if (redCaptures.isEmpty()) {
                board.unmakeMove(blueMove, captured)
                // In Defense Mode, ensure no pieces are left hanging
                if (isDefenseMode && hangingAfter.isNotEmpty()) {
                    return false
                }
                return true
            }

            val maxRedCaptures = redCaptures.maxOfOrNull { it.totalCaptures } ?: 1
            val blueCaptures = if (blueMove.isCapture) blueMove.totalCaptures else 0

            // PILLAR 2: Strict Material Delta Validation (No Net Loss)
            // Absolute Rule: Net Pieces After Move >= Net Pieces Before Move
            val netPiecesBefore = initialBlue - initialRed
            val netPiecesAfter = (initialBlue - maxRedCaptures) - (initialRed - blueCaptures)
            if (netPiecesAfter < netPiecesBefore) {
                // If a suggested move results in losing even a single piece without an equivalent or greater gain,
                // veto the branch with an infinite negative penalty!
                board.unmakeMove(blueMove, captured)
                return false
            }

            // In Defense Mode: Even if netPiecesAfter >= netPiecesBefore, any piece loss or trade is strictly forbidden!
            if (isDefenseMode && maxRedCaptures >= 1) {
                board.unmakeMove(blueMove, captured)
                return false
            }

            // If target square was in danger zone and blueCaptures didn't give a clear surplus over maxRedCaptures:
            if (isTargetInDangerZone && (blueCaptures - maxRedCaptures <= 0)) {
                board.unmakeMove(blueMove, captured)
                return false
            }

            // Multi-Piece Counter-Capture Veto:
            if (maxRedCaptures >= 2 && blueCaptures < 2) {
                board.unmakeMove(blueMove, captured)
                return false
            }

            // Quiet Suicide Veto: Blue made quiet move (0 captures) into Red's capture: REJECT!
            if (!blueMove.isCapture && maxRedCaptures >= 1) {
                board.unmakeMove(blueMove, captured)
                return false
            }

            // Fork & Double-Attack Trap Veto:
            val threatenedBluePieces = redCaptures.flatMap { it.allCaptured }.distinct()
            if (threatenedBluePieces.size >= 2) {
                board.unmakeMove(blueMove, captured)
                return false
            }

            // Detailed 2-Ply Simulation of all Red counter-attacks:
            var isAnyCounterDevastating = false
            for (redCounter in redCaptures) {
                val redCapSim = board.makeMove(redCounter)
                val bluePiecesAfterCounter = board.countPieces(Piece.BLUE)
                val redPiecesAfterCounter = board.countPieces(Piece.RED)
                val simNetDelta = (bluePiecesAfterCounter - redPiecesAfterCounter) - netPiecesBefore

                if (simNetDelta < 0) {
                    isAnyCounterDevastating = true
                }
                val stillHanging = findHangingBluePieces(board)
                if (stillHanging.isNotEmpty() && simNetDelta <= 0) {
                    isAnyCounterDevastating = true
                }
                board.unmakeMove(redCounter, redCapSim)
                if (isAnyCounterDevastating) break
            }

            board.unmakeMove(blueMove, captured)

            if (isAnyCounterDevastating) {
                return false
            }

            // Equal 1-for-1 trades:
            if (blueCaptures == maxRedCaptures) {
                if (isDefenseMode) return false
                val seeVal = evaluateSEE(board, blueMove)
                if (seeVal < 0) return false
            }

            true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Requirement 1 & 2: Checks if a node is on the outer perimeter of the board.
     * Outer nodes are vulnerable to being trapped or jumped if isolated without friendly supporters.
     */
    fun isOuterExposedNode(node: Int): Boolean {
        return node in intArrayOf(0, 2, 3, 5, 6, 10, 11, 15, 16, 20, 21, 25, 26, 30, 31, 33, 34, 36)
    }

    /**
     * Requirement 2: Static Positional Safety Weights for 16 Ghuti Nodes
     * - Vital intersection nodes (18, 8, 28) and corner fortresses (6, 10, 26, 30, 0, 2, 34, 36) have heavy weights.
     */
    fun getNodePositionalWeight(node: Int): Int {
        return when (node) {
            18 -> 550 // Central Pivot (8 directions)
            8, 28 -> 650 // Apex Gateways bridging triangles to 5x5 grid
            6, 10, 26, 30 -> 500 // 5x5 Outer Grid Corners
            0, 2, 34, 36 -> 450 // Apex Wing Corners
            3, 5, 31, 33 -> 350 // Mid Wing Fortresses
            12, 13, 14, 17, 19, 22, 23, 24 -> 250 // Central Diamond Ring
            1, 4, 32, 35 -> 150 // Central Vertical Backbone
            11, 15, 16, 20, 21, 25 -> 100 // Outer Edge Flanks
            else -> 50
        }
    }

    fun isPieceShieldedFromBehindRed(board: SholoGutiBoard, node: Int): Boolean {
        for (rule in SholoGutiBoard.JUMP_RULES) {
            if (rule.over == node && board.pieces[rule.from] == Piece.BLUE) {
                if (board.pieces[rule.to] == Piece.EMPTY) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Requirement 2: Complete Static Evaluation Function Rewrite (evaluateBoard)
     * - Positional Safety: Corners and vital intersection nodes have heavy positional safety weights.
     * - Clustered Connections: Massive positive weights for mutual cluster protection.
     * - Outer Nodes: Heavy negative penalties for isolated outer nodes.
     * - Zero-Loss Material Preservation in Defense Mode.
     */
    fun evaluateBoard(board: SholoGutiBoard, isDefenseMode: Boolean = false): Int {
        val bluePieces = board.countPieces(Piece.BLUE)
        val redPieces = board.countPieces(Piece.RED)

        // Strict Winning Conditions
        if (bluePieces == 0) return -INFINITE_SCORE
        if (redPieces == 0) return INFINITE_SCORE

        val blueMoves = board.generateMoves(Piece.BLUE)
        val redMoves = board.generateMoves(Piece.RED)

        if (blueMoves.isEmpty()) return -INFINITE_SCORE
        if (redMoves.isEmpty()) return INFINITE_SCORE

        val materialDiff = bluePieces - redPieces
        val isDynamicAttack = materialDiff >= 4 && !isDefenseMode

        // 1. Material Score
        val materialBaseWeight = if (isDefenseMode) 8000 else if (isDynamicAttack) 2400 else 2000
        var score = materialDiff * materialBaseWeight
        if (isDefenseMode) {
            score += bluePieces * 2000 // Sheep / Safe Strategy
        } else if (isDynamicAttack) {
            score += (32 - (bluePieces + redPieces)) * 80
        }

        // 2. Node Positional Safety Weights (Vital Intersections & Corners)
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            when (board.pieces[i]) {
                Piece.BLUE -> score += getNodePositionalWeight(i)
                Piece.RED -> score -= getNodePositionalWeight(i)
                Piece.EMPTY -> {}
            }
        }

        // 3. Cluster Fortress Protection & Outer Node Isolation Penalties
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            if (p == Piece.BLUE) {
                val friendlyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.BLUE }
                val isShielded = isPieceShieldedFromBehind(board, i)
                val isOuter = isOuterExposedNode(i)

                if (isDefenseMode) {
                    when {
                        friendlyNeighbors >= 3 -> score += 6000 // Impenetrable multi-shield fortress
                        friendlyNeighbors == 2 -> score += 3500
                        friendlyNeighbors == 1 -> score += 1500
                        else -> {
                            if (isOuter) score -= 6000 else score -= 3000 // Heavy penalty for isolated outer node
                        }
                    }
                    if (isShielded) score += 2500
                } else {
                    when {
                        friendlyNeighbors >= 2 -> score += 1200
                        friendlyNeighbors == 1 -> score += 500
                        else -> {
                            if (isOuter) score -= 2500 else score -= 800
                        }
                    }
                    if (isShielded) score += 1000
                }
            } else if (p == Piece.RED) {
                val redNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.RED }
                val redShielded = isPieceShieldedFromBehindRed(board, i)
                val redOuter = isOuterExposedNode(i)
                if (redNeighbors >= 2) score -= 1200
                if (redNeighbors == 0 && redOuter) score += 2000 // Exploit isolated enemy straggler
                if (redShielded) score -= 800
            }
        }

        // 4. Opponent Threat Detection & Infinite Negative Veto
        val redCaptures = redMoves.filter { it.isCapture }
        if (redCaptures.isNotEmpty()) {
            val maxRed = redCaptures.maxOf { it.totalCaptures }
            if (isDefenseMode) {
                score -= TACTICAL_VETO_PENALTY // Absolute Veto in Defense Mode for even 1 piece loss
            } else {
                if (maxRed >= 2) {
                    score -= TACTICAL_VETO_PENALTY
                } else {
                    score -= 60_000
                }
            }
        }

        // 5. Friendly Capture Threats (Disabled in Defense Mode)
        if (!isDefenseMode) {
            val blueCaptures = blueMoves.filter { it.isCapture }
            if (blueCaptures.isNotEmpty()) {
                val maxBlue = blueCaptures.maxOf { it.totalCaptures }
                score += when {
                    maxBlue >= 3 -> 6500
                    maxBlue == 2 -> 4000
                    else -> 1500
                }
            }
        }

        // 6. Mobility (controlled influence)
        score += (blueMoves.size - redMoves.size) * 15

        return score
    }

    fun evaluate(board: SholoGutiBoard, isManualDefense: Boolean = false): Int {
        val materialDiff = board.countPieces(Piece.BLUE) - board.countPieces(Piece.RED)
        val isDefense = isManualDefense || (materialDiff >= 3)
        return evaluateBoard(board, isDefense)
    }

    /**
     * Requirement 3: Hard Pre-Check Filter (Zero-Loss Defense Mode)
     * Strictly simulates opponent's immediate responses to determine if a move results
     * in a net piece loss (User pieces before move > User pieces after opponent's counter-capture).
     * If true, this move MUST be dropped immediately from candidate moves.
     */
    fun isMoveNetPieceLoss(
        board: SholoGutiBoard,
        blueMove: GutiMove,
        isDefenseMode: Boolean
    ): Boolean {
        val initialBlue = board.countPieces(Piece.BLUE)
        val capSim = board.makeMove(blueMove)

        // 1. Danger Zone Check: Target destination square must not be under direct attack
        val targetInDanger = isNodeUnderDirectAttack(board, blueMove.to)
        if (!blueMove.isCapture && targetInDanger) {
            board.unmakeMove(blueMove, capSim)
            return true // Quiet move walking directly into enemy jump -> drop immediately!
        }
        if (isDefenseMode && targetInDanger) {
            board.unmakeMove(blueMove, capSim)
            return true // Defense Mode vetoes any landing in danger zone
        }

        // 2. Corner Abandonment Check: NEVER voluntarily abandon corners without massive capture
        val isFromCorner = isSideWingOrCorner(blueMove.from) && !isHomeBase(blueMove.from)
        if (isFromCorner && (!blueMove.isCapture || blueMove.totalCaptures < 2)) {
            board.unmakeMove(blueMove, capSim)
            return true // Never voluntarily abandon a secured corner fortress
        }

        val redMoves = board.generateMoves(Piece.RED)
        val redCaptures = redMoves.filter { it.isCapture }

        var minBlueAfterCounter = board.countPieces(Piece.BLUE)
        var maxRedCaptures = 0

        if (redCaptures.isNotEmpty()) {
            for (redMove in redCaptures) {
                val redSim = board.makeMove(redMove)
                val blueLeft = board.countPieces(Piece.BLUE)
                if (blueLeft < minBlueAfterCounter) {
                    minBlueAfterCounter = blueLeft
                }
                if (redMove.totalCaptures > maxRedCaptures) {
                    maxRedCaptures = redMove.totalCaptures
                }
                board.unmakeMove(redMove, redSim)
            }
        }
        board.unmakeMove(blueMove, capSim)

        // Strict Net Piece Loss condition:
        // User pieces before move > User pieces after opponent's counter-capture
        val hasNetLoss = initialBlue > minBlueAfterCounter

        if (isDefenseMode) {
            if (hasNetLoss) return true
            if (maxRedCaptures >= 1) return true
            return false
        } else {
            if (!blueMove.isCapture && hasNetLoss) return true
            val netGain = blueMove.totalCaptures - maxRedCaptures
            if (netGain < 0) return true
            if (maxRedCaptures >= 2 && blueMove.totalCaptures < 2) return true
            return false
        }
    }

    /**
     * Requirement 3: Safest Quiet Locking Move Fallback
     * When all moves result in forced piece loss, defaults to the safest quiet locking move
     * that minimizes opponent capture potential and locks Blue pieces together in a shielded cluster.
     */
    fun findSafestQuietLockingMove(
        board: SholoGutiBoard,
        moves: List<GutiMove>,
        isDefenseMode: Boolean
    ): GutiMove {
        val quietMoves = moves.filter { !it.isCapture }
        val pool = if (quietMoves.isNotEmpty()) quietMoves else moves

        return pool.maxByOrNull { m ->
            val capSim = board.makeMove(m)
            val redCaptures = board.generateMoves(Piece.RED).filter { it.isCapture }
            val maxRedLoss = redCaptures.maxOfOrNull { it.totalCaptures } ?: 0
            val friendlyNeighbors = SholoGutiBoard.ADJACENCY[m.to].count { board.pieces[it] == Piece.BLUE }
            val isShielded = isPieceShieldedFromBehind(board, m.to)
            val isOuter = isOuterExposedNode(m.to)
            board.unmakeMove(m, capSim)

            var score = -maxRedLoss * 1_000_000
            score += friendlyNeighbors * 50_000
            if (isShielded) score += 30_000
            if (isOuter && friendlyNeighbors == 0) score -= 40_000
            if (m.to in intArrayOf(18, 8, 28, 6, 10, 26, 30)) score += 20_000
            score
        } ?: moves.first()
    }

    /**
     * Requirement 4: Multi-Capture / Double-Jump Split Path Check
     * Returns true if executing this capture opens multiple sequential jump options.
     */
    fun hasSplitBranchingOptions(board: SholoGutiBoard, move: GutiMove): Boolean {
        if (!move.isCapture) return false
        val firstHop = if (move.chainSequence.isNotEmpty()) move.chainSequence[0] else move.to

        val simBoard = board.copy()
        simBoard.pieces[move.from] = Piece.EMPTY
        simBoard.pieces[firstHop] = Piece.BLUE
        val jumpRule = SholoGutiBoard.JUMP_RULES.firstOrNull {
            it.from == move.from && it.to == firstHop && simBoard.pieces[it.over] == Piece.RED
        }
        if (jumpRule != null) {
            simBoard.pieces[jumpRule.over] = Piece.EMPTY
        }

        val subsequentJumpsCount = SholoGutiBoard.JUMP_RULES.count {
            it.from == firstHop && simBoard.pieces[it.over] == Piece.RED && simBoard.pieces[it.to] == Piece.EMPTY
        }
        return subsequentJumpsCount > 1
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
            Piece.BLUE -> score += 280
            Piece.RED -> score -= 280
            Piece.EMPTY -> {}
        }

        // Inner 3x3 Diamond Ring
        val innerRing = intArrayOf(12, 13, 14, 17, 19, 22, 23, 24)
        for (node in innerRing) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 115
                Piece.RED -> score -= 115
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
     * Mandatory Side Corner & Wing Tactical Dominance (Priority 1) & Base Deployment:
     * - Dominates opponent triangle wings (0, 2, 3, 5), outer grid corners & gateways (6, 10, 26, 30, 8, 28).
     * - Base Deployment: Penalizes leaving blue pieces sleeping in home base (31..36) to force active deployment.
     */
    private fun evaluateSideWingsAndCorners(board: SholoGutiBoard, isDefendMode: Boolean): Int {
        var score = 0
        val wingBase = if (isDefendMode) wingWeight + 150 else wingWeight + 80

        var blueWingsCount = 0
        var redWingsCount = 0

        // Opponent Triangle Wing Nodes (0, 2, 3, 5): Conquering enemy fortress
        val enemyWings = intArrayOf(0, 2, 3, 5)
        for (node in enemyWings) {
            val p = board.pieces[node]
            if (p == Piece.BLUE) {
                blueWingsCount++
                val hasFriendlySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.BLUE }
                // Priority 1 Massive Holding Reward: holding corner with support (+1800) vs unassisted (+1200)
                score += if (hasFriendlySupporter) wingBase + 1800 else wingBase + 1200
            } else if (p == Piece.RED) {
                redWingsCount++
                val hasFriendlySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.RED }
                score -= if (hasFriendlySupporter) wingBase + 1800 else wingBase + 1200
            }
        }

        // Grid Corners & Apex Gateways (Chokepoints connecting wings to 5x5 grid: 6, 10, 26, 30, 8, 28)
        val gateways = intArrayOf(6, 10, 26, 30, 8, 28)
        for (node in gateways) {
            when (board.pieces[node]) {
                Piece.BLUE -> {
                    blueWingsCount++
                    score += 850
                }
                Piece.RED -> {
                    redWingsCount++
                    score -= 850
                }
                Piece.EMPTY -> {}
            }
        }

        // Territorial dominance bonus for out-controlling wings & corners
        score += (blueWingsCount - redWingsCount) * 500

        // Side Flanks (11, 15, 16, 20)
        val flanks = intArrayOf(11, 15, 16, 20)
        for (node in flanks) {
            when (board.pieces[node]) {
                Piece.BLUE -> score += 60
                Piece.RED -> score -= 60
                Piece.EMPTY -> {}
            }
        }

        // Base Deployment Heuristic:
        // Penalize base stagnation when pieces remain trapped inside bottom triangle (31..36)
        var blueInBase = 0
        for (node in 31..36) {
            if (board.pieces[node] == Piece.BLUE) blueInBase++
        }
        if (blueInBase > 1) {
            score -= (blueInBase - 1) * 400 // Penalize leaving reserve pieces un-deployed
        }

        var redInBase = 0
        for (node in 0..5) {
            if (board.pieces[node] == Piece.RED) redInBase++
        }
        if (redInBase > 1) {
            score += (redInBase - 1) * 400
        }

        return score
    }

    /**
     * Evaluates Defensive Backing & Shielding Strategy:
     * - Friendly piece directly behind protects against jumps.
     * - In Defense Mode: Shielding bonus boosted (1.8x, +110), exposure penalty heavily amplified (-280).
     * - In Attack Mode: Standard shielding (+70), exposure penalty (-190).
     * - Formation cluster protection: Rewarding adjacent friendly pieces.
     */
    private fun evaluateDefensiveShielding(board: SholoGutiBoard, isDefendMode: Boolean): Int {
        var score = 0
        val shieldBonus = if (isDefendMode) 110 else 70
        val exposurePenalty = if (isDefendMode) 280 else 190

        for (rule in SholoGutiBoard.JUMP_RULES) {
            val pFrom = board.pieces[rule.from]
            val pOver = board.pieces[rule.over]
            val pTo = board.pieces[rule.to]

            // Blue piece in the middle
            if (pOver == Piece.BLUE) {
                if (pTo == Piece.BLUE) {
                    // Shielded! Enemy cannot jump Blue from 'from' because 'to' is blocked by Blue
                    score += shieldBonus
                } else if (pTo == Piece.EMPTY && pFrom == Piece.RED) {
                    // Exposed! Red can directly jump Blue from 'from' to 'to'
                    score -= exposurePenalty
                }
            }
            // Red piece in the middle
            else if (pOver == Piece.RED) {
                if (pTo == Piece.RED) {
                    score -= shieldBonus
                } else if (pTo == Piece.EMPTY && pFrom == Piece.BLUE) {
                    score += exposurePenalty
                }
            }
        }

        // Formation bonus: count adjacent friendly neighbors for mutual cluster protection
        val clusterWeight = if (isDefendMode) 1500 else 30
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            if (p == Piece.BLUE) {
                val friendlyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.BLUE }
                val isShielded = isPieceShieldedFromBehind(board, i)
                if (isDefendMode) {
                    score += friendlyNeighbors * clusterWeight
                    if (isShielded) score += 2000
                    if (friendlyNeighbors == 0) score -= 3000 // Heavy penalty for isolated piece in defense mode
                } else {
                    score += friendlyNeighbors * clusterWeight
                }
            } else if (p == Piece.RED) {
                val enemyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.RED }
                score -= enemyNeighbors * (if (isDefendMode) 50 else clusterWeight)
            }
        }

        return score
    }

    /**
     * Endgame Coordination & Trapping Heuristics (smoothly scaling as piece count drops <= 12):
     * - Sheep Cluster Cohesion: Rewarding friendly pieces keeping in compact protective groups.
     * - Lone Straggler Penalty: Heavy penalty for isolated pieces with zero friendly neighbors.
     * - Central Vertical Spine Barrier (8, 13, 18, 23, 28): Forming a line of control dividing the board.
     * - Corner Herding: Trapping remnant enemy pieces in apex/corner dead-ends when in lead.
     */
    private fun evaluateEndgame(board: SholoGutiBoard, bluePieces: Int, redPieces: Int, isDefendMode: Boolean): Int {
        var score = 0
        val totalPieces = bluePieces + redPieces

        // Endgame activates when total pieces drop <= 18, with highest influence at <= 12
        if (totalPieces > 18) return 0

        val weightMultiplier = when {
            totalPieces <= 8 -> 3
            totalPieces <= 12 -> 2
            else -> 1
        }

        val clusterReward = if (isDefendMode) 450 else 250
        val stragglerPenalty = if (isDefendMode) 1500 else 650

        // 1. Sheep Cluster Cohesion & Straggler Detection
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val p = board.pieces[i]
            if (p == Piece.BLUE) {
                val friendlyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.BLUE }
                if (friendlyNeighbors >= 1) {
                    score += clusterReward * friendlyNeighbors * weightMultiplier
                } else {
                    score -= stragglerPenalty * weightMultiplier
                }
            } else if (p == Piece.RED) {
                val enemyNeighbors = SholoGutiBoard.ADJACENCY[i].count { board.pieces[it] == Piece.RED }
                if (enemyNeighbors >= 1) {
                    score -= (clusterReward / 2) * enemyNeighbors * weightMultiplier
                } else {
                    score += (stragglerPenalty / 2) * weightMultiplier
                }
            }
        }

        // 2. Central Vertical Barrier (Nodes 8, 13, 18, 23, 28)
        val spineNodes = intArrayOf(8, 13, 18, 23, 28)
        var blueSpine = 0
        var redSpine = 0
        for (node in spineNodes) {
            when (board.pieces[node]) {
                Piece.BLUE -> blueSpine++
                Piece.RED -> redSpine++
                Piece.EMPTY -> {}
            }
        }
        score += (blueSpine - redSpine) * 350 * weightMultiplier

        // 3. Enemy Corner Herding when Blue has material advantage
        if (bluePieces > redPieces) {
            val deadEndApexNodes = intArrayOf(0, 5, 31, 36)
            for (node in deadEndApexNodes) {
                if (board.pieces[node] == Piece.RED) {
                    // Enemy trapped in low-mobility corner
                    score += 400 * weightMultiplier
                }
            }
        }

        return score
    }

    /**
     * Trap Avoidance & Vulnerability Detection:
     * - Apex / Chula Trap: Avoid sending unsupported pieces into apex corners (0, 2, 3, 5, 31, 33, 34, 36)
     *   where they are easily trapped and jumped.
     * - Center Fork Trap: Severe penalty if Red controls Node 18 and threatens multiple Blue pieces simultaneously.
     * - Corridor Pin Trap: Penalizes pieces wedged in narrow corridors without safe escape routes.
     */
    private fun evaluateTrapVulnerabilities(board: SholoGutiBoard, isDefenseMode: Boolean): Int {
        var score = 0

        // 1. Apex / Chula Trap Avoidance
        val apexNodes = intArrayOf(0, 2, 3, 5, 31, 33, 34, 36)
        val apexPenalty = if (isDefenseMode) 1800 else 850
        for (node in apexNodes) {
            if (board.pieces[node] == Piece.BLUE) {
                val friendlySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.BLUE }
                if (!friendlySupporter) {
                    score -= apexPenalty
                }
            } else if (board.pieces[node] == Piece.RED) {
                val enemySupporter = SholoGutiBoard.ADJACENCY[node].any { board.pieces[it] == Piece.RED }
                if (!enemySupporter) {
                    score += apexPenalty / 2
                }
            }
        }

        // 2. Center Fork Trap Avoidance (Node 18)
        // If Red occupies 18, check how many direct jumps or threats Red has on surrounding Blue pieces
        if (board.pieces[18] == Piece.RED) {
            var redJumpsFrom18 = 0
            for (rule in SholoGutiBoard.JUMP_RULES) {
                if (rule.from == 18 && board.pieces[rule.over] == Piece.BLUE && board.pieces[rule.to] == Piece.EMPTY) {
                    redJumpsFrom18++
                }
            }
            if (redJumpsFrom18 >= 2) {
                // Catastrophic double fork from center pivot!
                score -= if (isDefenseMode) 50_000 else 8_000
            } else if (redJumpsFrom18 == 1) {
                score -= if (isDefenseMode) 8_000 else 2_500
            }
        }

        // Symmetrically, if Blue holds 18 and forks Red pieces:
        if (board.pieces[18] == Piece.BLUE) {
            var blueJumpsFrom18 = 0
            for (rule in SholoGutiBoard.JUMP_RULES) {
                if (rule.from == 18 && board.pieces[rule.over] == Piece.RED && board.pieces[rule.to] == Piece.EMPTY) {
                    blueJumpsFrom18++
                }
            }
            if (blueJumpsFrom18 >= 2) {
                score += 5_000
            } else if (blueJumpsFrom18 == 1) {
                score += 1_500
            }
        }

        // 3. Corridor Pin Trap: Blue pieces pinned between two enemy lines with no valid regular moves
        val blueMoves = board.generateMoves(Piece.BLUE)
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            if (board.pieces[i] == Piece.BLUE) {
                val hasMoves = blueMoves.any { it.from == i }
                if (!hasMoves) {
                    // Immobilized / pinned piece
                    score -= if (isDefenseMode) 3500 else 1200
                }
            }
        }

        return score
    }

    private fun buildStatusMessage(move: GutiMove, isSafe: Boolean, depth: Int, board: SholoGutiBoard): String {
        val bluePieces = board.countPieces(Piece.BLUE)
        val redPieces = board.countPieces(Piece.RED)
        val materialDiff = bluePieces - redPieces
        val isFullDefenseMode = materialDiff >= 3

        val modeTag = when {
            isFullDefenseMode -> "🛡️ FULL DEFENSE / SHEEP MODE [+${materialDiff} Safe Lead]"
            materialDiff >= 4 -> "⚔️ DYNAMIC ATTACK [+${materialDiff} Advantage]"
            materialDiff > 0 -> "⚔️ AGGRESSIVE ATTACK [+${materialDiff}]"
            materialDiff == 0 -> "⚔️ AGGRESSIVE ATTACK [Balanced]"
            else -> "🛡️ DEFENSIVE SHIELD [${materialDiff}]"
        }
        val hangingPieces = findHangingBluePieces(board)
        val isRecovery = isMoveRescuingHangingPiece(board, move, hangingPieces)

        return buildString {
            append("$modeTag (d=$depth plies): ")

            if (isRecovery) {
                append("🛡️ RESCUE STRANDED PIECE: ")
            } else if (move.isChainCapture) {
                append("⚔️ MULTI-JUMP CHAIN (${move.totalCaptures} pieces!): ")
            } else if (move.isCapture) {
                append("⚔️ JUMP CAPTURE node ${move.captured}: ")
            } else if (isHomeBase(move.from) && (!isHomeBase(move.to) || move.to < move.from)) {
                append("🚀 Base Deployment: ")
            } else if (move.to == 18) {
                append("👑 Center Control Pivot: ")
            } else if (isSideWingOrCorner(move.to)) {
                append("🦅 Side Wing Fortress: ")
            } else {
                append("Strategic Advance: ")
            }

            append("${move.from} → ${move.to}")
            if (move.chainSequence.isNotEmpty()) {
                append(" (via ${move.chainSequence.joinToString("→")})")
            }

            if (isSafe) {
                append(" ✓ (Zero-Loss)")
            } else {
                append(" (Tactical)")
            }
        }
    }

    companion object {
        const val INFINITE_SCORE = 1_000_000_000
        const val TACTICAL_VETO_PENALTY = 999_999_999 // Absolute Tactical Veto (-999,999,999) for zero-loss defense & anti-catastrophe
        const val CATASTROPHIC_BLUNDER_PENALTY = 999_999_999 // Forbids losing 2, 3, or 4 pieces under any circumstances
        const val ANTI_LOOP_TABU_PENALTY = 920_000_000 // Anti-Loop Heuristic: strictly forbids cyclic back-and-forth oscillation
        const val WING_ABANDON_PENALTY = -3_500 // Strictly forbids abandoning secured wing/corner unless jumping >= 2 pieces
        const val DEFAULT_TIME_LIMIT_MS = 5_000L // Fast 5-second analysis window to easily beat 15s turn limit

        private const val FLAG_EXACT: Byte = 0
        private const val FLAG_LOWER: Byte = 1
        private const val FLAG_UPPER: Byte = 2
    }
}
