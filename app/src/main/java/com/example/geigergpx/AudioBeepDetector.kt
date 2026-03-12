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
    private val onBeep: (Float) -> Unit
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

        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * 8)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED)
        {
            running = false
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
            val processingBuf = ShortArray(bufferSize + WINDOW_SIZE)
            var leftoverSamples = 0
            var totalSamplesProcessed: Long = 0 // Используем обработанные сэмплы для точности

            var isBeeping = false
            var beepStartSample: Long = 0
            var silenceWindows = 0
            var currentBeepMaxMain = 0f

            try
            {
                while (running)
                {
                    val read = ar.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) continue
                    if (audioBuf.sum() == 0)
                    {
                        Log.d("MYTAG", "Error: empty audioBuff")
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

                        // Hanning compensation might be here; not required
                        val main =  (q1M*q1M + q2M*q2M - q1M*q2M*coeffMain) // / WINDOW_SIZE

                        var detected = false
                        if (main > magThreshold) {
                            val low = (q1L * q1L + q2L * q2L - q1L * q2L * coeffLow) // / WINDOW_SIZE
                            val high = (q1H * q1H + q2H * q2H - q1H * q2H * coeffHigh) // / WINDOW_SIZE

                            if (main > low * 1.2f && main > high * 1.2f) {
                                detected = true
                            }
                        }

                        if (detected) {
                            silenceWindows = 0
                            if (!isBeeping) {
                                isBeeping = true
                                beepStartSample = currentWindowGlobalSample
                                currentBeepMaxMain = main
                            } else {
                                if (main > currentBeepMaxMain) {
                                    currentBeepMaxMain = main
                                }
                            }
                        } else if (isBeeping) {
                            silenceWindows++
                            if (silenceWindows > SILENCE_WINDOWS_LIMIT) {
                                val beepEndSample = currentWindowGlobalSample
                                val duration = (beepEndSample - beepStartSample).toDouble() / SAMPLE_RATE
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
                    if (leftoverSamples > WINDOW_SIZE) leftoverSamples = WINDOW_SIZE
                    if (leftoverSamples > 0) {
                        System.arraycopy(processingBuf, pos, processingBuf, 0, leftoverSamples)
                    }
                    totalSamplesProcessed += pos // Increment by processed distance only

                }
            } catch (e: Exception) {
                Log.e("AudioBeepDetector", "Thread error", e)
            } finally {
                ar.stop()
                ar.release()
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

    fun stop()
    {
        running = false

        try
        {
            audioRecord?.stop()
        } catch(_:Exception){}

        workerThread?.join(500)
        workerThread = null

        try
        {
            audioRecord?.release()
        } catch(_:Exception){}

        audioRecord = null
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

        fun startCalibration(
            context: Context,
            onProgress: (current: Int, total: Int) -> Unit,
            onFinished: (threshold: Float?) -> Unit
        ): AudioBeepDetector {
            val totalBeepCount = 10
            val peaks = mutableListOf<Float>()

            var detector: AudioBeepDetector? = null

            val callback: (Float) -> Unit = { peakMain ->
                if (!peakMain.isFinite() || peaks.size >= totalBeepCount) {
                    // Ignore invalid or extra peaks
                } else {
                    peaks.add(peakMain)
                    onProgress(peaks.size, totalBeepCount)

                    if (peaks.size == totalBeepCount) {
                        val avg = peaks.map { it.toDouble() }.average().toFloat()
                        val rawThreshold = avg / 2f
                        val threshold = if (rawThreshold > 0f) rawThreshold else DEFAULT_MAG_THRESHOLD

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                            .putFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, threshold)
                            .apply()

                        onFinished(threshold)
                        detector?.stop()
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