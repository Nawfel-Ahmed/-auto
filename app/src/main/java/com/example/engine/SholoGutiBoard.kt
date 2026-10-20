package com.example.engine

class SholoGutiBoard {
    val pieces: Array<Piece> = Array(TOTAL_NODES) { Piece.EMPTY }

    init {
        setupDefaultBoard()
    }

    fun setupDefaultBoard() {
        for (i in 0 until TOTAL_NODES) {
            pieces[i] = when {
                i < 16 -> Piece.RED
                i in 16..20 -> Piece.EMPTY
                else -> Piece.BLUE
            }
        }
    }

    fun copy(): SholoGutiBoard {
        val b = SholoGutiBoard()
        for (i in 0 until TOTAL_NODES) {
            b.pieces[i] = this.pieces[i]
        }
        return b
    }

    fun countPieces(piece: Piece): Int {
        var count = 0
        for (p in pieces) {
            if (p == piece) count++
        }
        return count
    }

    fun generateMoves(player: Piece): List<GutiMove> {
        val opponent = player.opponent()
        val captureMoves = mutableListOf<GutiMove>()
        val stepMoves = mutableListOf<GutiMove>()

        for (rule in JUMP_RULES) {
            if (pieces[rule.from] == player &&
                pieces[rule.over] == opponent &&
                pieces[rule.to] == Piece.EMPTY
            ) {
                captureMoves.add(GutiMove(rule.from, rule.to, rule.over))
            }
        }

        if (captureMoves.isNotEmpty()) {
            return captureMoves
        }

        for (i in 0 until TOTAL_NODES) {
            if (pieces[i] == player) {
                for (neighbor in ADJACENCY[i]) {
                    if (pieces[neighbor] == Piece.EMPTY) {
                        stepMoves.add(GutiMove(i, neighbor, -1))
                    }
                }
            }
        }
        return stepMoves
    }

    fun makeMove(move: GutiMove): Piece {
        val movedPiece = pieces[move.from]
        pieces[move.from] = Piece.EMPTY
        pieces[move.to] = movedPiece
        var capturedPiece = Piece.EMPTY
        if (move.isCapture && move.captured in 0 until TOTAL_NODES) {
            capturedPiece = pieces[move.captured]
            pieces[move.captured] = Piece.EMPTY
        }
        return capturedPiece
    }

    fun unmakeMove(move: GutiMove, capturedPiece: Piece) {
        val movedPiece = pieces[move.to]
        pieces[move.to] = Piece.EMPTY
        pieces[move.from] = movedPiece
        if (move.isCapture && move.captured in 0 until TOTAL_NODES) {
            pieces[move.captured] = capturedPiece
        }
    }

    companion object {
        const val TOTAL_NODES = 37

        val NODE_POINTS: Array<NodePoint> = arrayOf(
            NodePoint(0, 0.280f, 0.165f, isBackbone = false, isWing = true),
            NodePoint(1, 0.500f, 0.165f, isBackbone = true, isWing = false),
            NodePoint(2, 0.720f, 0.165f, isBackbone = false, isWing = true),
            NodePoint(3, 0.390f, 0.218f, isBackbone = false, isWing = true),
            NodePoint(4, 0.500f, 0.218f, isBackbone = true, isWing = false),
            NodePoint(5, 0.610f, 0.218f, isBackbone = false, isWing = true),

            NodePoint(6, 0.065f, 0.270f, isBackbone = false, isWing = true),
            NodePoint(7, 0.280f, 0.270f, isBackbone = false, isWing = false),
            NodePoint(8, 0.500f, 0.270f, isBackbone = true, isWing = false),
            NodePoint(9, 0.720f, 0.270f, isBackbone = false, isWing = false),
            NodePoint(10, 0.935f, 0.270f, isBackbone = false, isWing = true),

            NodePoint(11, 0.065f, 0.372f, isBackbone = false, isWing = true),
            NodePoint(12, 0.280f, 0.372f, isBackbone = false, isWing = false),
            NodePoint(13, 0.500f, 0.372f, isBackbone = true, isWing = false),
            NodePoint(14, 0.720f, 0.372f, isBackbone = false, isWing = false),
            NodePoint(15, 0.935f, 0.372f, isBackbone = false, isWing = true),

            NodePoint(16, 0.065f, 0.474f, isBackbone = false, isWing = true),
            NodePoint(17, 0.280f, 0.474f, isBackbone = false, isWing = false),
            NodePoint(18, 0.500f, 0.474f, isBackbone = true, isWing = false),
            NodePoint(19, 0.720f, 0.474f, isBackbone = false, isWing = false),
            NodePoint(20, 0.935f, 0.474f, isBackbone = false, isWing = true),

            NodePoint(21, 0.065f, 0.576f, isBackbone = false, isWing = true),
            NodePoint(22, 0.280f, 0.576f, isBackbone = false, isWing = false),
            NodePoint(23, 0.500f, 0.576f, isBackbone = true, isWing = false),
            NodePoint(24, 0.720f, 0.576f, isBackbone = false, isWing = false),
            NodePoint(25, 0.935f, 0.576f, isBackbone = false, isWing = true),

            NodePoint(26, 0.065f, 0.678f, isBackbone = false, isWing = true),
            NodePoint(27, 0.280f, 0.678f, isBackbone = false, isWing = false),
            NodePoint(28, 0.500f, 0.678f, isBackbone = true, isWing = false),
            NodePoint(29, 0.720f, 0.678f, isBackbone = false, isWing = false),
            NodePoint(30, 0.935f, 0.678f, isBackbone = false, isWing = true),

            NodePoint(31, 0.390f, 0.730f, isBackbone = false, isWing = true),
            NodePoint(32, 0.500f, 0.730f, isBackbone = true, isWing = false),
            NodePoint(33, 0.610f, 0.730f, isBackbone = false, isWing = true),
            NodePoint(34, 0.280f, 0.783f, isBackbone = false, isWing = true),
            NodePoint(35, 0.500f, 0.783f, isBackbone = true, isWing = false),
            NodePoint(36, 0.720f, 0.783f, isBackbone = false, isWing = true)
        )

        val ADJACENCY: Array<IntArray>
        val JUMP_RULES: List<JumpRule>

        init {
            val adjMap = Array(TOTAL_NODES) { mutableListOf<Int>() }

            fun connect(a: Int, b: Int) {
                if (!adjMap[a].contains(b)) adjMap[a].add(b)
                if (!adjMap[b].contains(a)) adjMap[b].add(a)
            }

            // Top Triangle connections
            connect(0, 1); connect(1, 2)
            connect(3, 4); connect(4, 5)
            connect(1, 4)
            connect(0, 3); connect(3, 8)
            connect(2, 5); connect(5, 8)
            connect(0, 4); connect(4, 9)
            connect(2, 4); connect(4, 7)
            connect(4, 8)
            connect(3, 7); connect(5, 9)

            // 5x5 Grid horizontal
            for (r in 0..4) {
                val start = r * 5 + 6
                for (c in 0..3) {
                    connect(start + c, start + c + 1)
                }
            }

            // 5x5 Grid vertical
            for (r in 0..3) {
                val start = r * 5 + 6
                for (c in 0..4) {
                    connect(start + c, start + 5 + c)
                }
            }

            // 5x5 Grid diagonals
            for (r in 0..3) {
                for (c in 0..3) {
                    if ((r + c) % 2 == 0) {
                        connect(r * 5 + 6 + c, (r + 1) * 5 + 6 + c + 1)
                    }
                    if ((r + c + 1) % 2 == 1) {
                        connect(r * 5 + 6 + c + 1, (r + 1) * 5 + 6 + c)
                    }
                }
            }

            // Bottom Triangle connections
            connect(31, 32); connect(32, 33)
            connect(34, 35); connect(35, 36)
            connect(28, 32); connect(32, 35)
            connect(28, 31); connect(31, 34)
            connect(28, 33); connect(33, 36)
            connect(27, 32); connect(32, 36)
            connect(29, 32); connect(32, 34)
            connect(27, 31); connect(29, 33)

            ADJACENCY = Array(TOTAL_NODES) { adjMap[it].toIntArray() }

            val jumps = mutableListOf<JumpRule>()
            fun addCollinearLine(line: IntArray) {
                for (i in 0 until line.size - 2) {
                    val a = line[i]
                    val b = line[i + 1]
                    val c = line[i + 2]
                    jumps.add(JumpRule(a, b, c))
                    jumps.add(JumpRule(c, b, a))
                }
            }

            addCollinearLine(intArrayOf(1, 4, 8, 13, 18, 23, 28, 32, 35)) // Backbone
            addCollinearLine(intArrayOf(6, 11, 16, 21, 26))
            addCollinearLine(intArrayOf(7, 12, 17, 22, 27))
            addCollinearLine(intArrayOf(9, 14, 19, 24, 29))
            addCollinearLine(intArrayOf(10, 15, 20, 25, 30))

            addCollinearLine(intArrayOf(0, 1, 2))
            addCollinearLine(intArrayOf(3, 4, 5))
            addCollinearLine(intArrayOf(6, 7, 8, 9, 10))
            addCollinearLine(intArrayOf(11, 12, 13, 14, 15))
            addCollinearLine(intArrayOf(16, 17, 18, 19, 20))
            addCollinearLine(intArrayOf(21, 22, 23, 24, 25))
            addCollinearLine(intArrayOf(26, 27, 28, 29, 30))
            addCollinearLine(intArrayOf(31, 32, 33))
            addCollinearLine(intArrayOf(34, 35, 36))

            addCollinearLine(intArrayOf(6, 12, 18, 24, 30))
            addCollinearLine(intArrayOf(10, 14, 18, 22, 26))
            addCollinearLine(intArrayOf(8, 12, 16))
            addCollinearLine(intArrayOf(8, 14, 20))
            addCollinearLine(intArrayOf(16, 22, 28))
            addCollinearLine(intArrayOf(20, 24, 28))

            addCollinearLine(intArrayOf(0, 4, 9))
            addCollinearLine(intArrayOf(2, 4, 7))
            addCollinearLine(intArrayOf(0, 3, 8))
            addCollinearLine(intArrayOf(2, 5, 8))
            addCollinearLine(intArrayOf(27, 32, 36))
            addCollinearLine(intArrayOf(29, 32, 34))
            addCollinearLine(intArrayOf(28, 31, 34))
            addCollinearLine(intArrayOf(28, 33, 36))

            val seen = HashSet<String>()
            val distinctJumps = mutableListOf<JumpRule>()
            for (j in jumps) {
                val key = "${j.from}-${j.over}-${j.to}"
                if (seen.add(key)) {
                    distinctJumps.add(j)
                }
            }
            JUMP_RULES = distinctJumps
        }

        /**
         * Precision calculation for screen mapping.
         * Scales the 37 nodes to match the real game screen with full interactive calibration.
         */
        fun calculateNodeScreenPosition(
            nodeIndex: Int,
            screenWidth: Float,
            screenHeight: Float,
            scaleFactor: Float = 0.92f,
            verticalOffset: Float = 0.50f,
            horizontalOffset: Float = 0.50f,
            verticalScale: Float = 1.00f,
            triangleRatio: Float = 1.00f
        ): Pair<Float, Float> {
            val pt = NODE_POINTS[nodeIndex.coerceIn(0, TOTAL_NODES - 1)]
            val boardW = screenWidth * scaleFactor
            val boardH = boardW * 1.34f * verticalScale
            val centerX = screenWidth * horizontalOffset
            val centerY = screenHeight * verticalOffset

            // Adjust vertical distance of wings based on triangleRatio
            val adjustedNormY = if (nodeIndex <= 5) {
                // Top triangle wings
                val deltaFromRow0 = (pt.y - 0.270f) * triangleRatio
                (0.270f - 0.474f) + deltaFromRow0
            } else if (nodeIndex >= 31) {
                // Bottom triangle wings
                val deltaFromRow4 = (pt.y - 0.678f) * triangleRatio
                (0.678f - 0.474f) + deltaFromRow4
            } else {
                pt.y - 0.474f
            }

            val x = centerX + (pt.x - 0.50f) * boardW
            val y = centerY + adjustedNormY * boardH
            return Pair(x, y)
        }
    }
}
