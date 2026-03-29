package com.example.geigergpx

import android.graphics.Color
import kotlin.math.abs

object DoseColorScale {
    private const val ZERO_DOSE_EPSILON = 1e-5
    private const val GREEN_RED_SPLIT = 0.5

    private const val R1 = 0x00
    private const val G1 = 0xC8
    private const val B1 = 0x53

    private const val R2 = 0xFF
    private const val G2 = 0xEB
    private const val B2 = 0x3B

    private const val R3 = 0xD5
    private const val G3 = 0x00
    private const val B3 = 0x00

    fun colorForDose(value: Double, minDose: Double, maxDose: Double): Int {
        if (abs(value) < ZERO_DOSE_EPSILON) {
            return Color.GRAY
        }

        val normalized = if (maxDose > minDose) {
            (value - minDose) / (maxDose - minDose)
        } else {
            0.0
        }
        val t = normalized.coerceIn(0.0, 1.0)

        val (r, g, b) = if (t < GREEN_RED_SPLIT) {
            val ratio = t * 2
            Triple(
                (R1 + ratio * (R2 - R1)).toInt(),
                (G1 + ratio * (G2 - G1)).toInt(),
                (B1 + ratio * (B2 - B1)).toInt()
            )
        } else {
            val ratio = (t - GREEN_RED_SPLIT) * 2
            Triple(
                (R2 + ratio * (R3 - R2)).toInt(),
                (G2 + ratio * (G3 - G2)).toInt(),
                (B2 + ratio * (B3 - B2)).toInt()
            )
        }

        return Color.rgb(r, g, b)
    }
}
