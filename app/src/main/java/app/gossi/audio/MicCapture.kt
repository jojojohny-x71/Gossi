package app.gossi.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.max

/**
 * Records the phone microphone at 16 kHz mono and hands out 20 ms frames.
 * The frame buffer is reused — consumers must copy what they keep.
 */
class MicCapture(
    private val strongNoiseSuppression: Boolean,
    /** e.g. the Bluetooth earbud mic; null = phone mic. */
    private val preferredDevice: AudioDeviceInfo? = null,
    private val onFrame: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 320
        private const val TAG = "GossiMic"
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "gossi-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission") // checked by the activity before the service starts
    private fun loop() {
        // VOICE_COMMUNICATION enables the phone's call-grade noise suppression / echo cancelling.
        // Earbud mics (Bluetooth SCO) are only reachable through the communication source.
        val source = if (strongNoiseSuppression || preferredDevice != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE))
        } catch (e: Exception) {
            onError("Cannot open microphone: ${e.message}")
            return
        }
        if (preferredDevice != null) rec.setPreferredDevice(preferredDevice)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("Microphone is busy or unavailable")
            return
        }
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            if (strongNoiseSuppression && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effects unavailable", e)
        }
        try {
            rec.startRecording()
            val buf = ShortArray(FRAME)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onFrame(buf, n)
                else if (n < 0) {
                    onError("Microphone read failed ($n)")
                    break
                }
            }
        } catch (e: Exception) {
            onError("Microphone stopped: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            ns?.release()
            agc?.release()
            rec.release()
        }
    }
}
