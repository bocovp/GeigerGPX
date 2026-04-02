package com.github.bocovp.geigergpx

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AudioBeepDetector(
    private val context: Context? = null,
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val useBluetoothMicIfAvailable: Boolean = true,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {},
    /**
     * Fired on the worker thread immediately before the recording loop starts,
     * after the actual sample rate has been resolved. Callers that create their
     * own [GoertzelDetector] (e.g. [CalibrationSession]) must use this rate —
     * it may differ from [GoertzelDetector.DEFAULT_SAMPLE_RATE] on the SCO path.
     */
    private val onRecordingStarted: (sampleRate: Int) -> Unit = {},
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
    private var communicationDeviceSetByDetector = false
    private var bluetoothMicRoutingActive = false
    private var preferredInputDevice: AudioDeviceInfo? = null

    fun start() {
        if (running) return
        running = true

        // Configure BT routing before the worker thread starts.
        // SCO start is asynchronous — the worker waits for SCO_AUDIO_STATE_CONNECTED
        // before creating AudioRecord so audio is not silently captured from the
        // built-in mic while the SCO channel is still being established.
        configureBluetoothAudioRouting()

        workerThread = thread(start = true, name = "BeepDetector") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            // Wait for the async SCO channel to connect before opening AudioRecord.
            // Without this wait the recorder is created while SCO is still negotiating,
            // so Android silently falls back to the built-in microphone.
            if (scoEnabledByDetector) {
                val ctx = context
                if (ctx != null) {
                    val connected = waitForScoConnection(ctx)
                    if (!connected) {
                        Log.w(TAG, "SCO did not connect within timeout; audio may come from built-in mic")
                    }
                }
            }

            // stop() may have been called while we were blocked in the SCO wait.
            if (!running) return@thread

            // createAudioRecord() probes and resolves the actual sample rate for this
            // session (SCO: 16000 or 8000 Hz; built-in mic: DEFAULT_SAMPLE_RATE).
            val created = createAudioRecord()
            if (created == null) {
                running = false
                resetBluetoothAudioRouting()
                return@thread
            }

            val (ar, initialBufferSize, actualSampleRate) = created
            audioRecord = ar

            try {
                ar.startRecording()
                if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "Failed to start recording")
                    ar.release()
                    resetBluetoothAudioRouting()
                    running = false
                    return@thread
                }
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                ar.release()
                resetBluetoothAudioRouting()
                running = false
                return@thread
            }

            Log.i(TAG, "Recording started — actualSampleRate=$actualSampleRate Hz, " +
                    "routedDeviceType=${ar.routedDevice?.type}")

            // Warn immediately if Android silently re-routes away from the chosen device.
            ar.addOnRoutingChangedListener({ record ->
                Log.w(TAG, "AudioRecord routing changed! now routedDeviceType=${record.routedDevice?.type}")
            }, null)

            // Notify callers of the resolved sample rate before the recording loop begins.
            // This allows external consumers (e.g. CalibrationSession) to construct their
            // own GoertzelDetector with the correct rate before any samples arrive.
            onRecordingStarted(actualSampleRate)

            // The internal detector is created here, after the actual sample rate is known,
            // so its frequency coefficients and duration calculations are correct.
            // freqMain differs between SCO and built-in paths because the exact bin 16
            // centre (3282 Hz) is used for SCO rather than the 44100 Hz bin 13 (3276 Hz).
            // Goertzel energy scales with window size and signal amplitude; SCO mics have
            // lower gain and a compressed codec, so the threshold is scaled down as a
            // starting point — tune further using onWindowAnalyzed log output.
            val effectiveThreshold = if (bluetoothMicRoutingActive) {
                val scaled = magThreshold / BT_MAG_THRESHOLD_DIVISOR
                Log.i(TAG, "BT mic active — magThreshold scaled from $magThreshold to $scaled")
                scaled
            } else {
                magThreshold
            }

            val freqMain = if (bluetoothMicRoutingActive) {
                GoertzelDetector.SCO_FREQ_MAIN
            } else {
                GoertzelDetector.DEFAULT_FREQ_MAIN
            }

            val detector = GoertzelDetector(
                magThreshold = effectiveThreshold,
                sampleRate   = actualSampleRate,
                freqMain     = freqMain
            )
            detector.onBeep = onBeep

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
                        audioBuf = restartAndReallocate(audioBuf, detector, actualSampleRate) ?: audioBuf
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
                        Log.w(TAG, "AudioRecord appears stuck — restarting")
                        audioBuf = restartAndReallocate(audioBuf, detector, actualSampleRate) ?: audioBuf
                        zeroBufferCount = 0
                        continue
                    }

                    val samples = audioBuf.copyOf(read)

                    if (onRawAudio != null) {
                        onRawAudio.invoke(samples)
                    } else {
                        detector.processSamples(samples)
                    }
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

    /**
     * Blocks the calling thread until the Bluetooth SCO channel signals CONNECTED
     * or DISCONNECTED/ERROR, with a [timeoutMs] safety net.
     * Returns false immediately if the thread is interrupted (e.g. by stop()).
     * Must be called from the worker thread, never from the main thread.
     */
    private fun waitForScoConnection(context: Context, timeoutMs: Long = 3000L): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        @Suppress("DEPRECATION")
        if (am.isBluetoothScoOn) {
            Log.d(TAG, "SCO already connected, skipping wait")
            return true
        }

        val latch = CountDownLatch(1)
        var connected = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                ) ?: return
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        connected = true
                        latch.countDown()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        latch.countDown()
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.d(TAG, "SCO wait interrupted (stop() called)")
            Thread.currentThread().interrupt()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }

        Log.i(TAG, "SCO connection wait finished: connected=$connected")
        return connected
    }

    /**
     * Probes for the highest SCO-compatible sample rate when BT routing is active,
     * falling back through [SCO_SAMPLE_RATES] in order. Returns [SAMPLE_RATE] for
     * the built-in mic path.
     */
    private fun resolveSampleRate(): Int {
        if (!bluetoothMicRoutingActive) return SAMPLE_RATE
        for (rate in SCO_SAMPLE_RATES) {
            val result = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (result > 0) {
                Log.i(TAG, "SCO sample rate resolved to $rate Hz")
                return rate
            }
        }
        Log.w(TAG, "No SCO-compatible rate accepted; falling back to $SAMPLE_RATE Hz")
        return SAMPLE_RATE
    }

    private fun restartAndReallocate(
        current: ShortArray,
        detector: GoertzelDetector,
        sampleRate: Int
    ): ShortArray? {
        val newSize = restartAudioRecord(sampleRate) ?: return null
        detector.reset()
        return if (newSize != current.size) ShortArray(newSize) else current
    }

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

    /**
     * Creates and initialises an [AudioRecord].
     * Returns a Triple of (recorder, bufferSize, actualSampleRate), or null on failure.
     */
    private fun createAudioRecord(): Triple<AudioRecord, Int, Int>? {
        val audioSource = if (bluetoothMicRoutingActive) {
            Log.i(TAG, "Creating AudioRecord with VOICE_COMMUNICATION source for Bluetooth mic")
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            Log.i(TAG, "Creating AudioRecord with MIC source")
            MediaRecorder.AudioSource.MIC
        }

        val rate = resolveSampleRate()

        val minBuffer = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid minBufferSize: $minBuffer for rate=$rate")
            return null
        }

        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * WINDOWS_IN_BUFFER)

        val ar = AudioRecord(
            audioSource,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed with source=$audioSource rate=$rate")
            ar.release()
            return null
        }

        // setPreferredDevice is only meaningful when setCommunicationDevice() was NOT used
        // (legacy SCO fallback or pre-31 path). On API >= 31 the two APIs conflict.
        if (!communicationDeviceSetByDetector) {
            preferredInputDevice?.let { device ->
                val preferredSet = ar.setPreferredDevice(device)
                if (preferredSet) {
                    Log.i(TAG, "AudioRecord preferred input device set to type=${device.type}")
                } else {
                    Log.w(TAG, "Failed to set preferred AudioRecord input device type=${device.type}")
                }
            }
        }

        Log.i(TAG, "AudioRecord created — source=$audioSource, rate=$rate Hz, " +
                "routedDeviceType=${ar.routedDevice?.type}")
        return Triple(ar, bufferSize, rate)
    }

    private fun restartAudioRecord(sampleRate: Int): Int? {
        releaseRecorder(swapAudioRecord(null))

        val created = createAudioRecord()
        if (created == null) {
            Log.e(TAG, "AudioRecord recreation failed")
            return null
        }

        val (newRecorder, bufferSize, _) = created

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

        // Interrupt the worker so it unblocks immediately from the SCO wait.
        workerThread?.interrupt()

        val ar = swapAudioRecord(null)

        try {
            workerThread?.join(4000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while joining worker thread")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Error joining worker thread: ${e.message}")
        }
        workerThread = null

        releaseRecorder(ar)
        resetBluetoothAudioRouting()
    }

    private fun configureBluetoothAudioRouting(): Boolean {
        val ctx = context ?: return false
        bluetoothMicRoutingActive = false

        if (!useBluetoothMicIfAvailable) {
            Log.d(TAG, "Bluetooth mic usage disabled by preference")
            return false
        }

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (previousAudioMode == null) {
            previousAudioMode = am.mode
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "BLUETOOTH_CONNECT not granted, skipping Bluetooth microphone routing")
            return false
        }

        val btDevice = findBluetoothMic(am)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (btDevice == null) {
                val deviceTypes = am.availableCommunicationDevices.joinToString { it.type.toString() }
                Log.d(TAG, "No connected Bluetooth microphone found. availableCommunicationDevices=[$deviceTypes]")
                return false
            }

            val selected = am.setCommunicationDevice(btDevice)
            if (!selected) {
                Log.w(TAG, "Failed to switch communication device to Bluetooth mic; trying SCO fallback")
                val scoEnabled = tryEnableLegacyBluetoothSco(am)
                if (scoEnabled) preferredInputDevice = btDevice
                return scoEnabled
            }

            bluetoothMicRoutingActive = true
            communicationDeviceSetByDetector = true
            preferredInputDevice = btDevice
            Log.i(TAG, "Communication device switched to Bluetooth mic (type=${btDevice.type})")

            if (btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                scoEnabledByDetector = true
                Log.i(TAG, "Bluetooth SCO mode enabled alongside setCommunicationDevice")
            }
            return true
        } else {
            val scoEnabled = tryEnableLegacyBluetoothSco(am)
            if (scoEnabled && btDevice != null) preferredInputDevice = btDevice
            return scoEnabled
        }
    }

    private fun tryEnableLegacyBluetoothSco(am: AudioManager): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false

        @Suppress("DEPRECATION")
        val bluetoothLikelyConnected = am.isBluetoothA2dpOn || am.isBluetoothScoOn
        if (!bluetoothLikelyConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }

        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
            scoEnabledByDetector = true
            bluetoothMicRoutingActive = true
            Log.i(TAG, "Bluetooth SCO fallback enabled for microphone input")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Bluetooth SCO fallback", e)
            false
        }
    }

    private fun resetBluetoothAudioRouting() {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSetByDetector) {
            am.clearCommunicationDevice()
            communicationDeviceSetByDetector = false
        }
        if (scoEnabledByDetector) {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            scoEnabledByDetector = false
        }
        previousAudioMode?.let {
            am.mode = it
            previousAudioMode = null
        }
        bluetoothMicRoutingActive = false
        preferredInputDevice = null
    }

    private fun findBluetoothMic(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val communicationDevice = audioManager.communicationDevice
            if (communicationDevice != null &&
                (communicationDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    communicationDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            ) {
                return communicationDevice
            }

            audioManager.availableCommunicationDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }?.let { return it }
        }

        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    companion object {
        private const val TAG = "AudioBeepDetector"

        private const val WINDOW_SIZE       = GoertzelDetector.DEFAULT_WINDOW_SIZE
        private const val SAMPLE_RATE       = GoertzelDetector.DEFAULT_SAMPLE_RATE
        private const val WINDOWS_IN_BUFFER = 64
        private const val ZERO_BUFFER_LIMIT = 20

        // Wideband HFP (16000 Hz) is tried first because the target frequency (≈3282 Hz)
        // is well within its passband. Narrowband (8000 Hz) rolls off around 3400 Hz,
        // placing the target right at the edge.
        private val SCO_SAMPLE_RATES = intArrayOf(16000, 8000)

        // Starting divisor for BT threshold scaling. SCO mics have lower gain and HFP
        // applies lossy codec compression, so raw Goertzel energy values are much smaller
        // than on the built-in mic. Tune via onWindowAnalyzed peak main values during a beep.
        private const val BT_MAG_THRESHOLD_DIVISOR = 10f

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
