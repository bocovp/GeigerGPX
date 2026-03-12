package com.example.geigergpx

import android.app.Application

class GeigerGpxApp : Application() {
    val trackingRepository: TrackingRepository by lazy { TrackingRepository() }
}

