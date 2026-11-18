package com.example.opendash.auto

import android.media.AudioAttributes
import android.media.AudioDeviceInfo

/** The AA sink type is independent of Android playback usage on this same-phone receiver. */
internal object AaPlaybackPolicy {
    fun attributes(channel:Int):AudioAttributes=AudioAttributes.Builder()
        // NAVIGATION/ASSISTANT are redirected by AA on this phone. Playing the returned
        // PCM with those usages competes with the upstream projection policy on A2DP.
        // MEDIA is intentionally not advertised as an AA sink, so it stays local.
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(if(channel==6) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()

    fun output(devices:Array<AudioDeviceInfo>,communication:AudioDeviceInfo?,speech:Boolean):AudioDeviceInfo? {
        // SCO is also selected in MODE_NORMAL; switching to call mode aborts the AA assistant.
        if(speech && communication!=null && communication.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET)) return communication
        val order=intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        for(type in order) devices.firstOrNull { it.type==type }?.let { return it }
        return null
    }
}
