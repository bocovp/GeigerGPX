package com.example.geigergpx

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.preference.PreferenceManager
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos

class AudioBeepDetector(
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {}
) {

    @Volatile
    private var running = false
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val SAMPLE_RATE = 44100

    private val FREQ_MAIN = 3276.0f
    private val WINDOW_SIZE = 175 // aligned: bin 13 is central frequency

    private val FREQ_LOW  = 3276.0f - 252f // bin 12
    private val FREQ_HIGH = 3276.0f + 252f // bin 14

    private val WINDOWS_IN_BUFFER = 64 // 0.25 sec delaly
    private val STEP_SIZE = 32

    private val SILENCE_WINDOWS_LIMIT = 4

    private val ONE_BEEP_MIN = 0.020
    private val ONE_BEEP_MAX = 0.035
    private val TWO_BEEP_MAX = 0.070

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

        fun coeff(freq: Float): Float
        {
            val omega = 2.0 * PI * freq / SAMPLE_RATE
            return 2.0f * cos(omega).toFloat()
        }

        val coeffMain = coeff(FREQ_MAIN)
        val coeffLow = coeff(FREQ_LOW)
        val coeffHigh = coeff(FREQ_HIGH)

        val hann = FloatArray(WINDOW_SIZE) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (WINDOW_SIZE - 1))).toFloat()
        }

        workerThread = thread(start = true, name = "BeepDetector")
        {
            // Устанавливаем приоритет для работы со звуком
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)


            val audioBuf = ShortArray(bufferSize)
            val processingBuf = ShortArray(bufferSize + WINDOW_SIZE * 2)
            var leftoverSamples = 0
            var totalSamplesProcessed: Long = 0 // Используем обработанные сэмплы для точности
            var audioHealthy = true

            var isBeeping = false
            var beepStartSample: Long = 0
            var silenceWindows = 0
            var currentBeepMaxMain = 0f

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

                    // FIX: Bounds check to prevent crash if read is unexpectedly large
                    if (leftoverSamples + read > processingBuf.size) {
                        leftoverSamples = 0
                    }

                    System.arraycopy(audioBuf, 0, processingBuf, leftoverSamples, read)

                    val totalInBuf = leftoverSamples + read

                    var pos = 0

                    while (pos + WINDOW_SIZE <= totalInBuf)
                    {
                        val currentWindowGlobalSample = totalSamplesProcessed + pos

                        var q1M = 0f; var q2M = 0f
                        var q1L = 0f; var q2L = 0f
                        var q1H = 0f; var q2H = 0f
                        var energy = 0f
                        for (i in 0 until WINDOW_SIZE) {
                            val s = processingBuf[pos + i].toFloat() * hann[i]
                            energy += s * s

                            val q0M = coeffMain * q1M - q2M + s
                            q2M = q1M; q1M = q0M

                            val q0L = coeffLow * q1L - q2L + s
                            q2L = q1L; q1L = q0L

                            val q0H = coeffHigh * q1H - q2H + s
                            q2H = q1H; q1H = q0H
                        }

                        val main =  (q1M*q1M + q2M*q2M - q1M*q2M*coeffMain) // / WINDOW_SIZE

                        val toneRatio = main / (energy + 1e-9f)

                        var detected = false

             //         Log.e("AudioBeepDetector", "main: ${main.toInt()}")
                        var dominance = -1f

                        if (main > magThreshold) {
                            val ratioThreshold = 20f
                        //    Log.e("AudioBeepDetector", "toneRatio: ${toneRatio}")
                            if (toneRatio > ratioThreshold) {
                                val low = (q1L * q1L + q2L * q2L - q1L * q2L * coeffLow) // / WINDOW_SIZE
                                val high = (q1H * q1H + q2H * q2H - q1H * q2H * coeffHigh) // / WINDOW_SIZE
                                val sideEnergy = low + high
                                dominance = main / (sideEnergy + 1e-9f)
                                val dominanceThreshold = 0.75f
                                detected = (dominance > dominanceThreshold)
                           //     if (detected) Log.e("AudioBeepDetector", "dominance: ${dominance}")
                         //   if (main > low * 1.2f && main > high * 1.2f) detected = true
                            }
                        }

                        if (detected) {
                            silenceWindows = 0
                            if (!isBeeping) {
                                isBeeping = true
                                beepStartSample = currentWindowGlobalSample
                                currentBeepMaxMain = main
                            } else if (main > currentBeepMaxMain) {
                                    currentBeepMaxMain = main
                            }
                        } else if (isBeeping) {
                            silenceWindows++
                    //        Log.e("AudioBeepDetector", "Loosing     toneRatio: ${toneRatio} dominance: ${dominance}")
                            if (silenceWindows > SILENCE_WINDOWS_LIMIT) {
                                val duration = (currentWindowGlobalSample  - beepStartSample).toDouble() / SAMPLE_RATE
                                processBeep(duration, currentBeepMaxMain)
                                isBeeping = false
                                currentBeepMaxMain = 0f
                            }
                        }
                        pos += STEP_SIZE
                    }
                    // 3. Prepare for next hardware read
                    // Save unprocessed samples (the "tail") to the start of the buffer
                    leftoverSamples = totalInBuf - pos
                    if (leftoverSamples > 0) {
                        System.arraycopy(processingBuf, pos, processingBuf, 0, leftoverSamples)
                    }
                    totalSamplesProcessed += pos // Increment by processed distance only

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

    private fun processBeep(duration: Double, peakMain: Float) {
        when {
            duration >= ONE_BEEP_MIN && duration <= ONE_BEEP_MAX -> {
                Log.e("AudioBeepDetector", "SINGLE duration: ${"%.3f".format(duration)}  peakMain: ${"%.2e".format(peakMain)}")
                onBeep(peakMain, 1)
            }
            duration > ONE_BEEP_MAX && duration <= TWO_BEEP_MAX -> {
                Log.e("AudioBeepDetector", "DOUBLE duration: ${"%.3f".format(duration)}  peakMain: ${"%.2e".format(peakMain)}")
                onBeep(peakMain, 1) /// OFF for now
            }
            else -> {
                Log.e("AudioBeepDetector", "       duration: ${"%.3f".format(duration)}  peakMain: ${"%.2e".format(peakMain)}")
                onBeep(peakMain, 0)
            }
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
        private const val BASE_MAG = 1e6f  *10f//for MIC
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
            val totalBeepCount = 10
            val peaks = mutableListOf<Float>()

            var detector: AudioBeepDetector? = null

            val callback: (Float, Int) -> Unit = { peakMain, _ ->
                if (peakMain.isFinite() && peaks.size < totalBeepCount) {
                    peaks.add(peakMain)
                    onProgress(peaks.size, totalBeepCount)

                    if (peaks.size == totalBeepCount) {
                        val median = peaks.sorted()[totalBeepCount/2].toFloat()
                        val rawThreshold = minOf( median / 2f, 2e9f)
                        val threshold = if (rawThreshold > 0f) rawThreshold else DEFAULT_MAG_THRESHOLD

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                            .putFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, threshold)
                            .apply()

                        onFinished(threshold)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            detector?.stop()
                        }
                    }
                }
            }

            detector = AudioBeepDetector(
                magThreshold = DEFAULT_MAG_THRESHOLD / 10f,
                onBeep = callback
            )

            detector.start()
            return detector
        }
    }
}