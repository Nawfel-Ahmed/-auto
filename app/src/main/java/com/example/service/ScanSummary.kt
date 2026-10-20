package com.example.service

data class ScanSummary(
    val blueCount: Int,
    val redCount: Int,
    val emptyCount: Int,
    val success: Boolean,
    val message: String
)
