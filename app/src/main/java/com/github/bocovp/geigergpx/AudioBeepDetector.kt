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
    /**
     * If provided, raw audio samples are forwarded to this callback instead of
     * being processed by the internal [GoertzelDetector]. Intended for external
     * audio consumers (e.g. recording, visualization) that replace built-in detection.
     */
    private val onRawAudio: ((ShortArray) -> Unit)? = null
) {

    @Volatile private var running = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        val created = createAudioRecord()
        if (created == null) {
            running = false
            return
        }

        val (ar, initialBufferSize) = created
        audioRecord = ar

        try {
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "Failed to start recording")
                ar.release()
                running = false
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            ar.release()
            running = false
            return
        }

        val detector = GoertzelDetector(magThreshold)
        detector.onBeep = onBeep

        workerThread = thread(start = true, name = "BeepDetector") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            var audioBuf        = ShortArray(initialBufferSize)
            var audioHealthy    = true
            var zeroBufferCount = 0

            try {
                while (running) {
                    val currentAr = audioRecord
                    if (currentAr == null) {
                        Thread.sleep(20)
                        continue
                    }

                    if (currentAr.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "Recorder uninitialized, restarting")
                        audioBuf = restartAndReallocate(audioBuf, detector) ?: audioBuf
                        continue
                    }

                    val read = currentAr.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) {
                        zeroBufferCount++
                        Log.e(TAG, "Hardware read error: $read")
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
                            Log.d(TAG, "Empty audio buffer")
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
                        Log.w(TAG, "AudioRecord appears stuck —w restarting")
                        audioBuf = restartAndReallocate(audioBuf, detector) ?: audioBuf
                        zeroBufferCount = 0
                        continue
                    }

                    val samples = audioBuf.copyOf(read)

                    if (onRawAudio != null) {
                        onRawAudio.invoke(samples)
                    } else {
//                        Log.w(TAG, "read $read\t Thread if: ${Thread.currentThread().id} Configured Rate: ${currentAr.sampleRate} Hz")
                        detector.processSamples(samples)
                    }
                    //onRawAudio?.invoke(samples)
                    //detector.processSamples(samples)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Worker thread error", e)
            } finally {
                // Swap atomically so stop() and finally never release the same instance.
                releaseRecorder(swapAudioRecord(null))
            }
        }
    }

    // Restarts AudioRecord and returns a reallocated buffer if the size changed,
    // or the original buffer if the size is unchanged, or null on failure.
    private fun restartAndReallocate(current: ShortArray,
                                     detector: GoertzelDetector): ShortArray? {
        val newSize = restartAudioRecord() ?: return null
        detector.reset()             // clear stale state before new stream
        return if (newSize != current.size) ShortArray(newSize) else current
    }

    // Atomically swaps audioRecord for the given value and returns the old one.
    @Synchronized
    private fun swapAudioRecord(new: AudioRecord?): AudioRecord? {
        val old = audioRecord
        audioRecord = new
        return old
    }

    private fun releaseRecorder(ar: AudioRecord?) {
        ar ?: return
        try {
            if (ar.state == AudioRecord.STATE_INITIALIZED) ar.stop()
            ar.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }

    private fun createAudioRecord(): Pair<AudioRecord, Int>? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid minBufferSize: $minBuffer")
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
            Log.e(TAG, "AudioRecord initialization failed")
            ar.release()
            return null
        }

        return Pair(ar, bufferSize)
    }

    // Only ever called from the worker thread. Uses swapAudioRecord to stay
    // race-free with stop(), which may run concurrently on the main thread.
    private fun restartAudioRecord(): Int? {
        releaseRecorder(swapAudioRecord(null))

        val created = createAudioRecord()
        if (created == null) {
            Log.e(TAG, "AudioRecord recreation failed")
            return null
        }

        val (newRecorder, bufferSize) = created

        return try {
            newRecorder.startRecording()
            if (newRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "Restarted AudioRecord failed to start")
                newRecorder.release()
                null
            } else {
                audioRecord = newRecorder
                bufferSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting restarted AudioRecord", e)
            try { newRecorder.release() } catch (_: Exception) {}
            null
        }
    }

    fun stop() {
        running = false

        // Grab and clear the reference BEFORE joining. The worker's finally
        // block uses swapAudioRecord(null) for the same reason — whichever
        // runs first gets the live instance; the other gets null and no-ops.
        val ar = swapAudioRecord(null)

        try {
            workerThread?.join(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining worker thread: ${e.message}")
        }
        workerThread = null

        releaseRecorder(ar)
    }

    companion object {
        private const val TAG              = "AudioBeepDetector"

        private const val WINDOW_SIZE = GoertzelDetector.DEFAULT_WINDOW_SIZE
        private const val SAMPLE_RATE = GoertzelDetector.DEFAULT_SAMPLE_RATE
        private const val WINDOWS_IN_BUFFER = 64   // ~0.25 s buffer
        private const val ZERO_BUFFER_LIMIT = 20

        private const val BASE_MAG = 1e6f
        const val DEFAULT_MAG_THRESHOLD = BASE_MAG * WINDOW_SIZE

        fun createWithPrefs(context: Context, onBeep: (Float, Int) -> Unit): AudioBeepDetector {
            val threshold = storedThreshold(context)
            return AudioBeepDetector(threshold, onBeep)
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float, Int) -> Unit,
            onAudioHealth: (Boolean) -> Unit
        ): AudioBeepDetector {
            val threshold = storedThreshold(context)
            return AudioBeepDetector(threshold, onBeep, onAudioHealth)
        }

        private fun storedThreshold(context: Context): Float {
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, Float.NaN)
            return if (!stored.isNaN() && stored > 0f) stored else DEFAULT_MAG_THRESHOLD
        }
    }
}
