package com.github.bocovp.geigergpx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlin.concurrent.thread

class AudioBeepDetector(
    private val context: Context? = null,
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val useBluetoothMicIfAvailable: Boolean = true,
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
    private var previousAudioMode: Int? = null
    private var scoEnabledByDetector = false
    private var bluetoothMicRoutingActive = false

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
                resetBluetoothAudioRouting()
                running = false
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            ar.release()
            resetBluetoothAudioRouting()
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
                resetBluetoothAudioRouting()
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
        // Configure Bluetooth routing FIRST and capture the result
        val bluetoothConfigured = configureBluetoothAudioRouting()
        
        // Select audio source based on actual Bluetooth setup success
        val audioSource = if (bluetoothConfigured && bluetoothMicRoutingActive) {
            Log.i(TAG, "Creating AudioRecord with VOICE_COMMUNICATION source for Bluetooth mic")
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            if (bluetoothConfigured) {
                Log.i(TAG, "Bluetooth configuration attempted but mic routing not active, using MIC source")
            } else {
                Log.i(TAG, "Bluetooth not configured, using MIC source")
            }
            MediaRecorder.AudioSource.MIC
        }

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
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed with source=${audioSource}")
            ar.release()
            return null
        }

        Log.i(TAG, "AudioRecord created successfully with audioSource=$audioSource, state=${ar.state}")
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
        resetBluetoothAudioRouting()
    }

    /**
     * Configures Bluetooth audio routing for TWS headsets and other Bluetooth devices.
     * Returns true if Bluetooth configuration was attempted and device was found,
     * false if Bluetooth was not available or skipped.
     */
    private fun configureBluetoothAudioRouting(): Boolean {
        val ctx = context ?: return false
        if (!useBluetoothMicIfAvailable) {
            Log.d(TAG, "Bluetooth mic usage disabled by preference")
            return false
        }

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (previousAudioMode == null) {
            previousAudioMode = am.mode
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted, cannot configure Bluetooth microphone routing")
            return false
        }

        val btDevice = findBluetoothMic(am)
        if (btDevice == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val deviceTypes = am.availableCommunicationDevices.joinToString { it.type.toString() }
                Log.d(TAG, "No connected Bluetooth microphone found. availableCommunicationDevices types=[$deviceTypes]")
            } else {
                Log.d(TAG, "No connected Bluetooth microphone found")
            }
            return false
        }

        Log.i(TAG, "Found Bluetooth device for microphone: type=${btDevice.type}")

        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val selected = am.setCommunicationDevice(btDevice)
                if (!selected) {
                    Log.w(TAG, "Failed to switch communication device to Bluetooth mic")
                    bluetoothMicRoutingActive = false
                    return false
                }
                bluetoothMicRoutingActive = true
                Log.i(TAG, "Successfully set communication device to Bluetooth mic")
            }

            if (btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                scoEnabledByDetector = true
                Log.i(TAG, "Bluetooth SCO mode enabled for microphone input")
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Bluetooth audio routing", e)
            bluetoothMicRoutingActive = false
            return false
        }
    }

    private fun resetBluetoothAudioRouting() {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            if (scoEnabledByDetector) {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                scoEnabledByDetector = false
                Log.i(TAG, "Bluetooth SCO mode disabled")
            }
            previousAudioMode?.let {
                am.mode = it
                previousAudioMode = null
            }
            bluetoothMicRoutingActive = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting Bluetooth audio routing", e)
        }
    }

    private fun findBluetoothMic(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
        return null
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
            return AudioBeepDetector(
                context = context.applicationContext,
                magThreshold = threshold,
                useBluetoothMicIfAvailable = useBluetoothMicIfAvailable(context),
                onBeep = onBeep
            )
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float, Int) -> Unit,
            onAudioHealth: (Boolean) -> Unit
        ): AudioBeepDetector {
            val threshold = storedThreshold(context)
            return AudioBeepDetector(
                context = context.applicationContext,
                magThreshold = threshold,
                useBluetoothMicIfAvailable = useBluetoothMicIfAvailable(context),
                onBeep = onBeep,
                onAudioHealth = onAudioHealth
            )
        }

        private fun storedThreshold(context: Context): Float {
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, Float.NaN)
            return if (!stored.isNaN() && stored > 0f) stored else DEFAULT_MAG_THRESHOLD
        }

        private fun useBluetoothMicIfAvailable(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, true)
        }
    }
}