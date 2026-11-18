package com.example.opendash.auto

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SettingsExportTest {
    @Test fun oldBackupMigratesOutsideActionsAndDoesNotOverwriteOtherProfilesOnSingleImport() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val old=JSONObject(SettingsExport.create(app,true)).put("version",1)
        val saved=old.getJSONObject("profiles").getJSONObject(old.getInt("activeProfile").toString())
        TripperControls.mapActions.forEach { saved.remove(it.name) }
        saved.put("HOME",JSONObject().put("tap","VOICE").put("double","NONE"))
        saved.getJSONObject("LEFT").put("tap","OPEN_SPOTIFY")
        prefs.edit().clear().putString(BikeControlLayout.key(3,TripperControls.Action.LEFT,false),"BACK").commit()
        SettingsImport.apply(app,SettingsImport.parse(old.toString()),1)
        assertEquals("OPEN_SPOTIFY",prefs.getString(BikeControlLayout.key(1,TripperControls.Action.MAP_LEFT,false),null))
        assertEquals("NONE",prefs.getString(BikeControlLayout.key(1,TripperControls.Action.MAP_ENTER,false),null))
        assertEquals("BACK",prefs.getString(BikeControlLayout.key(3,TripperControls.Action.MAP_LEFT,false),null))
        val roundTrip=JSONObject(SettingsExport.create(app,false))
        assertEquals(4,roundTrip.getInt("version"))
        assertFalse(roundTrip.getJSONObject("profiles").getJSONObject("1").has("HOME"))
    }
    @Test fun newBackupKeepsOutsideAndInsideBindingsIndependent() {
        val app=RuntimeEnvironment.getApplication()
        val p=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().putString(BikeControlLayout.key(1,TripperControls.Action.MAP_RIGHT,false),"PROFILE_NEXT")
            .putString(BikeControlLayout.key(1,TripperControls.Action.RIGHT,false),"NEXT").commit()
        val backup=SettingsImport.parse(SettingsExport.create(app,false))
        p.edit().clear().commit(); SettingsImport.apply(app,backup)
        assertEquals("PROFILE_NEXT",p.getString(BikeControlLayout.key(1,TripperControls.Action.MAP_RIGHT,false),null))
        assertEquals("NEXT",p.getString(BikeControlLayout.key(1,TripperControls.Action.RIGHT,false),null))
    }
    @Test fun fullBackupRoundTripRestoresOptionsBindingsAndCalibrationWithoutTouchingWifi() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val calibration=app.getSharedPreferences("auto-media-buttons",Context.MODE_PRIVATE)
        val wifi=app.getSharedPreferences("wifi",Context.MODE_PRIVATE)
        prefs.edit().putInt(BikeControlLayout.PROFILE,2).putInt(PanelLogo.SIZE,75).putBoolean(PhoneAudio.OUTPUT,false)
            .putString(BikeControlLayout.key(3,TripperControls.Action.DOWN,true),"VOICE_AND_BAR").commit()
        calibration.edit().putInt("UP",6).commit()
        wifi.edit().putString("password","preserved").commit()
        val backup=SettingsImport.parse(SettingsExport.create(app,false))
        prefs.edit().putInt(PanelLogo.SIZE,199).putInt(PanelLayout.POSITION_Y,50).putBoolean(AaSensors.PARKED,true).commit()
        calibration.edit().clear().putInt("UP",7).commit()
        SettingsImport.apply(app,backup)
        assertEquals(75,prefs.getInt(PanelLogo.SIZE,0))
        assertFalse(prefs.contains(PanelLayout.POSITION_Y))
        assertFalse(prefs.getBoolean(PhoneAudio.OUTPUT,true))
        assertFalse(prefs.getBoolean(AaSensors.PARKED,true))
        assertEquals(2,BikeControlLayout.active(prefs))
        assertEquals("VOICE_AND_BAR",prefs.getString(BikeControlLayout.key(3,TripperControls.Action.DOWN,true),null))
        assertTrue(calibration.all.isEmpty())
        assertEquals("preserved",wifi.getString("password",null))
    }
    @Test fun importingSingleProfileCanTargetAnotherSlotWithoutChangingOtherSettings() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().putInt(BikeControlLayout.PROFILE,3).putInt(PanelLogo.SIZE,80)
            .putString(BikeControlLayout.key(3,TripperControls.Action.UP,false),"BACK")
            .putString(BikeControlLayout.key(2,TripperControls.Action.UP,false),"NEXT").commit()
        SettingsImport.apply(app,SettingsImport.parse(SettingsExport.create(app,true)),1)
        assertEquals("BACK",prefs.getString(BikeControlLayout.key(1,TripperControls.Action.UP,false),null))
        assertEquals("NEXT",prefs.getString(BikeControlLayout.key(2,TripperControls.Action.UP,false),null))
        assertEquals(80,prefs.getInt(PanelLogo.SIZE,0))
        assertEquals(1,BikeControlLayout.active(prefs))
    }
    @Test fun invalidCommandVersionAndRangeAreRejectedBeforeAnyPreferenceChanges() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().putInt(PanelLogo.SIZE,80).commit()
        val original=SettingsExport.create(app,false)
        val badCommand=JSONObject(original).apply { getJSONObject("profiles").getJSONObject("1").getJSONObject("UP").put("tap","INVALID") }
        val badRange=JSONObject(original).apply { getJSONObject("options").put(PanelLogo.SIZE,99999) }
        val badVersion=JSONObject(original).put("version",5)
        for(raw in listOf(badCommand.toString(),badRange.toString(),badVersion.toString(),"x".repeat(SettingsImport.MAX_BYTES+1))) {
            assertTrue(runCatching { SettingsImport.parse(raw) }.isFailure)
            assertEquals(80,prefs.getInt(PanelLogo.SIZE,0))
        }
    }
    @Test fun allExportPreservesLegacyProfilesTypesAndExcludesConnectionsAndParking() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().putBoolean(BikeControlLayout.MODE,false)
            .putString(BikeControlLayout.key(false,TripperControls.Action.UP,false),"NEXT")
            .putString(BikeControlLayout.key(true,TripperControls.Action.UP,false),"VOICE")
            .putString(BikeControlLayout.key(3,TripperControls.Action.UP,false),"BACK")
            .putBoolean(AaSensors.PARKED,true).putInt(PanelLogo.SIZE,75).putBoolean(PhoneAudio.OUTPUT,true).commit()
        app.getSharedPreferences("wifi",Context.MODE_PRIVATE).edit().putString("password","private-secret").commit()
        app.getSharedPreferences("auto-media-buttons",Context.MODE_PRIVATE).edit().putInt("UP",6).commit()
        val raw=SettingsExport.create(app,false)
        val result=JSONObject(raw)
        assertEquals(1,result.getInt("activeProfile"))
        val profiles=result.getJSONObject("profiles")
        assertEquals(3,profiles.length())
        assertEquals("NEXT",profiles.getJSONObject("1").getJSONObject("UP").getString("tap"))
        assertEquals("VOICE",profiles.getJSONObject("2").getJSONObject("UP").getString("tap"))
        assertEquals("BACK",profiles.getJSONObject("3").getJSONObject("UP").getString("tap"))
        assertEquals(75,result.getJSONObject("options").getInt(PanelLogo.SIZE))
        assertTrue(result.getJSONObject("options").getBoolean(PhoneAudio.OUTPUT))
        assertEquals(0,result.getJSONObject("calibration").length())
        assertFalse(raw.contains("private-secret")); assertFalse(raw.contains(AaSensors.PARKED))
    }
    @Test fun profileOnlyExportIncludesResolvedDefaultsWithoutOtherProfilesOrOptions() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).edit().putInt(BikeControlLayout.PROFILE,3).commit()
        val result=JSONObject(SettingsExport.create(app,true))
        assertEquals(3,result.getInt("activeProfile"))
        assertEquals(1,result.getJSONObject("profiles").length())
        assertEquals("AA_DASHBOARD_RIGHT",result.getJSONObject("profiles").getJSONObject("3").getJSONObject("UP").getString("tap"))
        assertFalse(result.has("options")); assertFalse(result.has("calibration"))
    }
}
