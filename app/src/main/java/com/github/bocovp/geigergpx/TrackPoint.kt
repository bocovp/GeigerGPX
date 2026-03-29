package com.github.bocovp.geigergpx

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val distanceFromLast: Double,
    val cps: Double,
    val counts: Int,
    val seconds: Double
)
