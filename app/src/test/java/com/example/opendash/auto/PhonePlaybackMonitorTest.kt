package com.example.opendash.auto

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class PhonePlaybackMonitorTest {
    @Test fun observesPlaybackChangesWithoutTreatingAssistantAsMusicOrRequiringButtonPresses() {
        val app=RuntimeEnvironment.getApplication()
        val audio=shadowOf(app.getSystemService(AudioManager::class.java))
        val events=mutableListOf<Boolean>()
        val monitor=PhonePlaybackMonitor(app,events::add)
        monitor.start()
        val music=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        audio.setActivePlaybackConfigurationsFor(listOf(music),true)
        audio.setActivePlaybackConfigurationsFor(listOf(music,AaPlaybackPolicy.attributes(4)),true)
        audio.setActivePlaybackConfigurationsFor(listOf(AaPlaybackPolicy.attributes(4)),true)
        audio.setActivePlaybackConfigurationsFor(emptyList(),true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(false,true,false),events)
        monitor.close()
        audio.setActivePlaybackConfigurationsFor(listOf(music),true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(false,true,false),events)
    }
}
