package com.example.opendash.auto

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SettingsRecoveryTest {
    @Test fun importCanBeUndoneAndRestorationCanAlsoBeUndoneWithoutTouchingWifi() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val wifi=app.getSharedPreferences("dash_config_encrypted",Context.MODE_PRIVATE)
        wifi.edit().putString("ssid","RE_PRIVATE").commit()
        prefs.edit().putString(MapDayNight.KEY,"NIGHT").putInt("video-fps",8).commit()
        val imported=SettingsImport.parse(SettingsExport.create(app,false))
        prefs.edit().putString(MapDayNight.KEY,"DAY").putInt("video-fps",4)
            .putString(BikeControlLayout.key(3,TripperControls.Action.UP,false),"BACK").commit()
        SettingsImport.apply(app,imported)
        assertEquals("NIGHT",prefs.getString(MapDayNight.KEY,null))
        val saved=SettingsRecovery.read(app)!!
        assertEquals("Importar configurações",saved.reason)
        assertTrue(saved.at>0)
        assertFalse(File(app.filesDir,"previous-settings.json").readText().contains("RE_PRIVATE"))
        SettingsRecovery.restore(app,saved)
        assertEquals("DAY",prefs.getString(MapDayNight.KEY,null))
        assertEquals(4,prefs.getInt("video-fps",0))
        assertEquals("BACK",prefs.getString(BikeControlLayout.key(3,TripperControls.Action.UP,false),null))
        SettingsRecovery.restore(app,SettingsRecovery.read(app)!!)
        assertEquals("NIGHT",prefs.getString(MapDayNight.KEY,null))
        assertEquals(8,prefs.getInt("video-fps",0))
        assertEquals("RE_PRIVATE",wifi.getString("ssid",null))
    }
    @Test fun factoryResetHasRecoverableOriginalSettings() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().putInt("video-fps",12).putBoolean(VideoProfile.MANUAL,true).putInt(VideoProfile.BITRATE,900).commit()
        SettingsRecovery.save(app,"Restaurar vídeo de fábrica")
        VideoProfile.restore(prefs)
        assertEquals(12,prefs.getInt("video-fps",0))
        SettingsRecovery.restore(app,SettingsRecovery.read(app)!!)
        assertEquals(12,prefs.getInt("video-fps",0))
        assertEquals(900,prefs.getInt(VideoProfile.BITRATE,0))
        assertTrue(prefs.getBoolean(VideoProfile.MANUAL,false))
    }
    @Test fun clockAndManualChoicesHaveExplicitBoundaries() {
        assertTrue(MapDayNight.isNight("AUTO",5))
        assertFalse(MapDayNight.isNight("AUTO",6))
        assertFalse(MapDayNight.isNight("AUTO",17))
        assertTrue(MapDayNight.isNight("AUTO",18))
        for(hour in 0..23) {
            assertFalse(MapDayNight.isNight("DAY",hour))
            assertTrue(MapDayNight.isNight("NIGHT",hour))
        }
    }
}
