package com.example.opendash.auto

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class ScreenOrganizationTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun categorizedPagesKeepSettingsAndMoveOnlyThePreview() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        prefs.edit().putInt(PanelLogo.SIZE,137).putInt("video-fps",8).putBoolean(MenuControls.ENABLED,true).commit()
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val a=c.get(); val tree=views(a.window.decorView)
            val before=prefs.all.toMap()
            fun tab(label:String)=tree.filterIsInstance<Button>().single { it.text==label }.performClick()
            fun open(label:String)=tree.single { it.tag=="settings-link" && it.contentDescription.toString().startsWith("$label.") }.performClick()
            fun screen(id:Int)=assertEquals(View.VISIBLE,tree.single { it.tag=="screen-$id" }.visibility)
            tab("Painel");screen(3)
            for((label,id) in listOf("Imagem e enquadramento" to 10,"Logo da moto" to 11,"Informações do celular" to 12,"Navegação no painel" to 13,"Barra do Android Auto" to 14)) {
                open(label);screen(id)
                assertEquals("Simulação do painel sem toque",(tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
                a.onBackPressed();screen(3)
            }
            tab("Ajustes");screen(2)
            for((label,id) in listOf("Vídeo" to 20,"Som e microfone" to 21,"Conexão com a moto" to 22,"Salvar e recuperar ajustes" to 23,"Avançado" to 24,"Sobre o Rota Auto" to 25,"Atualizações" to 26)) {
                open(label);screen(id);a.onBackPressed();screen(2)
            }
            tab("Comandos");screen(4)
            tree.filterIsInstance<Button>().single { it.text=="Menu aberto" }.performClick()
            assertTrue(views(a.window.decorView).filterIsInstance<Button>().any { it.text=="Renomear perfil" })
            tab("Conectar");screen(0);tab("Navegar");screen(1);a.onBackPressed();screen(0)
            assertNull((tree.filterIsInstance<TextureView>().single().parent as View).contentDescription)
            assertNotNull(tree.single { it.tag=="language-selector" })
            assertTrue(tree.filterIsInstance<OfflinePanelPreview>().any { it.isShown })
            assertTrue(tree.any { it.contentDescription=="Abrir canal Castellar450 no YouTube" })
            assertEquals(before,prefs.all)
            assertFalse(AutoBridgeService.state.value.active)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun restoresDetailPageAfterRecreation() {
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        val state=Bundle()
        try {
            val a=c.get();views(a.window.decorView).single { it.tag=="settings-link" && it.contentDescription.toString().startsWith("Logo da moto.") }.performClick()
            c.saveInstanceState(state)
        } finally { c.pause().stop().destroy() }
        val restored=Robolectric.buildActivity(AutoActivity::class.java).setup(state)
        try {
            val tree=views(restored.get().window.decorView)
            assertEquals(View.VISIBLE,tree.single { it.tag=="screen-11" }.visibility)
            restored.get().onBackPressed()
            assertEquals(View.VISIBLE,tree.single { it.tag=="screen-3" }.visibility)
        } finally { restored.pause().stop().destroy() }
    }
}
