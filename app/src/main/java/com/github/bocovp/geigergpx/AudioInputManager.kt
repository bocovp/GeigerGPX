package com.github.bocovp.geigergpx

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioInputManager(
    private val context: Context? = null,
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val bluetoothMagThreshold: Float = DEFAULT_BLUETOOTH_MAG_THRESHOLD,
    private val useBluetoothMicIfAvailable: Boolean = false,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {},
    private val onAudioStatus: (String, Int) -> Unit = { _, _ -> },
    private val onRecordingStarted: (sampleRate: Int) -> Unit = {},
    private val onRawAudio: ((ShortArray) -> Unit)? = null
) {

    private val running = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null
    private var previousAudioMode: Int? = null
    private var scoEnabledByDetector = false
    private var communicationDeviceSetByDetector = false
    private var bluetoothMicRoutingActive = false
    private var preferredInputDevice: AudioDeviceInfo? = null
    @Volatile private var lastPublishedAudioStatus: String? = null
    private var audioFocusRequest: Any? = null // Holds the API 26+ AudioFocusRequest

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "start() called but detector is already running")
            return
        }
        Log.i(TAG, "Starting AudioBeepDetector worker thread...")

        workerThread = thread(start = true, name = "BeepDetector") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            var audioBuf: ShortArray? = null

            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Log.i(TAG, "--- Outer Loop: Initializing Audio Hardware ---")

                    val ctx = context
                    if (ctx == null) { Thread.sleep(1000); continue }

                    if (useBluetoothMicIfAvailable && !bluetoothMicRoutingActive) {
                        publishAudioStatus("Initializing Bluetooth...", AUDIO_STATUS_WAITING)
                        configureBluetoothAudioRouting()

                        if (!bluetoothMicRoutingActive) {
                            Log.w(TAG, "Bluetooth routing not established. Retrying...")
                            resetBluetoothAudioRouting()
                            Thread.sleep(2000)
                            continue
                        }

                        // Only wait for the SCO broadcast when SCO was actually started.
                        // BLE headsets (TYPE_BLE_HEADSET) use setCommunicationDevice() alone —
                        // no SCO link is opened, no broadcast fires. Calling waitForScoConnection
                        // for BLE would always time out and loop forever.
                        if (scoEnabledByDetector) {
                            Log.i(TAG, "Waiting for Bluetooth SCO hardware connection...")
                            val connected = waitForScoConnection(ctx)
                            if (!connected) {
                                Log.w(TAG, "SCO connection failed/timeout. Retrying...")
                                publishAudioStatus("Bluetooth mic timeout, retrying...", AUDIO_STATUS_WAITING)
                                resetBluetoothAudioRouting()
                                Thread.sleep(2000)
                                continue
                            }
                        }
                    }

                    val created = createAudioRecord()
                    if (created == null) {
                        Log.e(TAG, "Failed to create AudioRecord. Retrying...")
                        publishAudioStatus("Microphone unavailable, retrying...", AUDIO_STATUS_ERROR)
                        Thread.sleep(2000)
                        continue
                    }

                    val (ar, bufferSize, actualSampleRate) = created
                    audioRecord = ar

                    try {
                        ar.startRecording()
                        // Brief settle time for Samsung M01 and similar hardware that need a
                        // moment to complete the HFP handshake after startRecording() returns.
                        Thread.sleep(500)

                        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            throw IllegalStateException("AudioRecord failed to start")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "startRecording failed", e)
                        releaseRecorder(swapAudioRecord(null))
                        resetBluetoothAudioRouting()
                        Thread.sleep(1000)
                        continue
                    }

                    var currentDevice = ar.routedDevice
                    var pollCount = 0

// Poll for up to 1 second if the OS is slow to report the routed device
                    while (currentDevice == null && pollCount < 10) {
                        Thread.sleep(100)
                        currentDevice = ar.routedDevice
                        pollCount++
                    }

// Strictly evaluate the actual hardware device. No falling back to 'intent'.
                    val actuallyBt = isBluetoothInputDevice(currentDevice)

                    Log.i(TAG, "Recording confirmed — sampleRate=$actualSampleRate Hz, BT=$actuallyBt, " +
                            "routedDeviceType=${currentDevice?.type}")

                    if (useBluetoothMicIfAvailable && !actuallyBt) {
                        Log.w(TAG, "OS routed to built-in mic despite BT setup. Cleaning up and retrying.")
                        publishAudioStatus("Bluetooth rejected by OS, retrying...", AUDIO_STATUS_WAITING)
                        releaseRecorder(swapAudioRecord(null))
                        resetBluetoothAudioRouting()
                        Thread.sleep(2000)
                        continue
                    }

                    updateRoutingAndPublishWorkingStatus(currentDevice)
                    onRecordingStarted(actualSampleRate)


                    val selectedThreshold = if (actuallyBt) bluetoothMagThreshold else magThreshold
                    val detector = GoertzelDetector(selectedThreshold, actualSampleRate).apply {
                        onBeep = this@AudioInputManager.onBeep
                    }

                    if (audioBuf == null || audioBuf.size != bufferSize) {
                        audioBuf = ShortArray(bufferSize)
                    }

                    val innerLoopBreak = AtomicBoolean(false)

                    ar.addOnRoutingChangedListener({ record ->
                        val newDevice = record.routedDevice
                        Log.w(TAG, "Routing changed mid-session! type=${newDevice?.type}")
                        if (useBluetoothMicIfAvailable && !isBluetoothInputDevice(newDevice)) {
                            Log.e(TAG, "Bluetooth lost mid-session. Breaking inner loop.")
                            innerLoopBreak.set(true)
                        } else {
                            updateRoutingAndPublishWorkingStatus(newDevice)
                        }
                    }, null)

                    var zeroBufferCount = 0
                    var audioHealthy = true

                    while (running.get() && !innerLoopBreak.get() && !Thread.currentThread().isInterrupted) {
                        val currentAr = audioRecord ?: break
                        val read = currentAr.read(audioBuf!!, 0, audioBuf!!.size)

                        if (read <= 0) {
                            Log.e(TAG, "Hardware read error: $read")
                            publishAudioStatus("Hardware error, recovering...", AUDIO_STATUS_ERROR)
                            break
                        }

                        val isZero = audioBuf!!.all { it == 0.toShort() }

                        if (isZero) {
                            zeroBufferCount++
                            if (audioHealthy) {
                                Log.d(TAG, "Empty buffer detected.")
                                audioHealthy = false
                                onAudioHealth(false)
                            }
                        } else {
                            zeroBufferCount = 0
                            if (!audioHealthy) {
                                Log.i(TAG, "Audio recovered.")
                                audioHealthy = true
                                onAudioHealth(true)
                                publishWorkingStatus()
                            }
                        }

                        if (zeroBufferCount >= ZERO_BUFFER_LIMIT) {
                            Log.e(TAG, "Ghost connection detected. Triggering recovery.")
                            publishAudioStatus("Audio stuck, recovering...", AUDIO_STATUS_ERROR)
                            break
                        }

                        val samples = audioBuf!!.copyOf(read)
                        if (onRawAudio != null) {
                            onRawAudio.invoke(samples)
                        } else {
                            detector.processSamples(samples)
                        }
                    }

                    releaseRecorder(swapAudioRecord(null))

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in outer loop", e)
                    resetBluetoothAudioRouting()
                    Thread.sleep(2000)
                }
            }
            resetBluetoothAudioRouting()
        }
    }

    private fun requestAudioFocus(am: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (audioFocusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun waitForScoConnection(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        @Suppress("DEPRECATION")
        if (am.isBluetoothScoOn) {
            Log.d(TAG, "SCO already connected, skipping wait")
            bluetoothMicRoutingActive = true
            return true
        }

        val latch = CountDownLatch(1)
        var connectedResult = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d(TAG, "SCO state broadcast: $state")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO connected.")
                        connectedResult = true
                        bluetoothMicRoutingActive = true
                        latch.countDown()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.w(TAG, "Bluetooth SCO failed (state=$state).")
                        bluetoothMicRoutingActive = false
                        latch.countDown()
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED
        )

        try {
            // Double-check immediately after registration to close the race window.
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) {
                connectedResult = true
                bluetoothMicRoutingActive = true
            } else {
                // 5 seconds to accommodate slow hardware (e.g. Samsung M01).
                latch.await(5, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering SCO receiver: ${e.message}")
            }
        }

        return connectedResult
    }

    private fun resolveSampleRate(): Int {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 1. Bluetooth priority: SCO hardware only supports 16000 or 8000 Hz.
        if (bluetoothMicRoutingActive) {
            for (rate in SCO_SAMPLE_RATES) {
                if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
                    Log.d(TAG, "Using SCO sample rate: $rate Hz")
                    return rate
                }
            }
        }

        // 2. Regular mic: try hardware native rate (usually 48000 Hz).
        val nativeRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        if (nativeRate != null &&
            AudioRecord.getMinBufferSize(nativeRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        ) {
            Log.d(TAG, "Using hardware native sample rate: $nativeRate Hz")
            return nativeRate
        }

        // 3. Absolute fallback.
        return 44100
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
            Log.e(TAG, "Error releasing recorder", e)
        }
    }

    private fun createAudioRecord(): Triple<AudioRecord, Int, Int>? {
        val audioSource = if (bluetoothMicRoutingActive) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val rate = resolveSampleRate()
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        if (minBuffer <= 0) return null

        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * WINDOWS_IN_BUFFER)
        val ar = AudioRecord(
            audioSource, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return null
        }

        // setPreferredDevice is only used when setCommunicationDevice() was NOT called
        // (legacy SCO fallback or pre-API-31). On API >= 31 both APIs conflict.
        if (!communicationDeviceSetByDetector) {
            preferredInputDevice?.let { device ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val success = ar.setPreferredDevice(device)
                    Log.i(TAG, "setPreferredDevice(${device.productName}): $success")
                }
            }
        }

        return Triple(ar, bufferSize, rate)
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.interrupt()
        val ar = swapAudioRecord(null)
        try { workerThread?.join(4000) } catch (e: Exception) {}
        releaseRecorder(ar)
        resetBluetoothAudioRouting()
    }

    private fun configureBluetoothAudioRouting(): Boolean {
        val ctx = context ?: return false
        if (!useBluetoothMicIfAvailable) return false

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        // 1. Gain audio focus before requesting routing.
        // Failing to hold focus causes the OS policy manager to reject setCommunicationDevice.
        if (!requestAudioFocus(am)) {
            Log.e(TAG, "Failed to gain audio focus. Bluetooth routing might be rejected by OS.")
            return false
        }

        if (previousAudioMode == null) previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false

        val btDevice = findBluetoothMic(am)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (btDevice == null) return false
            val selected = try { am.setCommunicationDevice(btDevice) } catch (e: Exception) { false }

            if (!selected) {
                return tryEnableLegacyBluetoothSco(am).also { if (it) preferredInputDevice = btDevice }
            }

            bluetoothMicRoutingActive = true
            communicationDeviceSetByDetector = true
            preferredInputDevice = btDevice
            Log.i(TAG, "Communication device set to ${btDevice.productName} (type=${btDevice.type})")

            // For TYPE_BLUETOOTH_SCO on API >= 31: setCommunicationDevice() routes audio
            // but does not activate the SCO audio link. startBluetoothSco() is still needed
            // to open the link and trigger ACTION_SCO_AUDIO_STATE_UPDATED.
            // TYPE_BLE_HEADSET does NOT use SCO — do not call startBluetoothSco() for it.
            if (btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                scoEnabledByDetector = true
                Log.i(TAG, "SCO link started alongside setCommunicationDevice")
            }
            return true
        } else {
            return tryEnableLegacyBluetoothSco(am).also { if (it && btDevice != null) preferredInputDevice = btDevice }
        }
    }

    private fun tryEnableLegacyBluetoothSco(am: AudioManager): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
            scoEnabledByDetector = true
            bluetoothMicRoutingActive = true
            Log.i(TAG, "Legacy Bluetooth SCO enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable legacy Bluetooth SCO", e)
            false
        }
    }

    @Synchronized
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

        abandonAudioFocus(am) // 2. Release focus when Bluetooth routing is torn down
        bluetoothMicRoutingActive = false
        preferredInputDevice = null
    }

    private fun findBluetoothMic(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audioManager.availableCommunicationDevices
            val communicationDevice = audioManager.communicationDevice

            if (communicationDevice != null &&
                (communicationDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        communicationDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                available.contains(communicationDevice)
            ) {
                return communicationDevice
            }

            // IMPORTANT: Do NOT filter by isSource or isSink for availableCommunicationDevices.
            // Android reports these bidirectional headsets as sinks (earpiece/output side) in
            // this list. isSource is often false even for headsets that clearly have microphones.
            // Device type alone is the correct and sufficient filter for setCommunicationDevice().
            available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let { return it }
            available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }?.let { return it }
        }

        // Pre-API-31 fallback: enumerate input devices. isSource is reliable here because
        // GET_DEVICES_INPUTS explicitly requests source (microphone) devices.
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun isBluetoothInputDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun updateRoutingAndPublishWorkingStatus(device: AudioDeviceInfo?) {
        bluetoothMicRoutingActive = if (device != null) isBluetoothInputDevice(device) else bluetoothMicRoutingActive
        publishAudioStatus(if (bluetoothMicRoutingActive) "Working (bluetooth)" else "Working", AUDIO_STATUS_WORKING)
    }

    private fun publishWorkingStatus() {
        publishAudioStatus(if (bluetoothMicRoutingActive) "Working (bluetooth)" else "Working", AUDIO_STATUS_WORKING)
    }

    private fun publishAudioStatus(status: String, errorCode: Int) {
        if (lastPublishedAudioStatus == status) return
        Log.i(TAG, "Status: $status")
        lastPublishedAudioStatus = status
        onAudioStatus(status, errorCode)
    }

    companion object {
        private const val TAG = "AudioBeepDetector"
        private const val WINDOW_SIZE = GoertzelDetector.DEFAULT_WINDOW_SIZE
        private const val WINDOWS_IN_BUFFER = 64
        private const val ZERO_BUFFER_LIMIT = 20
        private val SCO_SAMPLE_RATES = intArrayOf(16000, 8000)
        private const val BASE_MAG = 1e6f
        const val DEFAULT_MAG_THRESHOLD = BASE_MAG * WINDOW_SIZE
        const val DEFAULT_BLUETOOTH_MAG_THRESHOLD = BASE_MAG * WINDOW_SIZE
        const val AUDIO_STATUS_WAITING = 0
        const val AUDIO_STATUS_WORKING = 1
        const val AUDIO_STATUS_ERROR   = 2

        /**
         * Returns true if a Bluetooth microphone is physically connected and accessible.
         * Used by the UI to gate BT-specific features (e.g. BT calibration button).
         *
         * For availableCommunicationDevices (API 31+): type check only, no isSource/isSink.
         * Android reports communication headsets as sinks in this list, so isSource is
         * unreliable here. For GET_DEVICES_INPUTS (pre-31): isSource is correct.
         */
        fun isBluetoothMicAvailable(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return am.availableCommunicationDevices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }

        fun createWithPrefs(
            context: Context,
            onBeep: (Float, Int) -> Unit,
            onAudioHealth: (Boolean) -> Unit = {},
            onAudioStatus: (String, Int) -> Unit = { _, _ -> }
        ): com.github.bocovp.geigergpx.AudioInputManager {
            return AudioInputManager(
                context = context.applicationContext,
                magThreshold = storedThreshold(context, false),
                bluetoothMagThreshold = storedThreshold(context, true),
                useBluetoothMicIfAvailable = useBluetoothMicIfAvailable(context),
                onBeep = onBeep,
                onAudioHealth = onAudioHealth,
                onAudioStatus = onAudioStatus
            )
        }

        fun storedThreshold(context: Context, bluetooth: Boolean): Float {
            val key = if (bluetooth) SettingsFragment.KEY_BLUETOOTH_AUDIO_THRESHOLD else SettingsFragment.KEY_AUDIO_THRESHOLD
            val stored = PreferenceManager.getDefaultSharedPreferences(context).getFloat(key, Float.NaN)
            return if (!stored.isNaN() && stored > 0f) stored
            else if (bluetooth) DEFAULT_BLUETOOTH_MAG_THRESHOLD else DEFAULT_MAG_THRESHOLD
        }

        /**
         * Returns true ONLY if the user has enabled the Bluetooth preference in Settings.
         * Note: Removed physical hardware check so the worker loop can wait infinitely
         * and never silently fall back to the built-in mic if BT drops temporarily.
         */
        private fun useBluetoothMicIfAvailable(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, false)
        }
    }
}
