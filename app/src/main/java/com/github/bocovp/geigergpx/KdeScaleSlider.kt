package com.github.bocovp.geigergpx

import kotlin.math.exp

object KdeScaleSlider {
    const val INTERNAL_MAX = 1f
    const val MIN_MINUTES = 0.1f
    const val MIN_SECONDS = MIN_MINUTES * 60.0
    private const val EXP_SCALE_FACTOR = 0.523957

    fun internalToMinutes(internalValue: Float): Float {
        val expTerm = exp(3.0 * internalValue.toDouble()) - 1.0
        return (EXP_SCALE_FACTOR * expTerm).toFloat().coerceAtLeast(MIN_MINUTES)
    }

    fun minutesToInternal(minutes: Float): Float {
        val clampedMinutes = minutes.coerceAtLeast(MIN_MINUTES)
        val argument = (clampedMinutes / EXP_SCALE_FACTOR).toDouble() + 1.0
        return (kotlin.math.ln(argument) / 3.0).toFloat().coerceIn(0f, INTERNAL_MAX)
    }
}
