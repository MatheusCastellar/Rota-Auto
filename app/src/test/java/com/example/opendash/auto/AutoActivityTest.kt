package com.example.opendash.auto

import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class AutoActivityTest {
    @Test fun bitrateSwitchDisablesManualSliderAndFactoryResetOnlyRestoresVideo() {
        val p=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        p.edit().putInt("video-fps",4).putInt(VideoProfile.BITRATE,350).putBoolean(VideoProfile.MANUAL,true)
            .putInt(PanelLogo.SIZE,120).commit()
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            val toggle=tree.filterIsInstance<Switch>().single { it.text=="Ajustar kb/s manualmente" }
            val slider=tree.filterIsInstance<SeekBar>().single { it.contentDescription=="Taxa de vídeo" }
            assertTrue(slider.isEnabled)
            toggle.isChecked=false; assertFalse(slider.isEnabled)
            assertEquals(VideoProfile(4,200000,true),VideoProfile.fromPreferences(p))
            toggle.isChecked=true; assertTrue(slider.isEnabled)
            assertEquals(VideoProfile(4,350000,false),VideoProfile.fromPreferences(p))
            tree.filterIsInstance<RadioButton>().single { it.tag==8 }.isChecked=true
            assertEquals(VideoProfile(8,400000,false),VideoProfile.fromPreferences(p))
            assertEquals(300,slider.progress)
            tree.filterIsInstance<RadioButton>().single { it.tag==4 }.isChecked=true
            assertEquals(VideoProfile(4,200000,false),VideoProfile.fromPreferences(p))
            assertEquals(100,slider.progress)
            tree.filterIsInstance<Button>().single { it.text=="Restaurar vídeo de fábrica" }.performClick()
            assertFalse(slider.isEnabled); assertFalse(toggle.isChecked)
            assertEquals(VideoProfile(4,200000,true),VideoProfile.fromPreferences(p))
            assertEquals(120,p.getInt(PanelLogo.SIZE,0))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun developerProbeListsAllSymbolsAndStopsWithoutSavingTestOverrides() {
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            views(c.get().window.decorView).filterIsInstance<Button>().single { it.text=="Desenvolvedor" }.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val buttons=views(dialog.window!!.decorView).filterIsInstance<Button>()
            val choose=buttons.single { it.text=="Escolher símbolo" }
            assertFalse(choose.isEnabled)
            buttons.single { it.text=="Iniciar captura" }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(choose.isEnabled)
            choose.performClick()
            val choices=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(256,choices.listView.adapter.count)
            choices.listView.performItemClick(null,90,90L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(90,BikeInputCapture.state.value.glyph)
            assertTrue(choose.text.contains("0x5A"))
            buttons.single { it.text=="Testando: Fora" }.performClick()
            assertEquals("Dentro",BikeInputCapture.state.value.context)
            buttons.single { it.text=="Parar captura" }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(BikeInputCapture.state.value.glyph)
            assertFalse(choose.isEnabled); assertFalse(BikeInputCapture.blocksActions)
            assertTrue(BikeInputCapture.snapshot().contains("símbolo=90"))
            dialog.dismiss()
        } finally { BikeInputCapture.stop(); c.pause().stop().destroy() }
    }
    @Test fun manualParkingIsOffByDefaultAndCanBeChangedWithoutStartingProjection() {
        val prefs=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val toggle=views(c.get().window.decorView).filterIsInstance<Switch>().single { it.text=="Moto parada" }
            assertFalse(toggle.isChecked)
            toggle.isChecked=true
            assertTrue(prefs.getBoolean(AaSensors.PARKED,false))
            toggle.isChecked=false
            assertFalse(prefs.getBoolean(AaSensors.PARKED,true))
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun appearanceMenuKeepsIndependentLogoControlsAndAlignmentPresets() {
        val prefs=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            tree.filterIsInstance<Button>().single { it.text=="Personalizar painel" }.performClick()
            assertEquals("Simulação do painel sem toque",(tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            tree.filterIsInstance<RadioButton>().single { it.tag=="logo.GUERRILLA" }.isChecked=true
            tree.filterIsInstance<Button>().single { it.contentDescription=="Logo Y: Baixo" }.performClick()
            assertTrue(prefs.getInt(PanelLogo.POSITION_Y,0)>150)
            assertEquals(0,prefs.getInt(PanelLayout.POSITION_Y,0))
            tree.filterIsInstance<RadioButton>().single { it.tag=="curve.ARC" }.isChecked=true
            tree.filterIsInstance<RadioButton>().single { it.tag=="blur.ALL" }.isChecked=true
            assertEquals("ARC",prefs.getString(CurveStyle.PREFERENCE,null))
            assertEquals("ALL",prefs.getString(BlurArea.PREFERENCE,null))
            tree.filterIsInstance<Button>().single { it.text=="Voltar aos ajustes" && it.isShown }.performClick()
            assertNull((tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun allLayoutsApplyWithoutStartingProjectionAndNormalPreviewRemainsDefault() {
        val prefs=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            val modes=tree.filterIsInstance<RadioGroup>().single { it.contentDescription=="Modo de imagem do painel" }
            val radios=views(modes).filterIsInstance<RadioButton>()
            assertEquals(4,radios.size)
            for(mode in PanelLayout.entries.reversed()+PanelLayout.CURVED) {
                radios.single { it.tag==mode.name }.isChecked=true
                assertEquals(mode.name,prefs.getString(PanelLayout.PREFERENCE,null))
                assertFalse(AutoBridgeService.state.value.active)
            }
            tree.filterIsInstance<Button>().single { it.text=="Ajustes" }.performClick()
            tree.filterIsInstance<Button>().single { it.text=="Personalizar painel" }.performClick()
            assertEquals("Simulação do painel sem toque",(tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            tree.filterIsInstance<Button>().single { it.text=="Conectar" }.performClick()
            assertNull((tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            val choices=tree.filterIsInstance<Spinner>().first().adapter
            assertTrue((0 until choices.count).any { choices.getItem(it).toString()==BikeCommand.VOICE_AND_BAR.label })
        } finally { c.pause().stop().destroy() }
        val recreated=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(recreated.get().window.decorView)
            assertNull((tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            assertTrue(tree.filterIsInstance<RadioButton>().single { it.tag==PanelLayout.CURVED.name }.isChecked)
        } finally { recreated.pause().stop().destroy() }
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun opensWithoutStartingProjectionOrAskingForCredentials() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE).edit().putBoolean("native-area",true).commit()
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        val tree=views(c.get().window.decorView)
        for(label in listOf("Conectar","Navegar","Ajustes")) assertTrue(tree.filterIsInstance<Button>().any { it.text==label })
        assertFalse(tree.filterIsInstance<TextView>().any { it.text.contains("Área nativa") || it.text.contains("Recorta as margens") })
        assertTrue(tree.filterIsInstance<Button>().any { it.text=="Reconectar Moto" })
        assertEquals("Destino",tree.filterIsInstance<EditText>().single().contentDescription)
        assertTrue(tree.filterIsInstance<Button>().any { it.text=="Perfil 2" && it.isSelected })
        assertTrue(tree.filterIsInstance<Button>().any { it.text=="Cima · Voz + mostrar barra" })
        assertEquals(18,tree.filterIsInstance<Spinner>().count { it.contentDescription?.toString()?.let { s -> s.endsWith(": um toque") || s.endsWith(": dois toques") }==true })
        assertTrue(tree.filterIsInstance<SeekBar>().any { it.contentDescription=="Taxa de vídeo" })
        assertTrue(tree.filterIsInstance<SeekBar>().any { it.contentDescription=="Tempo dos comandos" })
        assertFalse(AutoBridgeService.state.value.active)
        assertEquals(0,c.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        c.pause().stop().destroy()
    }
    @Test fun editsBothGesturesAndKeepsModesIndependentAcrossActivityRecreation() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            var tree=views(c.get().window.decorView)
            val up=tree.filterIsInstance<Spinner>().single { it.contentDescription=="Cima: um toque" }
            up.setSelection(BikeCommand.NEXT.ordinal)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("NEXT",prefs.getString(BikeControlLayout.key(true,TripperControls.Action.UP,false),null))
            tree.filterIsInstance<Button>().single { it.text.toString()=="Perfil 1" }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            tree=views(c.get().window.decorView)
            assertEquals(BikeCommand.VOICE_AND_BAR.ordinal,tree.filterIsInstance<Spinner>().single { it.contentDescription=="Cima: um toque" }.selectedItemPosition)
            val downDouble=tree.filterIsInstance<Spinner>().single { it.contentDescription=="Baixo: dois toques" }
            downDouble.setSelection(BikeCommand.AA_APPS.ordinal)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("AA_APPS",prefs.getString(BikeControlLayout.key(false,TripperControls.Action.DOWN,true),null))
            tree.filterIsInstance<Button>().single { it.text.toString()=="Perfil 3" }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            tree=views(c.get().window.decorView)
            val third=tree.filterIsInstance<Spinner>().single { it.contentDescription=="Cima: um toque" }
            third.setSelection(BikeCommand.BACK.ordinal)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("BACK",prefs.getString(BikeControlLayout.key(3,TripperControls.Action.UP,false),null))
            assertEquals("NEXT",prefs.getString(BikeControlLayout.key(2,TripperControls.Action.UP,false),null))
            tree.filterIsInstance<Button>().single { it.text.toString()=="Perfil 1" }.performClick()
        } finally { c.pause().stop().destroy() }
        val recreated=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(recreated.get().window.decorView)
            assertTrue(tree.filterIsInstance<Button>().single { it.text.toString()=="Perfil 1" }.isSelected)
            assertEquals(BikeCommand.AA_APPS.ordinal,tree.filterIsInstance<Spinner>().single { it.contentDescription=="Baixo: dois toques" }.selectedItemPosition)
            assertEquals("NEXT",prefs.getString(BikeControlLayout.key(true,TripperControls.Action.UP,false),null))
        } finally { recreated.pause().stop().destroy() }
    }
    @Test fun receivedLocationPrefillsWithoutStartingNavigationUntilUserRequestsIt() {
        val incoming=android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,"Encontro: https://maps.google.com/?q=-22.9,-43.1")
        val c=Robolectric.buildActivity(AutoActivity::class.java,incoming).setup()
        try {
            val tree=views(c.get().window.decorView)
            val activity=c.get()
            assertEquals("https://maps.google.com/?q=-22.9,-43.1",tree.filterIsInstance<EditText>().single().text.toString())
            val shadow=org.robolectric.Shadows.shadowOf(activity)
            assertNull(shadow.nextStartedActivity)
            tree.filterIsInstance<Button>().single { it.text=="Traçar rota" }.performClick()
            val route=shadow.nextStartedActivity
            assertEquals(MapsRoute.PACKAGE,route.`package`)
            assertEquals("-22.9,-43.1",route.data!!.getQueryParameter("destination"))
            assertEquals("two-wheeler",route.data!!.getQueryParameter("travelmode"))
            assertEquals("navigate",route.data!!.getQueryParameter("dir_action"))
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun originalVoiceButtonPersistsUnityGainWithoutStartingProjection() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt(VoiceGain.PREFERENCE,225).commit()
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            assertEquals(125,tree.filterIsInstance<SeekBar>().single { it.contentDescription?.toString()?.startsWith("Ganho do assistente e GPS")==true }.progress)
            tree.filterIsInstance<Button>().single { it.text=="Voz no nível original · 100%" }.performClick()
            assertEquals(100,prefs.getInt(VoiceGain.PREFERENCE,-1))
            assertEquals(0,tree.filterIsInstance<SeekBar>().single { it.contentDescription?.toString()?.startsWith("Ganho do assistente e GPS")==true }.progress)
            assertTrue(tree.filterIsInstance<TextView>().any { it.text.toString()=="Ganho da voz: 100%" })
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun nativeAudioControlsHideBridgeGainAndKeepIndependentFallbackChoices() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("auto-controls-options",android.content.Context.MODE_PRIVATE)
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            val output=tree.filterIsInstance<Switch>().single { it.text=="Som direto no celular" }
            val input=tree.filterIsInstance<Switch>().single { it.text=="Microfone gerenciado pelo Android Auto" }
            assertTrue(output.isChecked)
            assertFalse(input.isChecked)
            val gainContainer=tree.filterIsInstance<SeekBar>().single { it.contentDescription?.toString()?.startsWith("Ganho do assistente e GPS")==true }.parent as View
            assertEquals(View.GONE,gainContainer.visibility)
            output.isChecked=false
            assertEquals(View.VISIBLE,gainContainer.visibility)
            assertFalse(PhoneAudio.output(app))
            input.isChecked=false
            assertFalse(PhoneAudio.input(app))
            input.isChecked=true
            assertTrue(PhoneAudio.input(app))
            input.isChecked=false
            output.isChecked=true
            assertTrue(PhoneAudio.output(app))
            assertFalse(PhoneAudio.input(app))
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
}





