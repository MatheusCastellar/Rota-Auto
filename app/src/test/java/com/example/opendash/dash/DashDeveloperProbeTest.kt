package com.example.opendash.dash

import kotlinx.coroutines.*
import com.example.opendash.dash.protocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DashDeveloperProbeTest {
    @Test fun enabledBubbleStaysVisibleBeforeDuringAndAfterNavigationIncludingUnknownManeuvers() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope)
        val card=session.javaClass.getDeclaredMethod("liveRouteCard",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        fun glyph():Int {
            val packet=card.invoke(session,true) as ByteArray
            return K1GPacket.parseIncoming(packet.copyOfRange(0,8)+packet.copyOfRange(17,packet.size))
                .single { it.type==5 && it.sub==2 }.value.single().toInt() and 255
        }
        try {
            session.cleanProjection=false
            assertEquals(DashCommands.NAV_MANEUVER_NEUTRAL,glyph())
            session.setProjectionGuidance(true,0x15,300,0x30)
            assertEquals(0x15,glyph())
            for(unknown in listOf(-1,0x5a,0xff,256)) {
                session.setProjectionGuidance(true,unknown,300,0x30)
                assertEquals(DashCommands.NAV_MANEUVER_NEUTRAL,glyph())
            }
            session.setProjectionGuidance(false,0xff,0,0x30)
            assertEquals(DashCommands.NAV_MANEUVER_NEUTRAL,glyph())
            session.cleanProjection=true; assertEquals(255,glyph())
            session.cleanProjection=false; assertEquals(DashCommands.NAV_MANEUVER_NEUTRAL,glyph())
            session.testManeuver=255; assertEquals(255,glyph()) // Explicit developer experiment only.
            session.testManeuver=null; assertEquals(DashCommands.NAV_MANEUVER_NEUTRAL,glyph())
        } finally { scope.cancel() }
    }
    @Test fun destinationDistanceNeverWritesMediaFieldsOrChangesPlayingAndMarquee() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope)
        val route=session.javaClass.getDeclaredMethod("liveRouteCard",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        val media=session.javaClass.getDeclaredField("mediaCard").apply { isAccessible=true }
        fun segments(packet:ByteArray):List<Tlv> = K1GPacket.parseIncoming(
            packet.copyOfRange(0,8)+packet.copyOfRange(17,packet.size))
        try {
            session.updateNowPlaying("Android Auto","","The Way I Are - Timbaland")
            session.musicPlaying=true
            val original=media.get(session) as DashMediaCard
            val updates=DashMediaUpdates()
            val initialMedia=updates.next(original,0,true)
            val mediaKeys=initialMedia.flatMap(::segments).map { it.type to it.sub }.toSet()
            assertTrue(mediaKeys.containsAll(setOf(5 to 0x0d,5 to 0x17,5 to 0x19)))
            for(hidden in listOf(false,true)) for(active in listOf(false,true,false)) {
                session.cleanProjection=hidden
                session.setProjectionGuidance(active,9,300,0x30,185,0x10)
                val fields=segments(route.invoke(session,true) as ByteArray)
                assertTrue(fields.none { (it.type to it.sub) in mediaKeys })
                assertArrayEquals(byteArrayOf(0,if(active) 185.toByte() else 0),fields.single { it.type==5 && it.sub==9 }.value)
                assertArrayEquals(byteArrayOf(if(active) 0x10 else 0x30),fields.single { it.type==5 && it.sub==0x46 }.value)
                assertEquals(original,media.get(session)); assertTrue(session.musicPlaying)
                assertTrue(updates.next(media.get(session) as DashMediaCard,250,true).isEmpty())
            }
        } finally { scope.cancel() }
    }
    private class Transport:DashTransport {
        override val receiveSource="192.168.1.1:2345 -> UDP:2002"
        val sent=mutableListOf<ByteArray>()
        override fun send(data:ByteArray) { sent.add(data) }
        override fun sendRtp(data:ByteArray) {}
        override suspend fun receive():ByteArray?=null
        override fun close() {}
    }
    @Test fun capturesEveryDatagramBeforeParsingIncludingUnknownMalformedAndFrameAcknowledgements() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope); val transport=Transport()
        session.javaClass.getDeclaredField("socket").apply { isAccessible=true }.set(session,transport)
        val incoming=listOf(byteArrayOf(),byteArrayOf(1,2,3),
            byteArrayOf(0,13,0,1,0,0,0,0,9,6,0,1,0x55),
            byteArrayOf(0,14,0,1,0,0,0,0,0x7f,0x7e,0,2,0x12,0x34))
        val captured=mutableListOf<ByteArray>()
        session.onRawPacket={ bytes,source ->
            assertEquals(transport.receiveSource,source)
            captured.add(bytes.copyOf())
        }
        val dispatch=session.javaClass.getDeclaredMethod("dispatchIncoming",ByteArray::class.java,DashTransport::class.java).apply { isAccessible=true }
        try {
            incoming.forEach { dispatch.invoke(session,it,transport) }
            assertEquals(incoming.size,captured.size)
            incoming.indices.forEach { assertArrayEquals(incoming[it],captured[it]) }
            assertEquals(1,transport.sent.size) // Required video ACK still sent.
        } finally { scope.cancel() }
    }
    @Test fun allGlyphsOverrideHiddenPreferenceAndLiveGuidanceAndRestoreWithoutChangingIt() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope)
        val card=session.javaClass.getDeclaredMethod("liveRouteCard",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        fun maneuver():Int {
            val data=card.invoke(session,true) as ByteArray
            var offset=17
            while(offset+4<=data.size) {
                val length=((data[offset+2].toInt() and 255) shl 8) or (data[offset+3].toInt() and 255)
                if(data[offset]==5.toByte() && data[offset+1]==2.toByte()) return data[offset+4].toInt() and 255
                offset+=4+length
            }
            error("Missing primary maneuver")
        }
        try {
            session.cleanProjection=true
            for(code in 0..255) { session.testManeuver=code; assertEquals(code,maneuver()) }
            session.testManeuver=0x2b
            session.setProjectionGuidance(true,0x15,300,0x30)
            assertEquals(0x2b,maneuver())
            session.testManeuver=null; assertEquals(255,maneuver()); assertTrue(session.cleanProjection)
            session.cleanProjection=false; assertEquals(0x15,maneuver())
            try { session.testManeuver=256; fail("Out of range accepted") } catch(_:IllegalArgumentException) {}
        } finally { scope.cancel() }
    }
}
