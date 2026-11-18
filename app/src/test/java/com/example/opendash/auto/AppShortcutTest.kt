package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class AppShortcutTest {
    private fun icon(pixels:IntArray,width:Int,cx:Int,cy:Int,phone:Boolean) {
        for(y in -17..17) for(x in -17..17) if(x*x+y*y<17*17) {
            pixels[(cy+y)*width+cx+x]=0xff25d366.toInt()
            if(phone && ((x*x+y*y in 70..115) || (x in -5..0 && y in 0..5))) pixels[(cy+y)*width+cx+x]=0xffffffff.toInt()
            else if(!phone && y in -3..3) pixels[(cy+y)*width+cx+x]=0xff000000.toInt()
        }
    }
    private fun matcher():WhatsappIcon {
        val pixels=IntArray(80*80); icon(pixels,80,40,40,true)
        return WhatsappIcon.fromTemplate(AaSourceFrame(80,80,pixels))!!
    }
    @Test fun matchesInstalledGlyphAtScreenCoordinatesAndRejectsSpotifyGreen() {
        val pixels=IntArray(96000); icon(pixels,400,200,100,true); icon(pixels,400,70,100,false)
        assertEquals(WhatsappIcon.Point(400,200),matcher().find(AaSourceFrame(400,240,pixels)))
        val spotify=IntArray(96000); icon(spotify,400,200,100,false)
        assertNull(matcher().find(AaSourceFrame(400,240,spotify)))
    }
    @Test fun adaptiveSquareTemplateMatchesCircularIconWithDisconnectedGreenIslands() {
        fun draw(p:IntArray,w:Int,cx:Int,cy:Int,square:Boolean) {
            for(y in -17..17) for(x in -17..17) {
                val radius=x*x+y*y
                if(square || radius<17*17) {
                    p[(cy+y)*w+cx+x]=if(radius in 150..205 || (x in -4..0 && y in -3..5)) -1 else 0xff25d366.toInt()
                }
            }
        }
        val template=IntArray(80*80); draw(template,80,40,40,true)
        val pixels=IntArray(96000); draw(pixels,400,200,100,false)
        val matcher=WhatsappIcon.fromTemplate(AaSourceFrame(80,80,template))!!
        assertEquals(WhatsappIcon.Point(400,200),matcher.find(AaSourceFrame(400,240,pixels)))
    }
    @Test fun launcherIconWinsOverTheSameAppInTheBottomBar() {
        val pixels=IntArray(96000); icon(pixels,400,200,100,true); icon(pixels,400,100,220,true)
        assertEquals(WhatsappIcon.Point(400,200),matcher().find(AaSourceFrame(400,240,pixels)))
    }
    @Test fun ambiguousIconsAndWrongResolutionDoNotTriggerTaps() {
        val pixels=IntArray(96000); icon(pixels,400,100,100,true); icon(pixels,400,200,100,true)
        assertNull(matcher().find(AaSourceFrame(400,240,pixels)))
        assertNull(matcher().find(AaSourceFrame(800,480,IntArray(800*480))))
    }
    @Test fun toggleTracksExplicitMapAndSpotifyCommandsAndFailedLaunchDoesNotAdvance() {
        val toggle=MapSpotifyToggle()
        assertEquals(BikeCommand.OPEN_SPOTIFY,toggle.next())
        assertEquals(BikeCommand.OPEN_SPOTIFY,toggle.next()) // failed request: opened was not called
        toggle.opened(BikeCommand.OPEN_SPOTIFY)
        assertEquals(BikeCommand.OPEN_MAPS,toggle.next())
        toggle.opened(BikeCommand.OPEN_MAPS)
        assertEquals(BikeCommand.OPEN_SPOTIFY,toggle.next())
        toggle.opened(BikeCommand.OPEN_WHATSAPP)
        assertEquals(BikeCommand.OPEN_SPOTIFY,toggle.next())
    }
}
