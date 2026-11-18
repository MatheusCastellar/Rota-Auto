package com.example.opendash.auto
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
class SpotifyPlayerShortcutTest {
    private fun fixture(name:String):AaSourceFrame {
        val image=ImageIO.read(javaClass.getResourceAsStream("/spotify/"+name))
        val pixels=IntArray(400*240) { i -> image.getRGB((105+(i%400*2+1)*870.0/800).toInt(),(1228+(i/400*2+1)*870.0/800).toInt()) }
        return AaSourceFrame(400,240,pixels)
    }
    @Test fun recognizesOurActualBrowseButtonAndRejectsTheActualPlayerAndItsRepeatArea() {
        val browse=fixture("browse.png")
        val point=SpotifyPlayerButton.find(browse)!!
        assertTrue(point.x in 740..754); assertTrue(point.y in 340..358)
        assertNull(SpotifyPlayerButton.find(fixture("player.png")))
        val mirrored=browse.copy(pixels=IntArray(400*240) { i -> browse.pixels[i/400*400+399-i%400] })
        assertTrue(SpotifyPlayerButton.find(mirrored)!!.x in 44..60)
    }
    @Test fun manualOpeningNeverTapsAndExplicitCommandRequiresTwoStableFramesThenStops() {
        val frame=fixture("browse.png"); val shortcut=SpotifyPlayerShortcut()
        assertNull(shortcut.observe(0,frame,1000))
        val id=shortcut.arm(1000)
        assertNull(shortcut.observe(id,frame,2400))
        assertNotNull(shortcut.observe(id,frame,2750))
        assertNull(shortcut.observe(id,frame,3100))
    }
    @Test fun navigationCancellationExpiryAndOldCallbacksCannotTriggerTap() {
        val frame=fixture("browse.png"); val s=SpotifyPlayerShortcut(); val old=s.arm(0)
        assertNull(s.observe(old,frame,1400)); s.cancel()
        assertNull(s.observe(old,frame,1750))
        val fresh=s.arm(2000)
        assertNull(s.observe(old,frame,3400)); assertNull(s.observe(fresh,frame,3400))
        assertNull(s.observe(fresh,frame,6501))
        val next=s.arm(7000)
        assertNull(s.observe(next,frame,8400)); assertNull(s.observe(next,null,8750))
        assertNull(s.observe(next,frame,9100))
    }
    @Test fun greenAlbumArtOrOtherResolutionCannotCauseBlindTap() {
        val f=fixture("browse.png")
        assertNull(SpotifyPlayerButton.find(f.copy(width=800)))
        assertNull(SpotifyPlayerButton.find(f.copy(pixels=IntArray(400*240) {0xff1ed760.toInt()})))
    }
}
