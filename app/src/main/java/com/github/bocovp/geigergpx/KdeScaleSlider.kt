package com.github.bocovp.geigergpx

import kotlin.math.exp

object KdeScaleSlider {
    const val INTERNAL_MAX = 1f
    private const val EXP_SCALE_FACTOR = 0.523957

    fun internalToMinutes(internalValue: Float): Float {
        val expTerm = exp(3.0 * internalValue.toDouble()) - 1.0
        return (EXP_SCALE_FACTOR * expTerm).toFloat()
    }
}
