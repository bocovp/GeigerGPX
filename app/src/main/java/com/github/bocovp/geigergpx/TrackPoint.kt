package com.github.bocovp.geigergpx

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val doseRate: Double,
    val counts: Int,
    val seconds: Double,
    val badCoordinates: Boolean = false
)

data class TrackStats(
    val pointCount: Int,
    val durationMillis: Long,
    val distanceMeters: Double
)