package com.teleprompterpro.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.teleprompterpro.app.util.Logger
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One selectable microphone (built-in or external). */
data class MicDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val external: Boolean,
) {
    val kindLabel: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
            else -> if (Build.VERSION.SDK_INT >= 31 && type == 26 /* BLE_HEADSET */) "Bluetooth LE" else "External"
        }

    companion object {
        /** Sentinel representing "let Android choose". */
        val SYSTEM_DEFAULT = MicDevice(-1, "System default", AudioDeviceInfo.TYPE_UNKNOWN, false)
    }
}

/** Outcome of a real signal probe on a specific microphone. */
data class MicProbeResult(
    val requested: MicDevice,
    /** Device the audio system actually routed from (null = unknown / pre-API-24 route info). */
    val routed: MicDevice?,
    /** Whether AudioRecord opened and produced samples at all. */
    val opened: Boolean,
    /** Peak normalized level observed during the probe window (0..1). */
    val peakLevel: Float,
) {
    /** True when the user picked an external mic but audio is coming from somewhere else. */
    val fellBack: Boolean
        get() = requested.id != -1 && routed != null && routed.id != requested.id

    /** Detected at least some signal — mic is live (not muted / unpowered). */
    val hasSignal: Boolean get() = opened && peakLevel > 0.004f

    val summary: String
        get() = when {
            !opened -> "Microphone could not be opened"
            fellBack -> "Falling back to ${routed?.name ?: "phone mic"}"
            !hasSignal -> "No signal detected from ${requested.name}"
            else -> "${routed?.name ?: requested.name} is live"
        }
}

/**
 * External microphone discovery + pre-recording signal verification.
 *
 * Unlike a volume meter, this opens the *specific* device via
 * [AudioRecord.setPreferredDevice], checks which device the framework really
 * routed from, and measures whether any samples arrive — so a Bluetooth or
 * USB-C mic that is paired but not actually delivering audio is reported
 * instead of silently replaced by the phone mic.
 */
class MicrophoneProbe(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _devices = MutableStateFlow(listDevices())
    /** Currently attached input devices; refreshes on plug/unplug. */
    val devices: StateFlow<List<MicDevice>> = _devices

    private val _lastChange = MutableStateFlow(0L)
    /** Bumps whenever a device connects/disconnects (recording UI listens). */
    val deviceChangeTick: StateFlow<Long> = _lastChange

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _devices.value = listDevices()
            _lastChange.value = System.currentTimeMillis()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _devices.value = listDevices()
            _lastChange.value = System.currentTimeMillis()
        }
    }

    private var registered = false

    fun startWatching() {
        if (registered) return
        registered = true
        runCatching { audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
    }

    fun stopWatching() {
        if (!registered) return
        registered = false
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    fun listDevices(): List<MicDevice> {
        val infos = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) }
            .getOrDefault(emptyArray())
        val list = infos
            .filter { it.isSource && it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
            .map { toDevice(it) }
            .distinctBy { it.id }
        return listOf(MicDevice.SYSTEM_DEFAULT) + list.sortedBy { if (it.external) 0 else 1 }
    }

    fun find(id: Int): MicDevice? = _devices.value.firstOrNull { it.id == id }

    /**
     * Opens the requested mic for ~[windowMs] and reports routing + signal.
     * Must not be called while a recording owns the microphone.
     */
    suspend fun probe(requested: MicDevice, windowMs: Long = 1200L): MicProbeResult =
        withContext(Dispatchers.IO) {
            val info = if (requested.id == -1) null else
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == requested.id }
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@withContext MicProbeResult(requested, null, false, 0f)
            var record: AudioRecord? = null
            var scoStarted = false
            try {
                if (info?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    // SCO mics need the SCO link up before they deliver samples.
                    runCatching { audioManager.startBluetoothSco() }
                    scoStarted = true
                    Thread.sleep(600)
                }
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    return@withContext MicProbeResult(requested, null, false, 0f)
                }
                if (info != null) record.preferredDevice = info
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@withContext MicProbeResult(requested, null, false, 0f)
                }
                val buffer = ShortArray(1024)
                val deadline = System.currentTimeMillis() + windowMs
                var peak = 0f
                var opened = false
                while (System.currentTimeMillis() < deadline) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    opened = true
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buffer[i] / 32768.0
                        sum += s * s
                    }
                    val rms = sqrt(sum / n).toFloat()
                    if (rms > peak) peak = rms
                }
                val routed = record.routedDevice?.let { toDevice(it) }
                Logger.i("MicProbe", "requested=${requested.name} routed=${routed?.name} peak=$peak")
                MicProbeResult(requested, routed, opened, peak)
            } catch (e: Exception) {
                Logger.w("MicProbe", "Probe failed", e)
                MicProbeResult(requested, null, false, 0f)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                if (scoStarted) runCatching { audioManager.stopBluetoothSco() }
            }
        }

    /** Turn on the Bluetooth SCO link for recording through a BT headset mic. */
    fun enableBluetoothSco(enabled: Boolean) {
        runCatching {
            if (enabled) {
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
    }

    private fun toDevice(info: AudioDeviceInfo): MicDevice {
        val external = info.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
        val product = info.productName?.toString()?.trim().orEmpty()
        val base = MicDevice(info.id, "", info.type, external)
        val name = when {
            !external -> "Phone microphone"
            product.isNotEmpty() && !product.equals(Build.MODEL, ignoreCase = true) -> "$product (${base.kindLabel})"
            else -> "${base.kindLabel} microphone"
        }
        return base.copy(name = name)
    }
}
