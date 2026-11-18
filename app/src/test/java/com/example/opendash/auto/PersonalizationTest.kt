package com.example.opendash.auto

import android.content.Context
import android.graphics.Color
import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalizationTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
    @Test fun timedBarBoundsAndToggleDoNotExpireUnexpectedly() {
        for((configured,expected) in listOf(0 to 1,1 to 1,5 to 5,30 to 30,99 to 30)) {
            val bar=TemporaryBar(); bar.configure(configured,false); bar.show(0)
            assertEquals(1f,bar.progress(250L+expected*1000-1),0f)
            assertEquals(0f,bar.progress(500L+expected*1000),0f)
        }
        val bar=TemporaryBar(); bar.configure(5,true); bar.show(1000)
        assertEquals(1f,bar.progress(60000),0f)
        bar.show(60000); assertEquals(.5f,bar.progress(60125),.001f)
        bar.show(60125); assertEquals(.5f,bar.progress(60125),.001f)
        assertEquals(1f,bar.progress(60375),0f)
        bar.show(61000); assertEquals(0f,bar.progress(62000),0f)
    }
    @Test fun widgetDefaultsAndGeometryAreIndependentOfLogo() {
        assertFalse(WidgetStyle.read(prefs).enabled)
        val mesh=PanelWidget.mesh(150,27,-12)
        val actual=PanelGeometry.bounds(mesh); val expected=PanelWidget.bounds(150,27,-12)
        assertEquals(expected.left,actual.left,.001f); assertEquals(expected.top,actual.top,.001f)
        assertEquals(expected.width,actual.width,.001f); assertEquals(expected.height,actual.height,.001f)
        prefs.edit().putBoolean(PanelWidget.ENABLED,true).putBoolean(PanelWidget.FRONT,false).commit()
        assertTrue(WidgetStyle.read(prefs).enabled); assertFalse(WidgetStyle.read(prefs).front)
        assertNull(prefs.getString(PanelLogo.PREFERENCE,null))
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun widgetBackgroundKeepsTransparentCornersOrBlackSquare() {
        val state=WidgetState("14/11","15:30",65,false,"5G")
        val transparent=PanelWidget.bitmap(state,OverlayBackground.TRANSPARENT)
        val rounded=PanelWidget.bitmap(state,OverlayBackground.ROUND)
        val square=PanelWidget.bitmap(state,OverlayBackground.SQUARE)
        val block=PanelWidget.bitmap(state,OverlayBackground.SQUARE,WidgetLayout.BLOCK)
        assertTrue(block.height<block.width/2)
        assertEquals(0,Color.alpha(transparent.getPixel(0,0)))
        assertEquals(0,Color.alpha(rounded.getPixel(0,0)))
        assertEquals(Color.BLACK,square.getPixel(0,0))
        assertEquals(Color.BLACK,rounded.getPixel(280,3))
        listOf(transparent,rounded,square,block).forEach { it.recycle() }
    }
    @Test fun mobileLabelUsesDisplayOverrideAndNeverInventsSignal() {
        assertEquals("5G",PhoneWidgetStatus.cellular(13,3))
        assertEquals("5G",PhoneWidgetStatus.cellular(20,0))
        assertEquals("4G",PhoneWidgetStatus.cellular(13,0))
        assertEquals("3G",PhoneWidgetStatus.cellular(15,0))
        assertEquals("—",PhoneWidgetStatus.cellular(0,0))
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun widgetContentIsCenteredInsideItsOwnBackgroundInBothLayouts() {
        for(layout in WidgetLayout.entries) {
            val bitmap=PanelWidget.bitmap(WidgetState("14/11","01:42",100,true,"5G"),OverlayBackground.TRANSPARENT,layout)
            val rows=if(layout==WidgetLayout.BLOCK) listOf(0 until bitmap.height/2,bitmap.height/2 until bitmap.height) else listOf(0 until bitmap.height)
            for(row in rows) {
                var left=bitmap.width; var right=-1
                for(y in row) for(x in 0 until bitmap.width) if(Color.alpha(bitmap.getPixel(x,y))>128) { left=minOf(left,x); right=maxOf(right,x) }
                assertTrue(right>left)
                assertEquals(bitmap.width/2f,(left+right)/2f,6f)
            }
            bitmap.recycle()
        }
    }
    @Test fun fullBackupRestoresNewAppearanceTimingLanguageAndBitrate() {
        prefs.edit().putBoolean(PanelWidget.ENABLED,true).putBoolean(PanelWidget.FRONT,false)
            .putString(PanelWidget.LAYOUT,"BLOCK").putInt(PanelWidget.HEIGHT,175)
            .putInt(PanelWidget.SIZE,140).putInt(PanelWidget.X,-41).putInt(PanelWidget.Y,73)
            .putString(PanelWidget.BACKGROUND,"ROUND").putString(PanelLogo.BACKGROUND,"SQUARE")
            .putInt(TemporaryBar.SECONDS,17).putBoolean(TemporaryBar.TOGGLE,true)
            .putInt(VideoProfile.BITRATE,450).putString(UiLanguage.KEY,"hi").commit()
        val values=prefs.all.toMap(); val backup=SettingsImport.parse(SettingsExport.create(app,false))
        prefs.edit().clear().commit(); SettingsImport.apply(app,backup)
        for((key,value) in values) assertEquals(key,value,prefs.all[key])
        assertEquals(450000,VideoProfile.fromPreferences(prefs).bitrate)
    }
    @Test fun languagesTranslateSettingsAndDynamicLabelsWithoutChangingUserInput() {
        for((language,label) in listOf("en" to "Start Android Auto","es" to "Iniciar Android Auto","hi" to "Android Auto चालू करें")) {
            prefs.edit().putString(UiLanguage.KEY,language).commit()
            assertEquals(label,UiLanguage.text(app,"Ligar Android Auto"))
            assertNotEquals("Compartilhado",UiLanguage.text(app,"Compartilhado"))
            assertFalse(UiLanguage.text(app,"Perfil 2").startsWith("Perfil ") && language=="en")
            val field=EditText(app).apply { setText("Meu destino") }; UiLanguage.apply(field)
            assertEquals("Meu destino",field.text.toString())
            val status=TextView(app); UiLanguage.apply(status); status.text="Moto desconectada"
            assertEquals(UiLanguage.text(app,"Moto desconectada"),status.text.toString())
        }
    }
    @Test fun advancedSettingsStayCollapsedAndOutsideCenterIsRed() {
        val c=Robolectric.buildActivity(AutoActivity::class.java).setup()
        try {
            val tree=views(c.get().window.decorView)
            assertTrue(tree.filterIsInstance<Button>().any { it.tag=="server-gear" })
            assertEquals(1,tree.count { it.tag=="language-selector" })
            val gain=tree.filterIsInstance<SeekBar>().single { it.contentDescription.toString().startsWith("Ganho do assistente") }
            assertFalse(gain.isShown)
            tree.filterIsInstance<Button>().single { it.text.toString()=="Ajustes" }.performClick()
            tree.filterIsInstance<Button>().single { it.text.toString()=="Opções avançadas" }.performClick()
            val center=tree.filterIsInstance<Button>().single { it.text.toString().endsWith("Compartilhado") }
            val span=center.text as android.text.Spanned
            assertTrue(span.getSpans(0,span.length,android.text.style.ForegroundColorSpan::class.java).any { it.foregroundColor==Color.parseColor("#FF7878") })
        } finally { c.pause().stop().destroy() }
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
