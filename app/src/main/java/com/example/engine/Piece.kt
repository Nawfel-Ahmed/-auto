package com.example.engine

enum class Piece {
    EMPTY,
    BLUE,
    RED;

    fun opponent(): Piece = when (this) {
        BLUE -> RED
        RED -> BLUE
        EMPTY -> EMPTY
    }
}
