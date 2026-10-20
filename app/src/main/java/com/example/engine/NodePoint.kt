package com.example.engine

data class NodePoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val isBackbone: Boolean = false,
    val isWing: Boolean = false
)
