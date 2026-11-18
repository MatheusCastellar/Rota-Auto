package com.example.opendash.auto

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@Implements(AudioManager::class)
class RecordingVolumeAudioManager:ShadowAudioManager() {
    val adjustments=mutableListOf<Triple<Int,Int,Int>>()
    @Implementation override fun adjustStreamVolume(stream:Int,direction:Int,flags:Int) {
        adjustments.add(Triple(stream,direction,flags))
        super.adjustStreamVolume(stream,direction,flags)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],shadows=[RecordingVolumeAudioManager::class])
class BikeMediaControlsTest {
    @Test fun physicalButtonsGoToPhonePlayerAndVoiceRemainsAnAssignableAction() {
        val app=RuntimeEnvironment.getApplication()
        val audio=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(audio)
        shadow.clearDispatchedMediaKeyEvents()
        var voice=0
        val player=BikeMediaControls(app) { voice++; true }
        val controls=TripperControls(true)
        assertFalse(controls.importNavigationBindings(mapOf(
            TripperControls.Action.UP to 6,TripperControls.Action.DOWN to 7,
            TripperControls.Action.LEFT to 10,TripperControls.Action.RIGHT to 9,
            TripperControls.Action.SELECT to 5,TripperControls.Action.BACK to 24)))
        for((i,code) in listOf(5,10,9).withIndex()) player.execute(controls.receive(code,1000L+i*300).action!!)
        val events=shadow.dispatchedMediaKeyEvents
        assertEquals(listOf(85,85,88,88,87,87),events.map { it.keyCode })
        assertEquals(listOf(0,1,0,1,0,1),events.map { it.action })
        assertTrue(events.all { it.repeatCount==0 })
        assertEquals(0,voice)
        assertNull(controls.receive(11,2000).action)
        assertTrue(player.execute(BikeCommand.VOICE).contains("acionado"))
        assertEquals(1,voice)
        assertEquals(6,shadow.dispatchedMediaKeyEvents.size)
        assertNull(shadow.lastAudioFocusRequest) // never steal focus from AA/Spotify
    }
    @Test fun volumeButtonsChangeOnlyMusicVolumeAndCanUndoEachOther() {
        val app=RuntimeEnvironment.getApplication()
        val audio=app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        val player=BikeMediaControls(app) { fail("Volume must not invoke assistant"); false }
        player.execute(TripperControls.Action.UP)
        assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        player.execute(TripperControls.Action.DOWN)
        assertEquals(4,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        // Robolectric 4.17's adjustStreamVolume erroneously updates every stream via its
        // single-argument helper. Verify the actual API arguments, not that shadow bug.
        assertEquals(listOf(Triple(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0),
            Triple(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,0)),
            (Shadows.shadowOf(audio) as RecordingVolumeAudioManager).adjustments)
    }
    @Test fun unavailableAssistantDoesNotClaimSuccessOrDispatchMusic() {
        val app=RuntimeEnvironment.getApplication()
        val shadow=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
        shadow.clearDispatchedMediaKeyEvents()
        assertTrue(BikeMediaControls(app) { false }.execute(TripperControls.Action.HOME).contains("indisponível"))
        assertTrue(shadow.dispatchedMediaKeyEvents.isEmpty())
    }
    @Test fun volumeFollowsCallAndReturnsToMusicWithoutTakingAudioRouting() {
        val app=RuntimeEnvironment.getApplication()
        val audio=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(audio) as RecordingVolumeAudioManager
        val player=BikeMediaControls(app) { false }
        for(mode in listOf(AudioManager.MODE_IN_CALL,AudioManager.MODE_IN_COMMUNICATION,AudioManager.MODE_NORMAL)) {
            audio.mode=mode
            val message=player.execute(BikeCommand.VOLUME_UP)
            assertTrue(message.contains(if(mode==AudioManager.MODE_NORMAL) "mídia" else "chamada"))
            assertEquals(mode,audio.mode)
        }
        assertEquals(listOf(AudioManager.STREAM_VOICE_CALL,AudioManager.STREAM_VOICE_CALL,AudioManager.STREAM_MUSIC),shadow.adjustments.map { it.first })
        assertNull(shadow.lastAudioFocusRequest)
    }
    @Test fun serviceDiscardsHomeCalibrationAndKeepsSeparateCentersOnRestart() {
        val app=RuntimeEnvironment.getApplication()
        val old=app.getSharedPreferences("auto-controls",Context.MODE_PRIVATE)
        val saved=app.getSharedPreferences("auto-media-buttons",Context.MODE_PRIVATE)
        old.edit().clear().putInt("UP",6).putInt("DOWN",7).putInt("LEFT",10)
            .putInt("RIGHT",9).putInt("SELECT",5).putInt("BACK",24).commit()
        saved.edit().clear().commit()
        val first=Robolectric.buildService(AutoBridgeService::class.java).create()
        try {
            assertEquals(-1,saved.getInt("HOME",-1))
            assertEquals(5,saved.getInt("SELECT",-1))
            assertEquals(24,old.getInt("BACK",-1))
        } finally { first.destroy() }
        saved.edit().putInt("HOME",11).commit()
        val second=Robolectric.buildService(AutoBridgeService::class.java).create()
        try { assertEquals(-1,saved.getInt("HOME",-1)); assertEquals(24,saved.getInt("MAP_ENTER",-1)) } finally { second.destroy() }
    }
}
