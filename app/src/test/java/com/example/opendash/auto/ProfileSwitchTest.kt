package com.example.opendash.auto
import android.content.Context
import android.os.Looper
import com.example.opendash.dash.DashSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ProfileSwitchTest {
    @Test fun idleReturnRunsInServiceResetsOnInputAndDoesNotChangeBindings() {
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().putInt(BikeControlLayout.PROFILE,2).putBoolean(ProfileIdleReturn.KEY,true)
            .putString(BikeControlLayout.key(2,TripperControls.Action.SELECT,false),"NONE").commit()
        val service=Robolectric.buildService(AutoBridgeService::class.java).create()
        val main=Shadows.shadowOf(Looper.getMainLooper())
        try {
            main.idleFor(Duration.ofSeconds(14)); assertEquals(2,BikeControlLayout.active(p))
            AutoBridgeService.testControl(TripperControls.Action.SELECT); main.idle()
            main.idleFor(Duration.ofSeconds(14)); assertEquals(2,BikeControlLayout.active(p))
            main.idleFor(Duration.ofSeconds(1)); assertEquals(1,BikeControlLayout.active(p))
            assertEquals("NONE",p.getString(BikeControlLayout.key(2,TripperControls.Action.SELECT,false),null))
            assertEquals(true,SettingsImport.parse(SettingsExport.create(RuntimeEnvironment.getApplication(),false)).options[ProfileIdleReturn.KEY])
            p.edit().putBoolean(ProfileIdleReturn.KEY,false).putInt(BikeControlLayout.PROFILE,3).commit(); main.idle()
            main.idleFor(Duration.ofSeconds(16)); assertEquals(3,BikeControlLayout.active(p))
        } finally { service.destroy() }
    }
    @Test fun gpsBubbleBindingTogglesSavedVisibilityAndLiveDashWithoutChangingProfile() {
        val app=RuntimeEnvironment.getApplication()
        val p=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().putInt(BikeControlLayout.PROFILE,1).putBoolean("clean-projection",true)
            .putString(BikeControlLayout.key(1,TripperControls.Action.RIGHT,false),"TOGGLE_GPS_BUBBLE")
            .putString(BikeControlLayout.key(1,TripperControls.Action.RIGHT,true),"NONE").commit()
        val service=Robolectric.buildService(AutoBridgeService::class.java).create()
        try {
            val dash=AutoBridgeService::class.java.getDeclaredField("dash").apply { isAccessible=true }.get(service.get()) as DashSession
            for(hidden in listOf(false,true,false)) {
                AutoBridgeService.testControl(TripperControls.Action.RIGHT)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(hidden,p.getBoolean("clean-projection",!hidden))
                assertEquals(hidden,dash.cleanProjection)
                assertEquals(1,BikeControlLayout.active(p))
                assertEquals(if(hidden) "Bolinha oculta" else "Bolinha visível",dash.projectionLabel)
            }
            val backup=SettingsImport.parse(SettingsExport.create(app,false))
            assertEquals("TOGGLE_GPS_BUBBLE",backup.profiles.getValue(1)["RIGHT.tap"])
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertEquals("Android Auto",dash.projectionLabel)
        } finally { service.destroy() }
    }
    @Test fun threeCapturedProfilesAreDistinctAndSavedAssignmentsRemainIndependent() {
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("profile-template-test",Context.MODE_PRIVATE)
        BikeControlLayout.migrateSplitInputs(p)
        val expected=listOf(
            listOf(BikeCommand.PLAY_PAUSE,BikeCommand.VOLUME_DOWN,BikeCommand.VOLUME_UP,BikeCommand.VOICE_AND_BAR,BikeCommand.PROFILE_NEXT),
            listOf(BikeCommand.NONE,BikeCommand.PREVIOUS,BikeCommand.NEXT,BikeCommand.VOICE_AND_BAR,BikeCommand.PROFILE_NEXT),
            listOf(BikeCommand.NONE,BikeCommand.OPEN_MAPS,BikeCommand.OPEN_SPOTIFY,BikeCommand.AA_DASHBOARD_RIGHT,BikeCommand.PROFILE_NEXT),
        )
        for(profile in 1..3) {
            assertEquals(List(4) { BikeCommand.NONE } + expected[profile-1],TripperControls.mediaActions.map { BikeControlLayout.resolve(profile,it,false,p.getString(BikeControlLayout.key(profile,it,false),null)) })
            assertTrue(TripperControls.mediaActions.all { BikeControlLayout.resolve(profile,it,true,null)==BikeCommand.NONE })
        }
        val button=TripperControls.Action.MAP_LEFT
        p.edit().putString(BikeControlLayout.key(1,button,false),"BACK").commit()
        assertEquals(BikeCommand.BACK,BikeControlLayout.resolve(1,button,false,p.getString(BikeControlLayout.key(1,button,false),null)))
        assertEquals(BikeCommand.NONE,BikeControlLayout.resolve(2,button,false,p.getString(BikeControlLayout.key(2,button,false),null)))
        p.edit().remove(BikeControlLayout.key(1,button,false)).commit()
        assertEquals(BikeCommand.NONE,BikeControlLayout.resolve(1,button,false,p.getString(BikeControlLayout.key(1,button,false),null)))
        // Restore each profile through the same remove-to-default operation as the UI.
        for(profile in 1..3) {
            val left=TripperControls.Action.LEFT
            p.edit().putString(BikeControlLayout.key(profile,left,false),"BACK").commit()
            assertEquals(BikeCommand.BACK,BikeControlLayout.resolve(profile,left,false,p.getString(BikeControlLayout.key(profile,left,false),null)))
            p.edit().remove(BikeControlLayout.key(profile,left,false)).commit()
            assertEquals(expected[profile-1][1],BikeControlLayout.resolve(profile,left,false,p.getString(BikeControlLayout.key(profile,left,false),null)))
        }
    }

    @Test fun existingBindingsAreCopiedOnceThenBothContextsCanBeEditedIndependently() {
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        for(profile in 1..3) p.edit().putString(BikeControlLayout.key(profile,TripperControls.Action.LEFT,false),"BACK")
            .putString(BikeControlLayout.key(profile,TripperControls.Action.DOWN,true),"AA_APPS").commit()
        BikeControlLayout.migrateSplitInputs(p)
        for(profile in 1..3) {
            assertEquals("BACK",p.getString(BikeControlLayout.key(profile,TripperControls.Action.MAP_LEFT,false),null))
            assertEquals("AA_APPS",p.getString(BikeControlLayout.key(profile,TripperControls.Action.MAP_DOWN,true),null))
            assertEquals("NONE",p.getString(BikeControlLayout.key(profile,TripperControls.Action.MAP_ENTER,false),null))
        }
        p.edit().putString(BikeControlLayout.key(1,TripperControls.Action.MAP_LEFT,false),"PROFILE_NEXT").commit()
        BikeControlLayout.migrateSplitInputs(p)
        assertEquals("PROFILE_NEXT",p.getString(BikeControlLayout.key(1,TripperControls.Action.MAP_LEFT,false),null))
        assertEquals("BACK",p.getString(BikeControlLayout.key(1,TripperControls.Action.LEFT,false),null))
    }
    @Test fun assignedButtonCyclesThreeProfilesAndFeedbackRestoresPlainAndroidAuto() {
        val app=RuntimeEnvironment.getApplication(); val p=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().putInt(BikeControlLayout.PROFILE,1).commit()
        for(profile in 1..3) p.edit().putString(BikeControlLayout.key(profile,TripperControls.Action.RIGHT,false),"PROFILE_NEXT")
            .putString(BikeControlLayout.key(profile,TripperControls.Action.RIGHT,true),"NONE").commit()
        val service=Robolectric.buildService(AutoBridgeService::class.java).create()
        try {
            val field=AutoBridgeService::class.java.getDeclaredField("dash").apply { isAccessible=true }
            val dash=field.get(service.get()) as DashSession
            for(expected in listOf(2,3,1)) {
                AutoBridgeService.testControl(TripperControls.Action.RIGHT)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(expected,BikeControlLayout.active(p))
                assertEquals("Perfil $expected",dash.projectionLabel)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999))
                assertEquals("Perfil $expected",dash.projectionLabel)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                assertEquals("Android Auto",dash.projectionLabel)
            }
        } finally { service.destroy() }
    }
}
