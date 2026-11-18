package com.example.opendash.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/** Observe playback without taking focus, capturing audio or sending media keys. */
internal class PhonePlaybackMonitor(context:Context,private val changed:(Boolean)->Unit):AutoCloseable {
    private val audio=context.getSystemService(AudioManager::class.java)
    private var registered=false
    private var last:Boolean?=null
    private val callback=object:AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs:MutableList<AudioPlaybackConfiguration>) { update(configs) }
    }
    fun start() {
        if(registered) return
        audio.registerAudioPlaybackCallback(callback,Handler(Looper.getMainLooper())); registered=true
        update(audio.activePlaybackConfigurations)
    }
    private fun update(configs:List<AudioPlaybackConfiguration>) {
        if(!registered) return
        val playing=configs.any { isMusic(it.audioAttributes) }
        if(playing!=last) { last=playing; changed(playing) }
    }
    override fun close() { if(registered) { registered=false; audio.unregisterAudioPlaybackCallback(callback) } }
    companion object {
        fun isMusic(attributes:AudioAttributes)=attributes.usage==AudioAttributes.USAGE_MEDIA &&
            attributes.contentType!=AudioAttributes.CONTENT_TYPE_SPEECH
    }
}
