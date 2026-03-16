package com.example.geigergpx

data class TrackSample(
    val latitude: Double,
    val longitude: Double,
    val doseRate: Double
)

data class MapTrack(
    val id: String,
    val title: String,
    val points: List<TrackSample>
)
