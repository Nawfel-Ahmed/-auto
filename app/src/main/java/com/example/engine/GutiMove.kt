package com.example.engine

data class GutiMove(
    val from: Int,
    val to: Int,
    val captured: Int = -1
) {
    val isCapture: Boolean get() = captured != -1
}
