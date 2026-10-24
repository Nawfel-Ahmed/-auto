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

        // Normalized relative descriptors for the 37 nodes
        val NODE_POINTS: Array<NodePoint> = arrayOf(
            // Top Triangle Wing (Nodes 0..5)
            // Apex Row (Row -2)
            NodePoint(0, 0.250f, 0.000f, isBackbone = false, isWing = true),
            NodePoint(1, 0.500f, 0.000f, isBackbone = true, isWing = false),
            NodePoint(2, 0.750f, 0.000f, isBackbone = false, isWing = true),
            // Middle Triangle Row (Row -1)
            NodePoint(3, 0.375f, 0.125f, isBackbone = false, isWing = true),
            NodePoint(4, 0.500f, 0.125f, isBackbone = true, isWing = false),
            NodePoint(5, 0.625f, 0.125f, isBackbone = false, isWing = true),

            // Central 5x5 Square Grid (Nodes 6..30)
            // Row 0 (Top grid edge)
            NodePoint(6, 0.000f, 0.250f, isBackbone = false, isWing = true),
            NodePoint(7, 0.250f, 0.250f, isBackbone = false, isWing = false),
            NodePoint(8, 0.500f, 0.250f, isBackbone = true, isWing = false),
            NodePoint(9, 0.750f, 0.250f, isBackbone = false, isWing = false),
            NodePoint(10, 1.000f, 0.250f, isBackbone = false, isWing = true),

            // Row 1
            NodePoint(11, 0.000f, 0.500f, isBackbone = false, isWing = true),
            NodePoint(12, 0.250f, 0.500f, isBackbone = false, isWing = false),
            NodePoint(13, 0.500f, 0.500f, isBackbone = true, isWing = false),
            NodePoint(14, 0.750f, 0.500f, isBackbone = false, isWing = false),
            NodePoint(15, 1.000f, 0.500f, isBackbone = false, isWing = true),

            // Row 2 (Middle horizontal line, board center at Node 18)
            NodePoint(16, 0.000f, 0.750f, isBackbone = false, isWing = true),
            NodePoint(17, 0.250f, 0.750f, isBackbone = false, isWing = false),
            NodePoint(18, 0.500f, 0.750f, isBackbone = true, isWing = false),
            NodePoint(19, 0.750f, 0.750f, isBackbone = false, isWing = false),
            NodePoint(20, 1.000f, 0.750f, isBackbone = false, isWing = true),

            // Row 3
            NodePoint(21, 0.000f, 1.000f, isBackbone = false, isWing = true),
            NodePoint(22, 0.250f, 1.000f, isBackbone = false, isWing = false),
            NodePoint(23, 0.500f, 1.000f, isBackbone = true, isWing = false),
            NodePoint(24, 0.750f, 1.000f, isBackbone = false, isWing = false),
            NodePoint(25, 1.000f, 1.000f, isBackbone = false, isWing = true),

            // Row 4 (Bottom grid edge)
            NodePoint(26, 0.000f, 1.250f, isBackbone = false, isWing = true),
            NodePoint(27, 0.250f, 1.250f, isBackbone = false, isWing = false),
            NodePoint(28, 0.500f, 1.250f, isBackbone = true, isWing = false),
            NodePoint(29, 0.750f, 1.250f, isBackbone = false, isWing = false),
            NodePoint(30, 1.000f, 1.250f, isBackbone = false, isWing = true),

            // Bottom Triangle Wing (Nodes 31..36)
            // Middle Triangle Row (Row +1)
            NodePoint(31, 0.375f, 1.375f, isBackbone = false, isWing = true),
            NodePoint(32, 0.500f, 1.375f, isBackbone = true, isWing = false),
            NodePoint(33, 0.625f, 1.375f, isBackbone = false, isWing = true),
            // Outer Row (Row +2)
            NodePoint(34, 0.250f, 1.500f, isBackbone = false, isWing = true),
            NodePoint(35, 0.500f, 1.500f, isBackbone = true, isWing = false),
            NodePoint(36, 0.750f, 1.500f, isBackbone = false, isWing = true)
        )

        // Exact 16 Ghuti Line Connections and Collinear Jumps
        val ADJACENCY: Array<IntArray>
        val JUMP_RULES: List<JumpRule>

        init {
            val adjMap = Array(TOTAL_NODES) { mutableListOf<Int>() }

            fun connect(a: Int, b: Int) {
                if (!adjMap[a].contains(b)) adjMap[a].add(b)
                if (!adjMap[b].contains(a)) adjMap[b].add(a)
            }

            // Top Triangle connections (Only 3 radiating lines from node 8 + 2 horizontal crossbars)
            connect(0, 1); connect(1, 2)
            connect(3, 4); connect(4, 5)
            connect(1, 4); connect(4, 8)
            connect(0, 3); connect(3, 8)
            connect(2, 5); connect(5, 8)

            // 5x5 Grid horizontal lines
            for (r in 0..4) {
                val start = r * 5 + 6
                for (c in 0..3) {
                    connect(start + c, start + c + 1)
                }
            }

            // 5x5 Grid vertical lines
            for (r in 0..3) {
                val start = r * 5 + 6
                for (c in 0..4) {
                    connect(start + c, start + 5 + c)
                }
            }

            // 5x5 Grid exact authentic diagonals:
            // Two main diagonals
            connect(6, 12); connect(12, 18); connect(18, 24); connect(24, 30)
            connect(10, 14); connect(14, 18); connect(18, 22); connect(22, 26)

            // Four diamond diagonals
            connect(8, 12); connect(12, 16)
            connect(8, 14); connect(14, 20)
            connect(16, 22); connect(22, 28)
            connect(20, 24); connect(24, 28)

            // Bottom Triangle connections (Only 3 radiating lines from node 28 + 2 horizontal crossbars)
            connect(31, 32); connect(32, 33)
            connect(34, 35); connect(35, 36)
            connect(28, 32); connect(32, 35)
            connect(28, 31); connect(31, 34)
            connect(28, 33); connect(33, 36)

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

            // Authentic Collinear jump lines across the board
            // Central vertical backbone
            addCollinearLine(intArrayOf(1, 4, 8, 13, 18, 23, 28, 32, 35))
            // Other grid vertical columns
            addCollinearLine(intArrayOf(6, 11, 16, 21, 26))
            addCollinearLine(intArrayOf(7, 12, 17, 22, 27))
            addCollinearLine(intArrayOf(9, 14, 19, 24, 29))
            addCollinearLine(intArrayOf(10, 15, 20, 25, 30))

            // Horizontal lines
            addCollinearLine(intArrayOf(0, 1, 2))
            addCollinearLine(intArrayOf(3, 4, 5))
            addCollinearLine(intArrayOf(6, 7, 8, 9, 10))
            addCollinearLine(intArrayOf(11, 12, 13, 14, 15))
            addCollinearLine(intArrayOf(16, 17, 18, 19, 20))
            addCollinearLine(intArrayOf(21, 22, 23, 24, 25))
            addCollinearLine(intArrayOf(26, 27, 28, 29, 30))
            addCollinearLine(intArrayOf(31, 32, 33))
            addCollinearLine(intArrayOf(34, 35, 36))

            // Main diagonals
            addCollinearLine(intArrayOf(6, 12, 18, 24, 30))
            addCollinearLine(intArrayOf(10, 14, 18, 22, 26))

            // Diamond diagonals
            addCollinearLine(intArrayOf(8, 12, 16))
            addCollinearLine(intArrayOf(8, 14, 20))
            addCollinearLine(intArrayOf(16, 22, 28))
            addCollinearLine(intArrayOf(20, 24, 28))

            // Triangle sloped collinear lines
            addCollinearLine(intArrayOf(0, 3, 8))
            addCollinearLine(intArrayOf(2, 5, 8))
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
         * Precision Geometric Screen Position Calculation:
         * Maps all 37 nodes to exact screen pixel coordinates based on calibrated percentages.
         * The central 5x5 grid maintains equal horizontal and vertical spacing (perfect squares).
         * Triangular wings connect seamlessly with exactly half-grid-step vertical spacing per row.
         */
        fun calculateNodeScreenPosition(
            nodeIndex: Int,
            screenWidth: Float,
            screenHeight: Float,
            scaleFactor: Float = 0.886f,
            verticalOffset: Float = 0.4764f,
            horizontalOffset: Float = 0.50f,
            verticalScale: Float = 1.00f,
            triangleRatio: Float = 1.00f,
            triangleWidthRatio: Float = 1.00f
        ): Pair<Float, Float> {
            // Unit grid step u (distance between adjacent lines in the 5x5 grid)
            val u = screenWidth * (scaleFactor / 4.0f)
            val centerX = screenWidth * horizontalOffset
            val centerY = screenHeight * verticalOffset

            val dx: Float
            val dy: Float

            when {
                // Top Triangle Apex Row -2 (nodes 0, 1, 2)
                nodeIndex == 0 -> {
                    dx = -1.0f * triangleWidthRatio
                    dy = -2.0f - 1.0f * triangleRatio
                }
                nodeIndex == 1 -> {
                    dx = 0.0f
                    dy = -2.0f - 1.0f * triangleRatio
                }
                nodeIndex == 2 -> {
                    dx = +1.0f * triangleWidthRatio
                    dy = -2.0f - 1.0f * triangleRatio
                }

                // Top Triangle Mid Row -1 (nodes 3, 4, 5)
                nodeIndex == 3 -> {
                    dx = -0.5f * triangleWidthRatio
                    dy = -2.0f - 0.5f * triangleRatio
                }
                nodeIndex == 4 -> {
                    dx = 0.0f
                    dy = -2.0f - 0.5f * triangleRatio
                }
                nodeIndex == 5 -> {
                    dx = +0.5f * triangleWidthRatio
                    dy = -2.0f - 0.5f * triangleRatio
                }

                // Central 5x5 Grid (nodes 6..30)
                nodeIndex in 6..30 -> {
                    val gridIdx = nodeIndex - 6
                    val col = (gridIdx % 5) - 2  // -2, -1, 0, 1, 2
                    val row = (gridIdx / 5) - 2  // -2, -1, 0, 1, 2
                    dx = col.toFloat()
                    dy = row.toFloat()
                }

                // Bottom Triangle Mid Row +1 (nodes 31, 32, 33)
                nodeIndex == 31 -> {
                    dx = -0.5f * triangleWidthRatio
                    dy = +2.0f + 0.5f * triangleRatio
                }
                nodeIndex == 32 -> {
                    dx = 0.0f
                    dy = +2.0f + 0.5f * triangleRatio
                }
                nodeIndex == 33 -> {
                    dx = +0.5f * triangleWidthRatio
                    dy = +2.0f + 0.5f * triangleRatio
                }

                // Bottom Triangle Apex Row +2 (nodes 34, 35, 36)
                nodeIndex == 34 -> {
                    dx = -1.0f * triangleWidthRatio
                    dy = +2.0f + 1.0f * triangleRatio
                }
                nodeIndex == 35 -> {
                    dx = 0.0f
                    dy = +2.0f + 1.0f * triangleRatio
                }
                nodeIndex == 36 -> {
                    dx = +1.0f * triangleWidthRatio
                    dy = +2.0f + 1.0f * triangleRatio
                }

                else -> {
                    dx = 0.0f
                    dy = 0.0f
                }
            }

            val px = centerX + dx * u
            val py = centerY + dy * u * verticalScale
            return Pair(px, py)
        }
    }
}
