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
    private val onBeep: (Float) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {}
) {

    @Volatile
    private var running = false
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val SAMPLE_RATE = 44100

    private val FREQ_MAIN = 3276.0f
    private val FREQ_LOW  = 3276.0f - 200f
    private val FREQ_HIGH = 3276.0f + 200f

    private val WINDOW_SIZE = 128
    private val STEP_SIZE = 32

    private val SILENCE_WINDOWS_LIMIT = 4

    private val ONE_BEEP_MIN = 0.020
    private val ONE_BEEP_MAX = 0.035
    private val TWO_BEEP_MAX = 0.070

    fun start()
    {

        if (running) return
        running = true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // 64*128/44100. = 0.18 seconds
        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * 64)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize*2// Double it for safety/internal headroom
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED)
        {
            running = false
            ar.release() // Release immediately if failed
            return
        }

        audioRecord = ar
        ar.startRecording()

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

            try
            {
                while (running)
                {
                    val currentAr  = audioRecord ?: break

                    if (currentAr.state != AudioRecord.STATE_INITIALIZED) break
                    val read = currentAr.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) continue
                    val isZero = audioBuf.all { it == 0.toShort() }
                    if (isZero) {
                        if (audioHealthy) {
                            Log.d("MYTAG", "Error: empty audioBuff")
                            audioHealthy = false
                            onAudioHealth(false)
                        }
                    } else if (!audioHealthy) {
                        audioHealthy = true
                        onAudioHealth(true)
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

                        for (i in 0 until WINDOW_SIZE) {
                            val s = processingBuf[pos + i].toFloat() * hann[i]

                            val q0M = coeffMain * q1M - q2M + s
                            q2M = q1M; q1M = q0M

                            val q0L = coeffLow * q1L - q2L + s
                            q2L = q1L; q1L = q0L

                            val q0H = coeffHigh * q1H - q2H + s
                            q2H = q1H; q1H = q0H
                        }

                        val main =  (q1M*q1M + q2M*q2M - q1M*q2M*coeffMain) // / WINDOW_SIZE

                        var detected = false
                        if (main > magThreshold) {
                            val low = (q1L * q1L + q2L * q2L - q1L * q2L * coeffLow) // / WINDOW_SIZE
                            val high = (q1H * q1H + q2H * q2H - q1H * q2H * coeffHigh) // / WINDOW_SIZE

                            if (main > low * 1.2f && main > high * 1.2f) detected = true
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
                // NOTE: Do NOT call ar.release() here.
                // Let the stop() function handle cleanup to avoid race conditions.
                try {
                    if (ar.state == AudioRecord.STATE_INITIALIZED) ar.stop()
                } catch (e: Exception) {}
            }
        }
    }

    private fun processBeep(duration: Double, peakMain: Float)
    {
        when
        {
            duration >= ONE_BEEP_MIN && duration <= ONE_BEEP_MAX -> {
                onBeep(peakMain)
            }
            duration > ONE_BEEP_MAX && duration <= TWO_BEEP_MAX -> {
                onBeep(peakMain)
                onBeep(peakMain)
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
        private const val WINDOW_SIZE_STATIC = 128
        private const val BASE_MAG = 1e6f
        private val DEFAULT_MAG_THRESHOLD = (BASE_MAG * WINDOW_SIZE_STATIC).toFloat()

        fun createWithPrefs(context: Context, onBeep: (Float) -> Unit): AudioBeepDetector {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val stored = prefs.getFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, Float.NaN)
            val threshold = if (!stored.isNaN() && stored > 0f) stored else DEFAULT_MAG_THRESHOLD
            return AudioBeepDetector(threshold, onBeep)
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float) -> Unit,
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

            val callback: (Float) -> Unit = { peakMain ->
                if (peakMain.isFinite() && peaks.size < totalBeepCount) {
                    peaks.add(peakMain)
                    onProgress(peaks.size, totalBeepCount)

                    if (peaks.size == totalBeepCount) {
                        val avg = peaks.average().toFloat()
                        val rawThreshold = avg / 2f
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