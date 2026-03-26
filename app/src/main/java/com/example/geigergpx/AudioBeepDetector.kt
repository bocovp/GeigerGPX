package com.example.geigergpx

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.preference.PreferenceManager
import kotlin.concurrent.thread

class AudioBeepDetector(
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {},
    private val onRawAudio: ((ShortArray) -> Unit)? = null
) {

    @Volatile
    private var running = false
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val SAMPLE_RATE = 44100
    private val WINDOW_SIZE = 175 // aligned: bin 13 is central frequency
    private val WINDOWS_IN_BUFFER = 64 // 0.25 sec delaly

    private val ZERO_BUFFER_LIMIT = 20

    fun start()
    {

        if (running) return
        running = true

        // MODIFIED: create recorder using new API
        val created = createAudioRecord()
        if (created == null) {
            running = false
            return
        }

        val (ar, bufferSize) = created   // MODIFIED
        audioRecord = ar                 // MODIFIED

        try {                            // MODIFIED
            ar.startRecording()

            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("AudioBeepDetector", "Failed to start recording")
                ar.release()
                running = false
                return
            }

        } catch (e: Exception) {         // MODIFIED
            Log.e("AudioBeepDetector", "startRecording failed", e)
            ar.release()
            running = false
            return
        }

        val detector = GoertzelDetector(magThreshold)
        detector.onBeep = onBeep

        workerThread = thread(start = true, name = "BeepDetector")
        {
            // Устанавливаем приоритет для работы со звуком
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val audioBuf = ShortArray(bufferSize)
            var audioHealthy = true

            var zeroBufferCount = 0

            try
            {
                while (running)
                {
                    val currentAr  = audioRecord
                    if (currentAr == null) {
                        Thread.sleep(20)   // MODIFIED
                        continue
                    }

                    if (currentAr.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e("AudioBeepDetector", "Recorder uninitialized") // MODIFIED
                        restartAudioRecord()                                  // MODIFIED
                        continue
                    }

                    val read = currentAr.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) {
                        zeroBufferCount++
                        Log.e("MYTAG", "Hardware Error: $read")
                        continue
                    }

                    var isZero = true
                    for (i in 0 until read) {
                        if (audioBuf[i] != 0.toShort()) {
                            isZero = false
                            break
                        }
                    }

                    if (isZero) {
                        zeroBufferCount++
                        if (audioHealthy) {
                            Log.d("MYTAG", "Error: empty audioBuff")
                            audioHealthy = false
                            onAudioHealth(false)
                        }
                    } else {
                        zeroBufferCount = 0
                        if (!audioHealthy) {
                            audioHealthy = true
                            onAudioHealth(true)
                        }
                    }

                    if (zeroBufferCount >= ZERO_BUFFER_LIMIT) {
                        Log.w("AudioBeepDetector", "AudioRecord appears stuck — restarting")
                        val newBufferSize = restartAudioRecord()

                        if (newBufferSize != null && newBufferSize != bufferSize) {
                            Log.w("AudioBeepDetector", "Buffer size changed after restart") // MODIFIED
                        }

                        zeroBufferCount = 0
                        continue
                    }

                    val samples = audioBuf.copyOf(read)
                    onRawAudio?.invoke(samples)
                    detector.processSamples(samples)

                }
            } catch (e: Exception) {
                Log.e("AudioBeepDetector", "Thread error", e)
            } finally {
                audioRecord?.let {
                    try {
                        if (it.state == AudioRecord.STATE_INITIALIZED) {
                            it.stop()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun createAudioRecord(): Pair<AudioRecord, Int>? {

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Log.e("AudioBeepDetector", "Invalid minBufferSize: $minBuffer")
            return null
        }

        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * WINDOWS_IN_BUFFER)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioBeepDetector", "AudioRecord initialization failed")
            ar.release()
            return null
        }

        return Pair(ar, bufferSize)
    }

    private fun restartAudioRecord(): Int? {

        val old = audioRecord
        audioRecord = null

        try {
            old?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioBeepDetector", "Error stopping AudioRecord", e)
        }

        val created = createAudioRecord()
        if (created == null) {
            Log.e("AudioBeepDetector", "AudioRecord recreation failed")
            return null
        }

        val (newRecorder, bufferSize) = created

        try {

            newRecorder.startRecording()

            if (newRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("AudioBeepDetector", "AudioRecord failed to start")
                newRecorder.release()
                return null
            }

            audioRecord = newRecorder
            return bufferSize

        } catch (e: Exception) {

            Log.e("AudioBeepDetector", "Error starting AudioRecord", e)

            try {
                newRecorder.release()
            } catch (_: Exception) {}

            return null
        }
    }

    fun stop() {
        running = false
        // Join the thread first to ensure it's done using the audioRecord
        try {
            workerThread?.join(500)
        } catch (e: Exception) {
            Log.e("AudioBeepDetector", "Error joining thread: ${e.message}")
        }
        workerThread = null

        // Release ONLY here after the thread is dead
        synchronized(this) {
            try {
                audioRecord?.let {
                    if (it.state == AudioRecord.STATE_INITIALIZED) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e("AudioBeepDetector", "Error releasing AudioRecord: ${e.message}")
            }
            audioRecord = null
        }
    }

    companion object {
        private const val WINDOW_SIZE_STATIC = 175
        private const val BASE_MAG = 1e6f
        private val DEFAULT_MAG_THRESHOLD = (BASE_MAG * WINDOW_SIZE_STATIC).toFloat()

        fun createWithPrefs(context: Context, onBeep: (Float, Int) -> Unit): AudioBeepDetector {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val stored = prefs.getFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, Float.NaN)
            val threshold = if (!stored.isNaN() && stored > 0f) stored else DEFAULT_MAG_THRESHOLD
            return AudioBeepDetector(threshold, onBeep)
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float, Int) -> Unit,
            onAudioHealth: (Boolean) -> Unit
        ): AudioBeepDetector {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val stored = prefs.getFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, Float.NaN)
            val threshold = if (!stored.isNaN() && stored > 0f) stored else DEFAULT_MAG_THRESHOLD
            return AudioBeepDetector(threshold, onBeep, onAudioHealth)
        }

        fun startCalibration(
            context: Context,
            onProgress: (current: Int, total: Int) -> Unit,
            onFinished: (threshold: Float?) -> Unit
        ): AudioBeepDetector {
            var detector: AudioBeepDetector? = null
            val calibrationSession = GoertzelDetector.CalibrationSession(
                fallbackThreshold = DEFAULT_MAG_THRESHOLD,
                onProgress = onProgress,
                onFinished = { threshold ->
                    if (threshold != null) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                            .putFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, threshold)
                            .apply()
                    }
                    onFinished(threshold)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        detector?.stop()
                    }
                }
            )

            detector = AudioBeepDetector(
                magThreshold = DEFAULT_MAG_THRESHOLD / 1000f, // 20260319 before: 10f
                onBeep = { _, _ -> },
                onRawAudio = { samples ->
                    calibrationSession.processSamples(samples)
                }
            )

            detector.start()
            return detector
        }
    }
}
