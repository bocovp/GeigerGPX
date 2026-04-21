package com.github.bocovp.geigergpx

data class MapTrack(
    val id: String,
    val title: String,
    val points: List<TrackPoint>
)
