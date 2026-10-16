package app.gossi.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes the microphone to the Bluetooth earbuds (Nothing Ear (a) etc.).
 *
 * Earbud mics only work over the Bluetooth "call" link (HFP/SCO), so while this is on the earbuds
 * behave like they're in a phone call: playback becomes mono call-quality audio. Gossi's voices are
 * sent over the same link (see VoiceBank.setCallAudio).
 */
class EarbudMic(private val am: AudioManager) {

    private var active = false

    /** @return the earbud input device to record from, or null if no Bluetooth headset mic is available. */
    @Suppress("DEPRECATION")
    fun enable(): AudioDeviceInfo? {
        val btTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val device = am.availableCommunicationDevices.firstOrNull { it.type in btTypes }
                if (device == null || !am.setCommunicationDevice(device)) {
                    am.mode = AudioManager.MODE_NORMAL
                    return null
                }
            } else {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
            active = true
        } catch (e: Exception) {
            Log.w("GossiEarbudMic", "enable failed", e)
            disable()
            return null
        }
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in btTypes }
    }

    @Suppress("DEPRECATION")
    fun disable() {
        if (!active) return
        active = false
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w("GossiEarbudMic", "disable failed", e)
        }
    }
}
