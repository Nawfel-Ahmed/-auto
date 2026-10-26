package com.example.engine

data class GutiMove(
    val from: Int,
    val to: Int,
    val captured: Int = -1,
    val chainSequence: List<Int> = emptyList(),
    val totalCaptures: Int = if (captured != -1) 1 else 0,
    val allCaptured: List<Int> = if (captured != -1) listOf(captured) else emptyList()
) {
    val isCapture: Boolean get() = captured != -1
    val isChainCapture: Boolean get() = totalCaptures > 1
}

