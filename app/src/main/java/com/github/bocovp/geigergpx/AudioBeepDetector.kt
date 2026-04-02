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
    private val bluetoothMagThreshold: Float = DEFAULT_BLUETOOTH_MAG_THRESHOLD,
    private val useBluetoothMicIfAvailable: Boolean = true,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {},
    private val onAudioStatus: (String, Int) -> Unit = { _, _ -> },
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
    @Volatile private var lastPublishedAudioStatus: String? = null

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

            // ar.routedDevice AFTER startRecording() is the OS ground truth for what
            // device is actually capturing audio. We use it — not our setup intent — to
            // determine whether BT is genuinely active and to publish the working status.
            // This also corrects bluetoothMicRoutingActive if Android silently fell back
            // to the built-in mic despite our routing setup.
            updateRoutingAndPublishWorkingStatus(ar.routedDevice)

            // Cross-check: warn if the OS reports a BT device but the sample rate we
            // resolved is 44100 Hz. Real SCO hardware never runs at 44100 Hz — if this
            // fires it means getMinBufferSize() accepted a non-SCO rate while we thought
            // BT was routing, which implies Android resampling is occurring silently.
            if (bluetoothMicRoutingActive && actualSampleRate == SAMPLE_RATE) {
                Log.w(TAG, "routedDevice is BT but sampleRate=$actualSampleRate Hz — " +
                        "OS may be resampling from SCO rate; detection quality may be degraded")
            }
            // Inverse check: if routing fell back to built-in mic but we opened at a
            // SCO rate, note it. Detection still works (built-in supports 16000 Hz) but
            // the threshold was calibrated for SCO, so results may differ.
            if (!bluetoothMicRoutingActive && actualSampleRate != SAMPLE_RATE) {
                Log.w(TAG, "routedDevice is built-in mic but sampleRate=$actualSampleRate Hz " +
                        "(SCO rate); BT routing fell back. Threshold calibrated for SCO may not apply.")
            }

            Log.i(TAG, "Recording confirmed — actualSampleRate=$actualSampleRate Hz, " +
                    "routedDeviceType=${ar.routedDevice?.type}, bluetoothActive=$bluetoothMicRoutingActive")

            // Re-publish routing changes triggered by the OS mid-session (e.g. headset
            // disconnected, another app steals the communication device).
            ar.addOnRoutingChangedListener({ record ->
                val newDevice = record.routedDevice
                Log.w(TAG, "AudioRecord routing changed! now routedDeviceType=${newDevice?.type}")
                updateRoutingAndPublishWorkingStatus(newDevice)
            }, null)

            // Notify callers of the resolved sample rate before the recording loop begins.
            // This allows external consumers (e.g. CalibrationSession) to construct their
            // own GoertzelDetector with the correct rate before any samples arrive.
            onRecordingStarted(actualSampleRate)

            // Threshold is NOT scaled for BT here. Calibration is expected to be
            // performed separately in BT mode, so the stored threshold already reflects
            // the actual SCO mic sensitivity and codec characteristics. Applying an
            // additional divisor would create a mismatch between calibration and detection.

            val selectedThreshold = if (bluetoothMicRoutingActive) bluetoothMagThreshold else magThreshold
            val detector = GoertzelDetector(
                magThreshold = selectedThreshold,
                sampleRate   = actualSampleRate
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
                        publishAudioStatus("Recorder uninitialized, restarting", AUDIO_STATUS_ERROR)
                        audioBuf = restartAndReallocate(audioBuf, detector, actualSampleRate) ?: audioBuf
                        continue
                    }

                    val read = currentAr.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) {
                        zeroBufferCount++
                        Log.e(TAG, "Hardware read error: $read")
                        publishAudioStatus("Hardware read error", AUDIO_STATUS_ERROR)
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
                            publishAudioStatus("Empty audio buffer", AUDIO_STATUS_ERROR)
                        }
                    } else {
                        zeroBufferCount = 0
                        if (!audioHealthy) {
                            audioHealthy = true
                            onAudioHealth(true)
                            publishWorkingStatus()
                        }
                    }

                    if (zeroBufferCount >= ZERO_BUFFER_LIMIT) {
                        Log.w(TAG, "AudioRecord appears stuck — restarting")
                        publishAudioStatus("AudioRecord appears stuck — restarting", AUDIO_STATUS_ERROR)
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
     *
     * Note: this probes what AudioRecord will accept for the given parameters —
     * it does not verify that the active audio device is actually BT. The result
     * is cross-checked against [ar.routedDevice] after [startRecording] and a
     * warning is logged if they are inconsistent.
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
                updateRoutingAndPublishWorkingStatus(newRecorder.routedDevice)
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
            if (isBluetoothInputDevice(communicationDevice)) {
                return communicationDevice
            }

            // Prefer the classic SCO/HFP profile first because it always supports
            // bidirectional voice audio. Some BLE headset routes can be output-only;
            // selecting them here makes status look "bluetooth" while capture still
            // falls back to the built-in microphone.
            audioManager.availableCommunicationDevices
                .firstOrNull { device ->
                    device.isSource && device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                ?.let { return it }

            audioManager.availableCommunicationDevices
                .firstOrNull { device ->
                    device.isSource && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                ?.let { return it }
        }

        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { device ->
            device.isSource && (
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            )
        }
    }

    private fun isBluetoothInputDevice(device: AudioDeviceInfo?): Boolean {
        val resolved = device ?: return false
        return resolved.isSource && (
            resolved.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                resolved.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        )
    }

    /**
     * Updates [bluetoothMicRoutingActive] from the OS ground truth ([ar.routedDevice]
     * after [startRecording]) and publishes the working status. Using the actual routed
     * device — rather than our setup intent — is the only way to confirm that audio
     * is genuinely coming from a BT device: if Android silently fell back to the
     * built-in mic, this will show "Working" instead of "Working (bluetooth)".
     *
     * This is called both at startup and from the [OnRoutingChangedListener] so the
     * status remains accurate if the OS re-routes mid-session.
     */
    private fun updateRoutingAndPublishWorkingStatus(device: AudioDeviceInfo?) {
        bluetoothMicRoutingActive = isBluetoothInputDevice(device)
        publishWorkingStatus()
    }

    private fun publishWorkingStatus() {
        val status = if (bluetoothMicRoutingActive) "Working (bluetooth)" else "Working"
        publishAudioStatus(status, AUDIO_STATUS_WORKING)
    }

    private fun publishAudioStatus(status: String, errorCode: Int) {
        if (lastPublishedAudioStatus == status) return
        lastPublishedAudioStatus = status
        onAudioStatus(status, errorCode)
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

        private const val BASE_MAG = 1e6f
        const val DEFAULT_MAG_THRESHOLD = BASE_MAG * WINDOW_SIZE
        const val DEFAULT_BLUETOOTH_MAG_THRESHOLD = BASE_MAG * WINDOW_SIZE
        const val AUDIO_STATUS_WAITING = 0
        const val AUDIO_STATUS_WORKING = 1
        const val AUDIO_STATUS_ERROR   = 2

        fun createWithPrefs(context: Context, onBeep: (Float, Int) -> Unit): AudioBeepDetector {
            val threshold = storedThreshold(context, bluetooth = false)
            val bluetoothThreshold = storedThreshold(context, bluetooth = true)
            return AudioBeepDetector(
                context = context.applicationContext,
                magThreshold = threshold,
                bluetoothMagThreshold = bluetoothThreshold,
                useBluetoothMicIfAvailable = useBluetoothMicIfAvailable(context),
                onBeep = onBeep
            )
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float, Int) -> Unit,
            onAudioHealth: (Boolean) -> Unit,
            onAudioStatus: (String, Int) -> Unit
        ): AudioBeepDetector {
            val threshold = storedThreshold(context, bluetooth = false)
            val bluetoothThreshold = storedThreshold(context, bluetooth = true)
            return AudioBeepDetector(
                context = context.applicationContext,
                magThreshold = threshold,
                bluetoothMagThreshold = bluetoothThreshold,
                useBluetoothMicIfAvailable = useBluetoothMicIfAvailable(context),
                onBeep = onBeep,
                onAudioHealth = onAudioHealth,
                onAudioStatus = onAudioStatus
            )
        }

        fun storedThreshold(context: Context, bluetooth: Boolean): Float {
            val key = if (bluetooth) {
                SettingsFragment.KEY_BLUETOOTH_AUDIO_THRESHOLD
            } else {
                SettingsFragment.KEY_AUDIO_THRESHOLD
            }
            val fallback = if (bluetooth) DEFAULT_BLUETOOTH_MAG_THRESHOLD else DEFAULT_MAG_THRESHOLD
            val stored = PreferenceManager.getDefaultSharedPreferences(context).getFloat(key, Float.NaN)
            return if (!stored.isNaN() && stored > 0f) stored else fallback
        }

        fun isBluetoothMicAvailable(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBluetoothCommunicationDevice = am.availableCommunicationDevices.any { device ->
                    device.isSource && (
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    )
                }
                if (hasBluetoothCommunicationDevice) return true
            }

            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { device ->
                device.isSource && (
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                )
            }
        }

        private fun useBluetoothMicIfAvailable(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, true)
        }
    }
}
