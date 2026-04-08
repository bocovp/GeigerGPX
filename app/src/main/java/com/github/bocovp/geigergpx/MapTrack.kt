package com.github.bocovp.geigergpx

data class TrackSample(
    val latitude: Double,
    val longitude: Double,
    val doseRate: Double,
    val counts: Int,
    val seconds: Double,
    val badCoordinates: Boolean = false
)

data class MapTrack(
    val id: String,
    val title: String,
    val points: List<TrackSample>
)
