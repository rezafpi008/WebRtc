package com.texon.example.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.Nullable
import com.texon.example.webrtc.R
import org.webrtc.ThreadUtils
import java.util.*
import kotlin.collections.HashSet


class RTCAudioManager(context: Context) {
    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    enum class AudioManagerState {
        UNINITIALIZED, PREINITIALIZED, RUNNING
    }

    interface AudioManagerEvents {
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice?, availableAudioDevices: Set<AudioDevice?>?
        )
    }

    private val apprtcContext: Context

    @Nullable
    private val audioManager: AudioManager

    @Nullable
    private var audioManagerEvents: AudioManagerEvents? = null
    private var amState: AudioManagerState
    private var savedAudioMode = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false

    private var defaultAudioDevice: AudioDevice? = null

    private var selectedAudioDevice: AudioDevice? = null

    private var userSelectedAudioDevice: AudioDevice? = null

    @Nullable
    private val useSpeakerphone: String?

    private var audioDevices: MutableSet<AudioDevice?> = HashSet()

    private val wiredHeadsetReceiver: BroadcastReceiver

    @Nullable
    private var audioFocusChangeListener: OnAudioFocusChangeListener? = null


    private inner class WiredHeadsetReceiver() : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val state = intent.getIntExtra("state", STATE_UNPLUGGED)
            val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
            val name = intent.getStringExtra("name")
            Log.d(TAG, "WiredHeadsetReceiver.onReceive"
                    + ": " + "a=" + intent.action.toString() + ", s=" +
                    (if (state == STATE_UNPLUGGED) "unplugged" else "plugged").toString()
                    + ", m=" + (if (microphone == HAS_MIC) "mic" else "no mic").toString()
                    + ", n=" + name.toString() + ", sb=" + isInitialStickyBroadcast)
            hasWiredHeadset = (state == STATE_PLUGGED)
            updateAudioDeviceState()
        }

        private val STATE_UNPLUGGED = 0
        private val STATE_PLUGGED = 1
        private val HAS_NO_MIC = 0
        private val HAS_MIC = 1
    }

    fun start(audioManagerEvents: AudioManagerEvents?) {
        Log.d(TAG, "start")
        ThreadUtils.checkIsOnMainThread()
        if (amState == AudioManagerState.RUNNING) {
            Log.e(TAG, "AudioManager is already active")
            return
        }
        Log.d(TAG, "AudioManager starts...")
        this.audioManagerEvents = audioManagerEvents
        amState = AudioManagerState.RUNNING

        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        hasWiredHeadset = hasWiredHeadset()

        audioFocusChangeListener =
            OnAudioFocusChangeListener { focusChange ->

                val typeOfChange: String
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> typeOfChange = "AUDIOFOCUS_GAIN"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> typeOfChange =
                        "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                    AudioManager.AUDIOFOCUS_LOSS -> typeOfChange = "AUDIOFOCUS_LOSS"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> typeOfChange =
                        "AUDIOFOCUS_LOSS_TRANSIENT"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> typeOfChange =
                        "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                    else -> typeOfChange = "AUDIOFOCUS_INVALID"
                }
                Log.d(TAG, "onAudioFocusChange: $typeOfChange")
            }


        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted for VOICE_CALL streams")
        } else {
            Log.e(TAG, "Audio focus request failed")
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        setMicrophoneMute(false)

        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()

        updateAudioDeviceState()

        registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        Log.d(TAG, "AudioManager started")
    }

    fun stop() {
        Log.d(TAG, "stop")
        ThreadUtils.checkIsOnMainThread()
        if (amState != AudioManagerState.RUNNING) {
            Log.e(
                TAG,
                "Trying to stop AudioManager in incorrect state: $amState"
            )
            return
        }
        amState = AudioManagerState.UNINITIALIZED
        unregisterReceiver(wiredHeadsetReceiver)

        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)
        audioManager.mode = savedAudioMode

        audioManager.abandonAudioFocus(audioFocusChangeListener)
        audioFocusChangeListener = null
        Log.d(TAG, "Abandoned audio focus for VOICE_CALL streams")

        audioManagerEvents = null
        Log.d(TAG, "AudioManager stopped")
    }

    /** Changes selection of the currently active audio device.  */
    private fun setAudioDeviceInternal(device: AudioDevice?) {
        Log.d(TAG, "setAudioDeviceInternal(device=$device)")
        if (audioDevices.contains(device)) {
            when (device) {
                AudioDevice.SPEAKER_PHONE -> setSpeakerphoneOn(true)
                AudioDevice.EARPIECE -> setSpeakerphoneOn(false)
                AudioDevice.WIRED_HEADSET -> setSpeakerphoneOn(false)
                else -> Log.e(TAG, "Invalid audio device selection")
            }
        }
        selectedAudioDevice = device
    }


    fun setDefaultAudioDevice(defaultDevice: AudioDevice?) {
        ThreadUtils.checkIsOnMainThread()
        when (defaultDevice) {
            AudioDevice.SPEAKER_PHONE -> defaultAudioDevice = defaultDevice
            AudioDevice.EARPIECE -> if (hasEarpiece()) {
                defaultAudioDevice = defaultDevice
            } else {
                defaultAudioDevice = AudioDevice.SPEAKER_PHONE
            }
            else -> Log.e(TAG, "Invalid default audio device selection")
        }
        Log.d(TAG, "setDefaultAudioDevice(device=$defaultAudioDevice)")
        updateAudioDeviceState()
    }

    /** Changes selection of the currently active audio device.  */
    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!audioDevices.contains(device)) {
            Log.e(
                TAG,
                "Can not select $device from available $audioDevices"
            )
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    /** Returns current set of available/selectable audio devices.  */
    fun getAudioDevices(): Set<AudioDevice> {
        ThreadUtils.checkIsOnMainThread()
        return Collections.unmodifiableSet(HashSet(audioDevices)) as Set<AudioDevice>
    }

    /** Returns the currently selected audio device.  */
    fun getSelectedAudioDevice(): AudioDevice? {
        ThreadUtils.checkIsOnMainThread()
        return selectedAudioDevice
    }

    /** Helper method for receiver registration.  */
    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    /** Helper method for unregistration of an existing receiver.  */
    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        apprtcContext.unregisterReceiver(receiver)
    }

    /** Sets the speaker phone mode.  */
    private fun setSpeakerphoneOn(on: Boolean) {
        val wasOn = audioManager.isSpeakerphoneOn
        if (wasOn == on) {
            return
        }
        audioManager.isSpeakerphoneOn = on
    }

    /** Sets the microphone mute state.  */
    private fun setMicrophoneMute(on: Boolean) {
        val wasMuted = audioManager.isMicrophoneMute
        if (wasMuted == on) {
            return
        }
        audioManager.isMicrophoneMute = on
    }

    /** Gets the current earpiece state.  */
    private fun hasEarpiece(): Boolean {
        return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    @Deprecated("")
    private fun hasWiredHeadset(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return audioManager.isWiredHeadsetOn
        } else {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device: AudioDeviceInfo in devices) {
                val type = device.type
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    Log.d(TAG, "hasWiredHeadset: found wired headset")
                    return true
                } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    Log.d(TAG, "hasWiredHeadset: found USB audio device")
                    return true
                }
            }
            return false
        }
    }

    /**
     * Updates list of possible audio devices and make new device selection.
     */
    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(
            TAG, ("--- updateAudioDeviceState: "
                    + "wired headset=" + hasWiredHeadset)
        )
        Log.d(
            TAG, ("Device status: "
                    + "available=" + audioDevices + ", "
                    + "selected=" + selectedAudioDevice + ", "
                    + "user selected=" + userSelectedAudioDevice)
        )


        // Update the set of available audio devices.
        val newAudioDevices: MutableSet<AudioDevice?> = HashSet()

        if (hasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE)
            }
        }
        // Store state which is set to true if the device list has changed.
        var audioDeviceSetUpdated = audioDevices != newAudioDevices
        // Update the existing audio device set.
        audioDevices = newAudioDevices
        // Correct user selected audio devices if needed.
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {

            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }


        // Update selected audio device.
        val newAudioDevice: AudioDevice?
        if (hasWiredHeadset) {

            newAudioDevice = AudioDevice.WIRED_HEADSET
        } else {

            newAudioDevice = defaultAudioDevice
        }
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            setAudioDeviceInternal(newAudioDevice)
            Log.d(
                TAG, ("New device status: "
                        + "available=" + audioDevices + ", "
                        + "selected=" + newAudioDevice)
            )
            if (audioManagerEvents != null) {
                audioManagerEvents!!.onAudioDeviceChanged(selectedAudioDevice, audioDevices)
            }
        }
        Log.d(TAG, "--- updateAudioDeviceState done")
    }

    companion object {
        private val TAG = "AppRTCAudioManager"
        private val SPEAKERPHONE_AUTO = "auto"
        private val SPEAKERPHONE_TRUE = "true"
        private val SPEAKERPHONE_FALSE = "false"

        fun create(context: Context): RTCAudioManager {
            return RTCAudioManager(context)
        }
    }

    init {
        Log.d(TAG, "ctor")
        ThreadUtils.checkIsOnMainThread()
        apprtcContext = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wiredHeadsetReceiver = WiredHeadsetReceiver()
        amState = AudioManagerState.UNINITIALIZED
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        useSpeakerphone = sharedPreferences.getString(
            context.getString(R.string.pref_speakerphone_key),
            context.getString(R.string.pref_speakerphone_default)
        )
        Log.d(TAG, "useSpeakerphone: $useSpeakerphone")
        if ((useSpeakerphone == SPEAKERPHONE_FALSE)) {
            defaultAudioDevice = AudioDevice.EARPIECE
        } else {
            defaultAudioDevice = AudioDevice.SPEAKER_PHONE
        }
        Log.d(TAG, "defaultAudioDevice: $defaultAudioDevice")
    }
}