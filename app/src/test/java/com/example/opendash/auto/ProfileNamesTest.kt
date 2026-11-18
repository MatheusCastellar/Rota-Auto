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
class ProfileNamesTest {
    private fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun renameDialogSavesBothModesAndCustomLabelsAreNotTranslated() {
        val controller=org.robolectric.Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val a=controller.get()
            for(mode in listOf(false,true)) {
                ProfileNames.edit(a,mode,1)
                val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText(if(mode) "Navegação" else "Música")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertEquals("Música",ProfileNames.label(a,false,1))
            assertEquals("Navegação",ProfileNames.label(a,true,1))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            a.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).edit().putString(UiLanguage.KEY,"en").commit()
            UiLanguage.apply(a.window.decorView)
            assertTrue(views(a.window.decorView).filterIsInstance<android.widget.Button>().any { it.text.toString()=="Música" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun modeNamesAreIndependentAndEmptyRestoresLocalizedDefault() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().clear().putInt(BikeControlLayout.PROFILE,1).putString(ProfileNames.key(false,1),"Música").putString(ProfileNames.key(true,1),"Navegação").commit()
        assertEquals("Música",ProfilePopup.read(c).title)
        assertEquals("Navegação",MenuControls.popup(c,1).title)
        p.edit().putString(ProfileNames.key(true,1),"  ").putString(UiLanguage.KEY,"en").commit()
        assertEquals("Profile 1",MenuControls.popup(c,1).title)
        assertEquals("Música",ProfilePopup.read(c).title)
        assertEquals("12345678901234567890",ProfileNames.clean("12345678901234567890"))
        assertEquals("1234567890123456789…",ProfileNames.clean("123456789012345678901"))
        assertEquals("क्षि".repeat(19)+"…",ProfileNames.clean("क्षि".repeat(21)))
        assertEquals("Música",ProfileNames.clean("\nMúsica\u0000"))
    }
    @Test fun fullAndIndividualBackupsKeepNamesInCorrectModeAndTargetProfile() {
        val c=RuntimeEnvironment.getApplication()
        val p=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().clear().putInt(BikeControlLayout.PROFILE,2)
            .putString(ProfileNames.key(false,2),"Estrada").putString(ProfileNames.key(true,2),"Música").commit()
        val full=SettingsImport.parse(SettingsExport.create(c,false))
        val single=SettingsImport.parse(SettingsExport.create(c,true))
        p.edit().clear().commit(); SettingsImport.apply(c,full)
        assertEquals("Estrada",ProfileNames.label(c,false,2)); assertEquals("Música",ProfileNames.label(c,true,2))
        SettingsImport.apply(c,single,3)
        assertEquals("Estrada",ProfileNames.label(c,false,3)); assertEquals("Perfil 3",ProfileNames.label(c,true,3))
        val legacy=org.json.JSONObject(SettingsExport.create(c,true)).apply { remove("profileName") }
        SettingsImport.apply(c,SettingsImport.parse(legacy.toString()),2)
        assertEquals("Estrada",ProfileNames.label(c,false,2))
    }
}
