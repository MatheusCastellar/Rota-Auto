package com.example.opendash.auto

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoProfileTest {
    @Test fun selectingFpsLoadsSuggestedAverageWithoutEnablingManualImplicitly() {
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("video-fps-test",Context.MODE_PRIVATE)
        p.edit().clear().commit()
        VideoProfile.selectFps(p,8)
        assertEquals(VideoProfile(8,400000,true),VideoProfile.fromPreferences(p))
        p.edit().putBoolean(VideoProfile.MANUAL,true).putInt(VideoProfile.BITRATE,900).commit()
        VideoProfile.selectFps(p,12)
        assertEquals(VideoProfile(12,200000,false),VideoProfile.fromPreferences(p))
        VideoProfile.selectFps(p,4)
        assertEquals(VideoProfile(4,200000,false),VideoProfile.fromPreferences(p))
    }
    @Test fun automaticFollowsFpsAndManualRemembersItsTargetAcrossSwitches() {
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("video-test",Context.MODE_PRIVATE)
        p.edit().clear().commit()
        assertEquals(VideoProfile(12,200000,true),VideoProfile.fromPreferences(p))
        p.edit().putInt(VideoProfile.BITRATE,450).commit() // Old backup preserves manual setting.
        assertEquals(VideoProfile(12,450000,false),VideoProfile.fromPreferences(p))
        p.edit().putBoolean(VideoProfile.MANUAL,false).putInt("video-fps",4).commit()
        assertEquals(VideoProfile(4,200000,true),VideoProfile.fromPreferences(p))
        p.edit().putBoolean(VideoProfile.MANUAL,true).commit()
        assertEquals(VideoProfile(4,450000,false),VideoProfile.fromPreferences(p))
        p.edit().putString("saved-bike","RE_test").commit()
        VideoProfile.restore(p)
        assertEquals(VideoProfile(12,200000,true),VideoProfile.fromPreferences(p))
        assertFalse(p.contains(VideoProfile.BITRATE)); assertEquals("RE_test",p.getString("saved-bike",null))
    }
}

