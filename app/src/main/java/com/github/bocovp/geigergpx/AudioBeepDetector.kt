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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioBeepDetector(
    private val context: Context? = null,
    private val magThreshold: Float = DEFAULT_MAG_THRESHOLD,
    private val bluetoothMagThreshold: Float = DEFAULT_BLUETOOTH_MAG_THRESHOLD,
    private val useBluetoothMicIfAvailable: Boolean = true,
    private val onBeep: (Float, Int) -> Unit,
    private val onAudioHealth: (Boolean) -> Unit = {},
    private val onAudioStatus: (String, Int) -> Unit = { _, _ -> },
    private val onRecordingStarted: (sampleRate: Int) -> Unit = {},
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
        if (running) {
            Log.d(TAG, "start() called but detector is already running")
            return
        }
        running = true
        Log.i(TAG, "Starting AudioBeepDetector worker thread...")

        workerThread = thread(start = true, name = "BeepDetector") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            var audioBuf: ShortArray? = null
            val ctx = context

            while (running) {
                try {
                    Log.i(TAG, "--- Outer Loop: Initializing Audio Hardware ---")
                    resetBluetoothAudioRouting()

                    if (ctx != null) {
                        configureBluetoothAudioRouting()
                    }

                    if (useBluetoothMicIfAvailable && !bluetoothMicRoutingActive) {
                        Log.w(TAG, "Bluetooth mic preferred but not routed. Waiting for hardware...")
                        publishAudioStatus("Waiting for Bluetooth mic...", AUDIO_STATUS_WAITING)
                        Thread.sleep(2000)
                        continue
                    }

                    if (scoEnabledByDetector && ctx != null) {
                        Log.i(TAG, "Waiting for Bluetooth SCO hardware connection...")
                        val connected = waitForScoConnection(ctx)
                        if (!connected && useBluetoothMicIfAvailable) {
                            Log.w(TAG, "SCO timeout. Retrying connection...")
                            publishAudioStatus("Bluetooth mic timeout, retrying...", AUDIO_STATUS_WAITING)
                            Thread.sleep(1000)
                            continue
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
                        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            throw IllegalStateException("AudioRecord state is not RECORDSTATE_RECORDING")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "startRecording failed", e)
                        releaseRecorder(swapAudioRecord(null))
                        Thread.sleep(1000)
                        continue
                    }

                    val currentDevice = ar.routedDevice
                    val actuallyBt = if (currentDevice != null) isBluetoothInputDevice(currentDevice) else bluetoothMicRoutingActive

                    Log.i(TAG, "Recording confirmed — sampleRate=$actualSampleRate Hz, BT=$actuallyBt")

                    if (useBluetoothMicIfAvailable && !actuallyBt) {
                        Log.w(TAG, "OS rejected BT routing. Retrying.")
                        publishAudioStatus("Waiting for Bluetooth mic...", AUDIO_STATUS_WAITING)
                        releaseRecorder(swapAudioRecord(null))
                        Thread.sleep(2000)
                        continue
                    }

                    updateRoutingAndPublishWorkingStatus(currentDevice)
                    onRecordingStarted(actualSampleRate)

                    val selectedThreshold = if (actuallyBt) bluetoothMagThreshold else magThreshold
                    val detector = GoertzelDetector(selectedThreshold, actualSampleRate).apply {
                        onBeep = this@AudioBeepDetector.onBeep
                    }

                    if (audioBuf == null || audioBuf.size != bufferSize) {
                        audioBuf = ShortArray(bufferSize)
                    }

                    // FIX: Using AtomicBoolean to allow modification inside the routing listener
                    val innerLoopBreak = AtomicBoolean(false)

                    ar.addOnRoutingChangedListener({ record ->
                        val newDevice = record.routedDevice
                        Log.w(TAG, "Routing changed mid-session! type=${newDevice?.type}")
                        if (useBluetoothMicIfAvailable && !isBluetoothInputDevice(newDevice)) {
                            Log.e(TAG, "Bluetooth lost. Breaking inner loop.")
                            innerLoopBreak.set(true)
                        } else {
                            updateRoutingAndPublishWorkingStatus(newDevice)
                        }
                    }, null)

                    var zeroBufferCount = 0
                    var audioHealthy = true

                    while (running && !innerLoopBreak.get()) {
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
                    Thread.sleep(2000)
                }
            }
            resetBluetoothAudioRouting()
        }
    }

    private fun waitForScoConnection(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val latch = CountDownLatch(1)

        @Suppress("DEPRECATION")
        if (am.isBluetoothScoOn) return true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) latch.countDown()
            }
        }

        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)

        return try {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) return true
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        } finally {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    private fun resolveSampleRate(): Int {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 1. Bluetooth Priority: Use SCO rates (16k or 8k) if BT is active
        if (bluetoothMicRoutingActive) {
            for (rate in SCO_SAMPLE_RATES) {
                if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
                    return rate
                }
            }
        }

        // 2. Regular Mic: Try to use Hardware Native Rate (usually 48000)
        val nativeRateStr = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val nativeRate = nativeRateStr?.toIntOrNull()

        if (nativeRate != null && AudioRecord.getMinBufferSize(nativeRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
            Log.d(TAG, "Using hardware native sample rate: $nativeRate Hz")
            return nativeRate
        }

        // 3. Absolute Fallback (44100)
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
        val audioSource = if (bluetoothMicRoutingActive) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rate = resolveSampleRate()
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        if (minBuffer <= 0) return null

        val bufferSize = maxOf(minBuffer, WINDOW_SIZE * WINDOWS_IN_BUFFER)
        val ar = AudioRecord(audioSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return null
        }

        if (!communicationDeviceSetByDetector) {
            preferredInputDevice?.let { ar.setPreferredDevice(it) }
        }

        return Triple(ar, bufferSize, rate)
    }

    fun stop() {
        if (!running) return
        running = false
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
        if (previousAudioMode == null) previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false

        val btDevice = findBluetoothMic(am)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (btDevice == null) return false
            val selected = try { am.setCommunicationDevice(btDevice) } catch (e: Exception) { false }

            if (!selected) return tryEnableLegacyBluetoothSco(am).also { if(it) preferredInputDevice = btDevice }

            bluetoothMicRoutingActive = true
            communicationDeviceSetByDetector = true
            preferredInputDevice = btDevice
            if (btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && am.isBluetoothScoAvailableOffCall) scoEnabledByDetector = true
            return true
        } else {
            return tryEnableLegacyBluetoothSco(am).also { if(it && btDevice != null) preferredInputDevice = btDevice }
        }
    }

    private fun tryEnableLegacyBluetoothSco(am: AudioManager): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            scoEnabledByDetector = true
            bluetoothMicRoutingActive = true
            true
        } catch (e: Exception) { false }
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
            val available = audioManager.availableCommunicationDevices
            val communicationDevice = audioManager.communicationDevice
            if (isBluetoothInputDevice(communicationDevice) && available.contains(communicationDevice)) return communicationDevice
            available.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let { return it }
            available.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }?.let { return it }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetoothInputDevice(it) }
    }

    private fun isBluetoothInputDevice(device: AudioDeviceInfo?): Boolean {
        val resolved = device ?: return false
        return resolved.isSource && (resolved.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || resolved.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun updateRoutingAndPublishWorkingStatus(device: AudioDeviceInfo?) {
        val actuallyBt = if (device != null) isBluetoothInputDevice(device) else bluetoothMicRoutingActive
        publishAudioStatus(if (actuallyBt) "Working (bluetooth)" else "Working", AUDIO_STATUS_WORKING)
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

        // RESTORED: Public function for UI visibility checks
        fun isBluetoothMicAvailable(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return am.availableCommunicationDevices.any { it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
            }
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }

        fun createWithPrefs(context: Context, onBeep: (Float, Int) -> Unit, onAudioHealth: (Boolean) -> Unit = {}, onAudioStatus: (String, Int) -> Unit = { _, _ -> }): AudioBeepDetector {
            return AudioBeepDetector(
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
            return if (!stored.isNaN() && stored > 0f) stored else (if (bluetooth) DEFAULT_BLUETOOTH_MAG_THRESHOLD else DEFAULT_MAG_THRESHOLD)
        }

        private fun useBluetoothMicIfAvailable(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, true)
        }
    }
}