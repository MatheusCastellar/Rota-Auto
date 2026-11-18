package com.example.opendash.auto
import com.example.opendash.dash.*
import org.junit.Assert.*
import org.junit.Test
class DashMusicMarqueeTest {
    @Test fun longSongAndArtistScrollAfterInitialHoldAndResetForNextSong() {
        val m=DashMusicMarquee(); val t=DashTrack("The Way I Are","Timbaland, Keri Hilson",true)
        val full="The Way I Are - Timbaland, Keri Hilson"
        assertEquals(full.take(19),m.text(t,1000))
        assertEquals(full.take(19),m.text(t,2499))
        assertEquals(full.substring(1,20),m.text(t,2500))
        assertEquals(full.substring(2,21),m.text(t,3150))
        assertEquals("Another song - Sing",m.text(DashTrack("Another song","Singer",true),4000))
    }
    @Test fun shortTrackStaysStillPauseHoldsBeginningAndNoTitleClearsIt() {
        val m=DashMusicMarquee()
        assertEquals("Song - Artist",m.text(DashTrack("Song","Artist",true),1000))
        assertEquals("Song - Artist",m.text(DashTrack("Song","Artist",true),9000))
        val t=DashTrack("A very long title for a song","Artist",false)
        assertEquals(t.title.take(19),m.text(t,10000))
        assertEquals(t.title.take(19),m.text(t,30000))
        assertEquals(t.title.take(19),m.text(t.copy(playing=true),31000))
        assertEquals("",m.text(DashTrack(artist="Artist"),32000))
    }
    @Test fun nativeFieldContainsOnlySafeSingleByteCharactersAndNoNullSeparators() {
        val m=DashMusicMarquee()
        val t=DashTrack("  Canção\nboa\u0000 ","Música 🎵",true)
        val text=m.text(t,1000)
        assertEquals("Canção boa - Música",text)
        for(now in 1000L..40000L step 650) {
            val frame=m.text(t,now)
            assertEquals(19,frame.length)
            assertTrue(frame.all { it.code in 32..255 })
        }
    }
}
