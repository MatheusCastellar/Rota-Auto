package com.example.opendash.auto

import android.content.Context
import android.graphics.Bitmap
import android.icu.text.BreakIterator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ProfilePopupTest {
    private fun count(value:String):Int {
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        var count=0; iterator.first()
        while(iterator.next()!=BreakIterator.DONE) count++
        return count
    }
    @Test fun everyCommandInEveryLanguageFitsAndCustomUsesAppName() {
        val table=StringBuilder("| Comando | Português | English | Español | हिन्दी |\n|---|---|---|---|---|\n")
        for(command in BikeCommand.entries) {
            val labels=UiLanguage.codes.map { CommandNames.short(command,it) }
            labels.forEach { assertTrue("$command: $it",it.isNotBlank() && count(it)<=10) }
            table.append("| ${command.label} | ${labels.joinToString(" | ")} |\n")
        }
        for(language in UiLanguage.codes) {
            assertEquals("Waze",CommandNames.short(BikeCommand.OPEN_OTHER,language,"Waze"))
            assertEquals("YouTube M…",CommandNames.short(BikeCommand.OPEN_OTHER,language,"YouTube Music"))
        }
        File("build/reports/command-names.md").apply { parentFile.mkdirs(); writeText(table.toString()) }
    }
    @Test fun truncationKeepsUnicodeGraphemesAndRemovesControlCharacters() {
        val grapheme="क्षि"
        assertEquals(grapheme.repeat(9)+"…",CommandNames.fit(grapheme.repeat(12)))
        assertEquals("Waze",CommandNames.fit("Waze\n\u0000"))
        assertEquals("1234567890",CommandNames.fit("1234567890"))
        assertEquals("123456789…",CommandNames.fit("12345678901"))
    }
    @Test fun snapshotUsesInsideCurrentProfileAndEachGestureWithoutChangingBindings() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().clear().putInt(BikeControlLayout.PROFILE,2)
            .putString(BikeControlLayout.key(2,TripperControls.Action.LEFT,false),"OPEN_OTHER")
            .putString(BikeControlLayout.appKey(2,TripperControls.Action.LEFT,false),"com.example.Waze")
            .putString(BikeControlLayout.key(2,TripperControls.Action.LEFT,true),"VOICE")
            .putString(BikeControlLayout.key(2,TripperControls.Action.MAP_LEFT,false),"VOLUME_UP").commit()
        val before=prefs.all.toMap()
        val state=ProfilePopup.read(context)
        assertEquals("Perfil 2",state.title)
        assertEquals(5,state.slots.size)
        assertEquals("Waze",state.slots[3].single)
        assertEquals("Voz",state.slots[3].double)
        assertEquals(before,prefs.all)
        assertFalse(prefs.getBoolean(ProfilePopup.ENABLED,false))
        prefs.edit().putBoolean(ProfilePopup.ENABLED,true).commit()
        assertEquals(true,SettingsImport.parse(SettingsExport.create(context,false)).options[ProfilePopup.ENABLED])
    }
    @Test fun durationDefaultsToTwoSecondsAndRoundTripsHalfSecondLimits() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(2_000L,ProfilePopup.read(context).durationMs)
        for((steps,millis) in listOf(1 to 500L,4 to 2_000L,9 to 4_500L,20 to 10_000L)) {
            prefs.edit().putInt(ProfilePopup.DURATION,steps).commit()
            val backup=SettingsImport.parse(SettingsExport.create(context,false))
            prefs.edit().remove(ProfilePopup.DURATION).commit()
            SettingsImport.apply(context,backup)
            assertEquals(millis,ProfilePopup.read(context).durationMs)
        }
        for(invalid in listOf(0,21)) {
            val bad=org.json.JSONObject(SettingsExport.create(context,false))
            bad.getJSONObject("options").put(ProfilePopup.DURATION,invalid)
            assertTrue(runCatching { SettingsImport.parse(bad.toString()) }.isFailure)
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderAllLanguagesAndAllThreeProfilesForVisualReview() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        for(language in UiLanguage.codes) for(profile in 1..3) {
            prefs.edit().clear().putInt(BikeControlLayout.PROFILE,profile).putString(UiLanguage.KEY,language).commit()
            if(profile==3) prefs.edit().putString(BikeControlLayout.key(3,TripperControls.Action.RIGHT,true),"VOICE_AND_BAR").commit()
            val bitmap=ProfilePopup.bitmap(ProfilePopup.read(context))
            assertEquals(1052,bitmap.width); assertEquals(600,bitmap.height)
            File("build/reports/profile-popup-$language-$profile.png").apply { parentFile.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            bitmap.recycle()
        }
    }
}
