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
class MenuControlsTest {
    private fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun editorSeparatesModesAndOutsideHasNoProfileSelector() {
        val c=org.robolectric.Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val activity=c.get()
            val p=activity.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
            fun tree()=views(activity.window.decorView)
            fun click(text:String)=tree().filterIsInstance<android.widget.Button>().single { it.text.toString()==text && it.isShown }.performClick()
            click("Ajustes"); click("Controles da moto")
            val before=p.all.filterKeys { it.startsWith("button.") }
            tree().filterIsInstance<android.widget.RadioButton>().single { it.text=="Modo menu" }.isChecked=true
            assertTrue(p.getBoolean(MenuControls.ENABLED,false))
            assertFalse(tree().filterIsInstance<android.widget.Button>().any { it.text.toString().matches(Regex("Perfil [1234]")) && it.isShown })
            click("Menu aberto")
            assertEquals(3,tree().filterIsInstance<android.widget.Button>().count { it.text.toString().matches(Regex("Perfil [1234]")) && it.isShown })
            click("Perfil 3")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(3,MenuControls.active(p))
            click("Menu fechado")
            assertFalse(tree().filterIsInstance<android.widget.Button>().any { it.text.toString().matches(Regex("Perfil [1234]")) && it.isShown })
            tree().filterIsInstance<android.widget.RadioButton>().single { it.text=="Tradicional" }.isChecked=true
            assertEquals(3,tree().filterIsInstance<android.widget.Button>().count { it.text.toString().matches(Regex("Perfil [123]")) && it.isShown })
            assertEquals(before,p.all.filterKeys { it.startsWith("button.") })
            assertTrue(tree().filterIsInstance<android.widget.TextView>().any { it.text==com.example.opendash.BuildConfig.VERSION_NAME && it.isShown })
        } finally { c.pause().stop().destroy() }
    }
    private val up=MenuDirection.UP
    private val down=MenuDirection.DOWN
    @Test fun adjustableTimeoutHonorsBothLimitsAndChangesFromLastInteraction() {
        for(ms in listOf(3000L,7000L,15000L)) {
            val s=MenuSession(idleMs=ms)
            s.press(up,100,up,down); s.press(down,200,up,down)
            assertEquals(2,s.profile)
            assertFalse(s.expire(200+ms-1)); assertTrue(s.expire(200+ms))
            assertEquals(1,s.profile)
        }
        val s=MenuSession()
        s.press(up,0,up,down); s.press(down,1000,up,down)
        s.setDuration(15000,5000); assertEquals(16000L,s.deadline)
        s.setDuration(3000,5000); assertNull(s.deadline); assertEquals(1,s.profile)
    }
    @Test fun timeoutBackupAndLegacyFourthProfileAreHandled() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().clear().commit(); assertEquals(7000L,MenuControls.duration(p))
        for(seconds in listOf(3,7,15)) {
            p.edit().putInt(MenuControls.SECONDS,seconds).commit()
            val backup=SettingsImport.parse(SettingsExport.create(c,false))
            p.edit().remove(MenuControls.SECONDS).commit(); SettingsImport.apply(c,backup)
            assertEquals(seconds*1000L,MenuControls.duration(p))
        }
        val old=org.json.JSONObject(SettingsExport.create(c,false))
        old.getJSONObject("options").put(MenuControls.ACTIVE,4).put("menu-bind.4.UP","VOICE")
        SettingsImport.apply(c,SettingsImport.parse(old.toString()))
        assertEquals(1,MenuControls.active(p))
        for(n in listOf(2,16)) {
            old.getJSONObject("options").put(MenuControls.SECONDS,n)
            assertTrue(runCatching { SettingsImport.parse(old.toString()) }.isFailure)
        }
    }
    @Test fun newDefaultsMatchOwnersThreeProfilesAndCommonOutsideControls() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().clear().commit()
        assertEquals(down,MenuControls.opener(p)); assertEquals(down,MenuControls.cycle(p))
        val expected=listOf(
            listOf("VOICE_AND_BAR","VOLUME_UP","VOICE_AND_BAR","VOLUME_DOWN","PLAY_PAUSE"),
            listOf("VOICE_AND_BAR","VOLUME_UP","NONE","VOLUME_DOWN","PLAY_PAUSE"),
            listOf("VOICE_AND_BAR","NEXT","NONE","PREVIOUS","PLAY_PAUSE"),
            listOf("AA_DASHBOARD_RIGHT","OPEN_MAPS","NONE","OPEN_SPOTIFY","NONE"))
        for(profile in 0..3) assertEquals(expected[profile],MenuDirection.entries.map { MenuControls.binding(p,profile,it).command.name })
    }
    @Test fun openingDoesNotExecuteAndRepeatedPressIsImmediate() {
        val s=MenuSession()
        assertEquals(MenuSession.Kind.OPEN,s.press(up,0,up,down).kind)
        assertEquals(MenuSession.Result(MenuSession.Kind.ACTION,1),s.press(up,1,up,down))
        assertEquals(MenuSession.Result(MenuSession.Kind.ACTION,1),s.press(up,2,up,down))
        assertEquals(7002L,s.deadline)
    }
    @Test fun cyclingThreeProfilesKeepsOpenAndClosingResetsFirst() {
        val s=MenuSession()
        s.press(up,0,up,down)
        for(i in 1..8) {
            val now=i*4000L
            assertEquals(MenuSession.Result(MenuSession.Kind.PROFILE,i%3+1),s.press(down,now,up,down))
            assertEquals(now+7000,s.deadline)
            assertFalse(s.expire(now+6999))
        }
        assertTrue(s.expire(39000))
        assertNull(s.deadline)
        assertEquals(1,s.profile)
        assertEquals(MenuSession.Result(MenuSession.Kind.OPEN,1),s.press(up,40000,up,down))
    }
    @Test fun closedActionsAlwaysUseCommonBindingsAndDontOpenMenu() {
        val s=MenuSession()
        for(p in 1..3) {
            s.profile=p
            assertEquals(MenuSession.Result(MenuSession.Kind.ACTION,0),s.press(MenuDirection.LEFT,0,up,down))
            assertNull(s.deadline)
            s.press(up,1,up,down)
            assertEquals(MenuSession.Result(MenuSession.Kind.ACTION,0),s.press(MenuDirection.RIGHT,7001,up,down))
            assertNull(s.deadline)
        }
    }
    @Test fun sameOpenAndCycleButtonOpensFirstThenCyclesAndCloseClearsState() {
        val s=MenuSession(3)
        assertEquals(MenuSession.Kind.OPEN,s.press(up,0,up,up).kind)
        assertEquals(1,s.profile)
        assertEquals(MenuSession.Kind.PROFILE,s.press(up,1,up,up).kind)
        assertEquals(2,s.profile)
        s.close()
        assertEquals(1,s.profile)
        s.touch(10)
        assertNull(s.deadline)
        assertEquals(MenuSession.Kind.OPEN,s.press(up,11,up,up).kind)
    }
    @Test fun menuBackupRoundTripsWithoutChangingTraditionalProfiles() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val old=BikeControlLayout.key(2,TripperControls.Action.UP,true)
        p.edit().clear().putString(old,"VOICE").putBoolean(MenuControls.ENABLED,true)
            .putInt(MenuControls.ACTIVE,3).putString(MenuControls.OPENER,"LEFT").putString(MenuControls.CYCLE,"CENTER")
            .putString(MenuControls.key(0,MenuDirection.RIGHT),"OPEN_OTHER")
            .putString(MenuControls.appKey(0,MenuDirection.RIGHT),"com.example.music")
            .putString(MenuControls.key(3,up),"OPEN_MAPS").commit()
        val backup=SettingsImport.parse(SettingsExport.create(c,false))
        p.edit().clear().commit()
        SettingsImport.apply(c,backup)
        assertTrue(p.getBoolean(MenuControls.ENABLED,false))
        assertEquals(3,MenuControls.active(p))
        assertEquals(MenuDirection.LEFT,MenuControls.opener(p))
        assertEquals(MenuDirection.CENTER,MenuControls.cycle(p))
        assertEquals(BikeBinding(BikeCommand.OPEN_OTHER,"com.example.music"),MenuControls.binding(p,0,MenuDirection.RIGHT))
        assertEquals(BikeCommand.OPEN_MAPS,MenuControls.binding(p,3,up).command)
        assertEquals("VOICE",p.getString(old,null))
        assertFalse(MenuControls.commands.contains(BikeCommand.PROFILE_NEXT))
    }
    @Test fun popupHasFiveSingleActionsAndThirdProfileIsLocalized() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        for((language,title) in listOf("pt-BR" to "Perfil 3","en" to "Profile 3","es" to "Perfil 3","hi" to "प्रोफ़ाइल 3")) {
            p.edit().clear().putString(UiLanguage.KEY,language).putString(MenuControls.CYCLE,"LEFT").commit()
            val popup=MenuControls.popup(c,3)
            assertEquals(title,popup.title)
            assertEquals(7000L,popup.durationMs)
            assertEquals(5,popup.slots.size)
            assertTrue(popup.slots.all { it.double==null })
            assertEquals(CommandNames.short(BikeCommand.PROFILE_NEXT,language),popup.slots[3].single)
        }
    }
}
