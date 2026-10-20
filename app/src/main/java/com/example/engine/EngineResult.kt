package com.example.engine

data class EngineResult(
    val bestMove: GutiMove?,
    val score: Int,
    val depthReached: Int,
    val nodesEvaluated: Long,
    val durationMs: Long,
    val isSafe: Boolean,
    val statusMessage: String
)
