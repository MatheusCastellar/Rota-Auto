package com.example.opendash.auto

import android.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class AaPlaybackPolicyTest {
    private fun device(type:Int)=AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    @Test fun returnedSpeechUsesLocalMediaOutputAndCannotBeRecaptured() {
        for(channel in listOf(4,5)) {
            val a=AaPlaybackPolicy.attributes(channel)
            assertEquals(AudioAttributes.USAGE_MEDIA,a.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,a.contentType)
            assertEquals(AudioAttributes.ALLOW_CAPTURE_BY_NONE,a.allowedCapturePolicy)
        }
    }
    @Test fun activeHeadsetVoiceRouteWinsThenReleasesBackToMusicOutput() {
        val sco=device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val a2dp=device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val all=arrayOf(a2dp,sco,speaker)
        assertSame(sco,AaPlaybackPolicy.output(all,sco,true))
        assertSame(a2dp,AaPlaybackPolicy.output(all,null,true))
        assertSame(a2dp,AaPlaybackPolicy.output(all,sco,false))
    }
    @Test fun disconnectedHeadsetFallsBackToSpeakerAndNeverRemoteSubmix() {
        val remote=device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertSame(speaker,AaPlaybackPolicy.output(arrayOf(remote,speaker),remote,true))
        assertNull(AaPlaybackPolicy.output(arrayOf(remote),null,true))
    }
}
