package com.example.opendash

import com.example.opendash.dash.nav.*
import com.example.opendash.dash.protocol.*
import com.example.opendash.dash.video.*
import com.example.opendash.util.LocationParser
import org.junit.Assert.*
import org.junit.Test

class ProtocolAndNavigationTest {
    @Test fun packetLengthAndSequenceDoNotCorruptPayload() {
        val packet=K1GPacket.build(Tlv(7,1,byteArrayOf(1)))
        val size=((packet[0].toInt() and 255) shl 8) or (packet[1].toInt() and 255)
        assertEquals(packet.size,size)
        val patched=K1GPacket.patchSeq(packet,257)
        assertEquals(1,patched[16].toInt() and 255)
        assertArrayEquals(packet.copyOfRange(17,packet.size),patched.copyOfRange(17,patched.size))
    }
    @Test fun incomingFrameAckIsParsedAtShortHeaderOffset() {
        val packet=byteArrayOf(0,13,0,1,0,0,0,0,9,6,0,1,0x55)
        val tlv=K1GPacket.parseIncoming(packet).single()
        assertEquals(9,tlv.type); assertEquals(6,tlv.sub); assertArrayEquals(byteArrayOf(0x55),tlv.value)
    }
    @Test fun fragmentedRtpReassemblesOriginalNalAndMarksOnlyLastPacket() {
        val packets=mutableListOf<ByteArray>()
        val nal=ByteArray(5000) { (it%251).toByte() }.apply { this[0]=0x65 }
        RtpPacketizer(packets::add).packetize(nal,true,12345)
        assertTrue(packets.size>1)
        assertTrue(packets.all { it.size<=1392 })
        assertTrue(packets.dropLast(1).all { it[1].toInt() and 0x80==0 })
        assertEquals(0x80,packets.last()[1].toInt() and 0x80)
        val reconstructed=byteArrayOf(((packets[0][12].toInt() and 0xe0) or (packets[0][13].toInt() and 31)).toByte())+
            packets.flatMap { it.drop(14) }.toByteArray()
        assertArrayEquals(nal,reconstructed)
        assertEquals(0x80,packets.first()[13].toInt() and 0x80)
        assertEquals(0x40,packets.last()[13].toInt() and 0x40)
        packets.zipWithNext().forEach { (a,b) ->
            fun sequence(p:ByteArray)=((p[2].toInt() and 255) shl 8) or (p[3].toInt() and 255)
            assertEquals((sequence(a)+1) and 65535,sequence(b))
            assertArrayEquals(a.copyOfRange(4,8),b.copyOfRange(4,8))
        }
    }
    @Test fun idrContainsParameterSetsAndDropsSei() {
        val emitted=mutableListOf<ByteArray>()
        val processor=NalProcessor { b,_->emitted+=b }
        val sc=byteArrayOf(0,0,0,1)
        processor.process(sc+byteArrayOf(0x67,0x42,0xc0.toByte(),0x29)+sc+byteArrayOf(0x68,1)+sc+byteArrayOf(6,3)+sc+byteArrayOf(0x65,9))
        assertEquals(1,emitted.size)
        assertArrayEquals(byteArrayOf(0x67,0x42,0,0x29)+sc+byteArrayOf(0x68,1)+sc+byteArrayOf(0x65,9),emitted.single())
    }
    @Test fun farAwayFromRouteEndpointDoesNotAnnounceArrival() {
        val points=listOf(GeoPoint(-23.55,-46.64),GeoPoint(-23.55,-46.63))
        val length=GeoPoint.distMeters(points[0],points[1])
        val route=Route(points,emptyList(),length,60.0,doubleArrayOf(0.0,length))
        val far=NavEngine.progress(route,GeoPoint(-23.55,-46.60),10f)
        assertTrue(far.offRoute); assertFalse(far.arrived)
        assertTrue(NavEngine.progress(route,points.last(),0f).arrived)
    }
    @Test fun maliciousSharedHostsAreNotFollowed() {
        val parsed=LocationParser.parse("https://maps.google.com.evil.example/?q=-23.55,-46.63")
        assertNull(parsed.url); assertNull(parsed.lat)
        assertEquals(-23.55,LocationParser.parse("https://maps.google.com/?q=-23.55,-46.63").lat!!,0.00001)
    }
}
