package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class AaProtocolTest {
    @Test fun optionalBarCropFitsMapWithoutLosingSideEdgesOrChangingPhoneTouch() {
        val r=AaGeometry.viewport(526,300,true)
        assertEquals(526f,r.width,.01f)
        assertEquals(263f,r.height,.01f)
        assertEquals(18.5f,r.top,.01f)
        assertEquals(400 to 240,AaGeometry.touch(263f,150f,526,300))
    }
    @Test fun mediaMetadataCanEncodeSingleByteCharactersInArtistField() {
        val text=com.example.opendash.dash.protocol.DashCommands.nowPlaying("","","Próxima faixa")
        assertArrayEquals(byteArrayOf(0,0)+"Próxima faixa".toByteArray(Charsets.ISO_8859_1),text.copyOfRange(21,text.size))
    }
    @Test fun phoneAudioKeepsSystemAndVideoWithoutDivertingSpeechMusicOrMicrophone() {
        val d=AaServices.discovery(phoneAudio=true)
        assertEquals(setOf(1,2,3,5,10,11),d.servicesList.map { it.id }.toSet())
        assertEquals(3,d.headunitInfo.vehicleType)
        assertEquals(Media.AudioStreamType.SYSTEM,d.servicesList.single { it.id==5 }.mediaSinkService.audioType)
        assertTrue(d.servicesList.single { it.id==3 }.inputSourceService.keycodesSupportedList.contains(84))
        assertTrue(AaServices.discovery(phoneAudio=true,microphone=true).servicesList.any { it.id==7 })
    }
    @Test fun microphoneOnlyAdvertisedWhenCaptureIsAvailable() {
        assertFalse(AaServices.discovery().servicesList.any { it.hasMediaSourceService() })
        val d=AaServices.discovery(microphone=true)
        val mic=d.servicesList.single { it.id==7 }.mediaSourceService
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM,mic.type)
        assertEquals(16000,mic.audioConfig.sampleRate)
        assertEquals(16,mic.audioConfig.numberOfBits); assertEquals(1,mic.audioConfig.numberOfChannels)
        assertFalse(mic.availableWhileInCall)
        assertTrue(d.servicesList.single { it.id==3 }.inputSourceService.keycodesSupportedList.containsAll(listOf(4,84)))
    }
    @Test fun musicStaysOnThePhoneWhileSpeechAndMicrophoneRemainAvailable() {
        val d=AaServices.discovery(microphone=true)
        val audio=d.servicesList.filter { it.hasMediaSinkService() && it.mediaSinkService.availableType==Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM }
        assertEquals(setOf(Media.AudioStreamType.SPEECH,Media.AudioStreamType.SYSTEM),audio.map { it.mediaSinkService.audioType }.toSet())
        assertFalse(d.servicesList.any { it.id==6 })
        assertTrue(d.servicesList.any { it.hasMediaSourceService() })
    }
    @Test fun nativeLabelDoesNotOpenNavigationOrAddAnInstruction() {
        val label=com.example.opendash.dash.protocol.DashCommands.projectionLabel("Android Auto")
        // Outbound K1G header is 17 bytes. Exactly one TLV: 05 01 + NUL-terminated title.
        val value="Android Auto\u0000".toByteArray()
        assertEquals(17+4+value.size,label.size)
        assertEquals(2,label[3].toInt())
        assertArrayEquals(byteArrayOf(5,1,0,value.size.toByte())+value,label.copyOfRange(17,label.size))
        val media=com.example.opendash.dash.protocol.DashCommands.nowPlaying("Controle","","")
        assertEquals(13,media[18].toInt())
        assertArrayEquals("\u0000Controle\u0000".toByteArray(),media.copyOfRange(21,media.size))
    }
    @Test fun genericMetadataRetainsOfficialAlbumTitleArtistWireOrder() {
        val gate=com.example.opendash.dash.protocol.DashCommands.musicActive(true)
        assertArrayEquals(byteArrayOf(5,0x17,0,1,0x55),gate.copyOfRange(17,gate.size))
        val off=com.example.opendash.dash.protocol.DashCommands.musicActive(false)
        assertArrayEquals(byteArrayOf(5,0x17,0,1,0xAA.toByte()),off.copyOfRange(17,off.size))
        for(label in listOf("Intercom","Controle")) {
            val text=com.example.opendash.dash.protocol.DashCommands.nowPlaying(label,"","")
            assertArrayEquals("\u0000$label\u0000".toByteArray(),text.copyOfRange(21,text.size))
        }
        val metadata=com.example.opendash.dash.protocol.DashCommands.nowPlaying("Faixa","Album","Artista")
        assertArrayEquals("Album\u0000Faixa\u0000Artista".toByteArray(),metadata.copyOfRange(21,metadata.size))
    }
    @Test fun readsPartialTcpChunksAndRejectsTruncation() {
        val out=ByteArrayOutputStream()
        AaWire.write(out,2,9,byteArrayOf(1,2,3),900)
        val bytes=out.toByteArray()
        val slow=object:ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(1,len))
        }
        val frame=AaWire.read(slow)
        assertEquals(2,frame.channel); assertEquals(900,frame.total)
        assertArrayEquals(byteArrayOf(1,2,3),frame.payload)
        assertThrows(EOFException::class.java) { AaWire.read(ByteArrayInputStream(bytes.copyOf(bytes.size-1))) }
    }
    @Test fun reassemblesInterleavedChannelsAndRejectsWrongSize() {
        val a=AaWire.Assembler()
        assertNull(a.accept(AaWire.Frame(2,9,6,byteArrayOf()),byteArrayOf(0,0,1)))
        assertArrayEquals(byteArrayOf(0,12),a.accept(AaWire.Frame(0,11,0,byteArrayOf()),byteArrayOf(0,12)))
        assertArrayEquals(byteArrayOf(0,0,1,2,3,4),a.accept(AaWire.Frame(2,10,0,byteArrayOf()),byteArrayOf(2,3,4)))
        assertThrows(IllegalStateException::class.java) { a.accept(AaWire.Frame(2,10,0,byteArrayOf()),byteArrayOf(1)) }
        val b=AaWire.Assembler()
        b.accept(AaWire.Frame(2,9,4,byteArrayOf()),byteArrayOf(1,2,3))
        assertThrows(IllegalArgumentException::class.java) { b.accept(AaWire.Frame(2,10,0,byteArrayOf()),byteArrayOf(4,5)) }
    }
    @Test fun rejectsOversizedAndMalformedFrames() {
        val out=ByteArrayOutputStream()
        AaWire.write(out,2,1,byteArrayOf(1),AaWire.MAX_MESSAGE+1)
        assertThrows(IllegalArgumentException::class.java) { AaWire.read(ByteArrayInputStream(out.toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { AaWire.read(ByteArrayInputStream(byteArrayOf(1,0,0,0))) }
    }
    @Test fun keepsWholeMapAndMatchesPreviewTouches() {
        val r=AaGeometry.viewport(526,300)
        assertEquals(13f,r.left,0.001f); assertEquals(0f,r.top,0.001f)
        assertEquals(500f,r.width,0.001f); assertEquals(300f,r.height,0.001f)
        assertEquals(800f/480f,r.width/r.height,0.0001f)
        assertEquals(0 to 0,AaGeometry.touch(13f,0f,526,300))
        assertEquals(400 to 240,AaGeometry.touch(263f,150f,526,300))
        assertNull(AaGeometry.touch(12f,100f,526,300))
        assertNull(AaGeometry.touch(513f,100f,526,300))
        assertNull(AaGeometry.touch(100f,300f,526,300))
        val large=AaGeometry.viewport(1080,720)
        assertEquals(400 to 240,AaGeometry.touch(large.left+large.width/2,large.top+large.height/2,1080,720))
        assertNull(AaGeometry.touch(0f,0f,0,0))
    }
    @Test fun discoveryAnnouncesH264AndConsistentFullImageGeometry() {
        val d=Control.ServiceDiscoveryResponse.parseFrom(AaServices.discovery().toByteArray())
        val video=d.servicesList.single { it.id==2 }.mediaSinkService
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,video.availableType)
        val config=video.getVideoConfigs(0)
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,config.codecResolution)
        assertEquals(0,config.marginWidth); assertEquals(0,config.marginHeight)
        assertEquals(160,config.density)
        val touch=d.servicesList.single { it.id==3 }.inputSourceService.touchscreen
        assertEquals(AaGeometry.SOURCE_WIDTH,touch.width); assertEquals(AaGeometry.SOURCE_HEIGHT,touch.height)
        assertFalse(d.servicesList.any { it.hasMediaSourceService() }) // no fictitious microphone
        assertTrue(d.hasHeadunitInfo())
        assertEquals(3,d.headunitInfo.vehicleType) // microphone omission is valid for an explicit motorcycle
        assertEquals(d.vehicleId,d.headunitInfo.vehicleId)
        assertNotEquals("ROTA-AUTO-LOCAL",d.vehicleId) // do not reuse a cached implicit-car identity
    }
}
