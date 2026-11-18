package com.example.opendash.auto
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
class AaMediaStatusTest {
    private fun metadata(title:String,artist:String):ByteArray {
        val out=ByteArrayOutputStream(); val p=CodedOutputStream.newInstance(out)
        p.writeString(1,title); p.writeString(2,artist); p.writeString(3,"Album"); p.writeInt32(9,123); p.flush()
        return out.toByteArray()
    }
    @Test fun trackAndPlaybackStateArriveIndependentlyAndDuplicatesDoNotNotify() {
        val m=AaMediaStatus()
        assertEquals(AaNowPlaying("Canção","Artista","Album"),m.receive(0x8003,metadata("Canção","Artista")))
        assertNull(m.receive(0x8003,metadata("Canção","Artista")))
        assertEquals(true,m.receive(0x8001,byteArrayOf(8,2))!!.playing)
        assertNull(m.receive(0x8001,byteArrayOf(8,1)))
        assertEquals(AaNowPlaying("Canção","Artista","Album",false),m.receive(0x8001,byteArrayOf(8,3)))
        assertEquals(AaNowPlaying(playing=false),m.receive(0x8001,byteArrayOf(8,0)))
    }
    @Test fun malformedMetadataDoesNotOverwriteLastGoodTrack() {
        val m=AaMediaStatus(); m.receive(0x8003,metadata("Track","Singer"))
        assertTrue(runCatching { m.receive(0x8003,byteArrayOf(10,100,65)) }.isFailure)
        assertEquals("Track",m.receive(0x8001,byteArrayOf(8,2))!!.title)
        assertNull(m.receive(0x8004,byteArrayOf(8,0)))
    }
    @Test fun metadataServiceDoesNotDivertPhoneAudio() {
        val services=AaServices.discovery(phoneAudio=true).servicesList
        assertTrue(services.single { it.id==10 }.hasMediaPlaybackService())
        assertFalse(services.single { it.id==10 }.hasMediaSinkService())
        assertFalse(services.any { it.id==6 || it.id==4 || it.id==7 })
    }
}
