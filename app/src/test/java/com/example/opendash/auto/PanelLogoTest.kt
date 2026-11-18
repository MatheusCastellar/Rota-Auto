package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PanelLogoTest {
    @Test fun allSuppliedLogosHaveTransparentBackgroundAndAntialiasedWhiteArtwork() {
        for(logo in PanelLogo.entries.filter { it!=PanelLogo.NONE }) {
            val bitmap=logo.bitmap(RuntimeEnvironment.getApplication().resources)!!
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            assertTrue(logo.name,pixels.any { (it ushr 24)==0 })
            assertTrue(logo.name,pixels.any { (it ushr 24)==255 })
            assertTrue(logo.name,pixels.any { (it ushr 24) in 1..254 })
            assertFalse(logo.name,pixels.any { (it ushr 24)>0 && (it and 0xffffff)!=0xffffff })
            bitmap.recycle()
        }
    }
    @Test fun artworkOrderAndEmptyDefaultMatchSelection() {
        assertEquals(listOf("Sem nada","Himalayan","Guerrilla","Bear"),PanelLogo.entries.map { it.label })
        assertEquals(PanelLogo.NONE,PanelLogo.resolve(null))
        assertNull(PanelLogo.NONE.bitmap(RuntimeEnvironment.getApplication().resources))
    }
    @Test fun suppliedLogosDecodeWithoutTheirLargeEmptyBorders() {
        val resources=RuntimeEnvironment.getApplication().resources
        for(logo in PanelLogo.entries.filter { it!=PanelLogo.NONE }) {
            val bitmap=logo.bitmap(resources)!!
            assertTrue(bitmap.width>200)
            assertTrue(bitmap.height in 50..220)
            assertTrue(bitmap.width.toFloat()/bitmap.height>1.8f)
            bitmap.recycle()
        }
    }
}
