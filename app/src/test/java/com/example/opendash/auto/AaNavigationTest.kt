package com.example.opendash.auto

import com.google.protobuf.CodedOutputStream
import com.example.opendash.dash.DashSession
import com.example.opendash.dash.protocol.DashCommands
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AaNavigationTest {
    @Test fun currentStreetIsSeparateFromNextTurnAndClearsWithoutNavigation() {
        val parser=AaNavigationStatus()
        parser.receive(0x8003,proto(1 to 1))
        val turn=nested(1 to "Next street".toByteArray())+proto(2 to 1,3 to 4)
        assertEquals("Android Auto",parser.receive(0x8004,turn)!!.panelTitle)
        val current=nested(3 to nested(1 to "Rua Atual".toByteArray()),1 to nested(1 to proto(1 to 200)))
        val nav=parser.receive(0x8007,current)!!
        assertEquals("Rua Atual",nav.panelTitle); assertEquals(200,nav.distanceMeters)
        assertNull(parser.receive(0x8007,current))
        assertEquals("Android Auto",parser.receive(0x8007,nested(1 to nested(1 to proto(1 to 180))))!!.panelTitle)
        parser.receive(0x8007,current)
        assertEquals("Android Auto",parser.receive(0x8003,proto(1 to 2))!!.panelTitle)
    }
    @Test fun explicitDisplayUnitsCanSupplyMissingRawMetresWithoutParsingInstructionText() {
        assertEquals(250,AaNavigationStatus.displayMeters("250",1))
        assertEquals(1500,AaNavigationStatus.displayMeters("1,5",3))
        assertEquals(305,AaNavigationStatus.displayMeters("1000",6))
        assertNull(AaNavigationStatus.displayMeters("Vire em 300 m",1))
        assertNull(AaNavigationStatus.displayMeters("1",0))
        val p=AaNavigationStatus(); p.receive(0x8003,proto(1 to 1))
        val display=nested(2 to "1.5".toByteArray())+proto(3 to 3)
        assertEquals(1500,p.receive(0x8007,nested(1 to nested(1 to display)))!!.distanceMeters)
    }
    @Test fun modernFirstStepAndDestinationStaySeparateAndRepeatedTurnsKeepDistance() {
        val p=AaNavigationStatus(); p.receive(0x8003,proto(1 to 1))
        val left=nested(1 to nested(1 to proto(1 to 7)),1 to nested(1 to proto(1 to 8)))
        assertEquals(AaNavigation(true,0x14),p.receive(0x8006,left))
        assertEquals(AaNavigation(true,0x14,320,23000),p.receive(0x8007,
            nested(1 to nested(1 to proto(1 to 320)),2 to nested(1 to proto(1 to 23000)))))
        assertNull(p.receive(0x8006,left)) // Repeated description must not flash zero metres.
        assertEquals(AaNavigation(true,0x15,null,23000),p.receive(0x8006,nested(1 to nested(1 to proto(1 to 8)))))
        assertEquals(0 to 0,AaNavigationStatus.destinationDistance(null,true))
        assertEquals(0 to 0x30,AaNavigationStatus.destinationDistance(null,false))
        assertEquals(230 to 0x10,AaNavigationStatus.destinationDistance(23000,true))
    }
    @Test fun repeatedLegacyTurnDoesNotEraseDistanceButDifferentTurnDoes() {
        val p=AaNavigationStatus(); p.receive(0x8003,proto(1 to 1))
        val turn=proto(2 to 1,3 to 4)
        p.receive(0x8004,turn); p.receive(0x8005,proto(1 to 250))
        assertNull(p.receive(0x8004,turn))
        assertEquals(AaNavigation(true,0x15),p.receive(0x8004,proto(2 to 2,3 to 4)))
    }
    private fun nested(vararg fields:Pair<Int,ByteArray>):ByteArray {
        val bytes=java.io.ByteArrayOutputStream(); val out=CodedOutputStream.newInstance(bytes)
        fields.forEach { out.writeByteArray(it.first,it.second) }; out.flush(); return bytes.toByteArray()
    }
    @Test fun destinationDistanceComesFromDestinationNotTheNextTurnAndClearsWhenUnavailable() {
        val p=AaNavigationStatus(); p.receive(0x8003,proto(1 to 1))
        val current=nested(1 to nested(1 to proto(1 to 200)),2 to nested(1 to proto(1 to 18500)))
        assertEquals(AaNavigation(true,255,200,18500),p.receive(0x8007,current))
        // A next-turn-only legacy update must not overwrite the destination distance.
        assertEquals(AaNavigation(true,255,150,18500),p.receive(0x8005,proto(1 to 150)))
        assertEquals(AaNavigation(true,255,100,null),p.receive(0x8007,nested(1 to nested(1 to proto(1 to 100)))))
        p.receive(0x8007,current)
        assertEquals(AaNavigation(),p.receive(0x8003,proto(1 to 2)))
        assertNull(p.receive(0x8007,current))
    }
    @Test fun missingNegativeAndMalformedDestinationValuesNeverBecomeRealDistances() {
        val p=AaNavigationStatus(); p.receive(0x8003,proto(1 to 1))
        val first=nested(1 to proto(1 to 900))
        val later=nested(1 to proto(1 to 19000))
        assertEquals(900,p.receive(0x8007,nested(2 to first,2 to later))!!.destinationMeters)
        // Negative int32 is not an unsigned huge distance; display text is not parsed.
        assertNull(p.receive(0x8007,nested(2 to nested(1 to proto(1 to -1))))!!.destinationMeters)
        assertNull(p.receive(0x8007,nested(2 to nested(1 to nested(2 to "18 km".toByteArray())))))
        p.receive(0x8007,nested(2 to first))
        try { p.receive(0x8007,byteArrayOf(0x12,0x05,0x0a)); fail("Malformed packet accepted") } catch(_:com.google.protobuf.InvalidProtocolBufferException) {}
        assertNull(p.receive(0x8007,nested(2 to first))) // Failed decode did not mutate current state.
        assertEquals(AaNavigation(true,0x1c),p.receive(0x8003,proto(1 to 3)))
    }
    @Test fun destinationFormattingHasZeroMetresAndKilometresWithoutOverflow() {
        assertEquals(0 to 0x30,AaNavigationStatus.destinationDistance(null))
        assertEquals(0 to 0x30,AaNavigationStatus.destinationDistance(-1))
        assertEquals(0 to 0x30,AaNavigationStatus.destinationDistance(0))
        assertEquals(999 to 0x30,AaNavigationStatus.destinationDistance(999))
        assertEquals(10 to 0x10,AaNavigationStatus.destinationDistance(1000))
        assertEquals(185 to 0x10,AaNavigationStatus.destinationDistance(18500))
        assertEquals(65535 to 0x10,AaNavigationStatus.destinationDistance(Int.MAX_VALUE))
    }
    private fun proto(vararg values:Pair<Int,Int>):ByteArray {
        val bytes=java.io.ByteArrayOutputStream(); val out=CodedOutputStream.newInstance(bytes)
        values.forEach { out.writeUInt32(it.first,it.second) }; out.flush(); return bytes.toByteArray()
    }
    @Test fun routeStartsTurnsUpdatesDistanceReroutesAndStopsWithoutStaleInstructions() {
        val p=AaNavigationStatus()
        assertNull(p.receive(0x8004,proto(2 to 1,3 to 4)))
        assertEquals(AaNavigation(active=true),p.receive(0x8003,proto(1 to 1)))
        assertEquals(AaNavigation(true,0x14),p.receive(0x8004,proto(2 to 1,3 to 4)))
        assertEquals(AaNavigation(true,0x14,250),p.receive(0x8005,proto(1 to 250)))
        assertNull(p.receive(0x8003,proto(1 to 1)))
        assertEquals(AaNavigation(true,0x15),p.receive(0x8004,proto(2 to 2,3 to 4)))
        assertEquals(AaNavigation(true,0x1C),p.receive(0x8003,proto(1 to 3)))
        assertEquals(AaNavigation(),p.receive(0x8003,proto(1 to 2)))
        assertNull(p.receive(0x8005,proto(1 to 20)))
    }
    @Test fun unsupportedManeuversNeverInventDirectionsAndLargeDistancesDoNotWrap() {
        assertEquals(0xFF,AaNavigationStatus.glyph(99,1))
        assertEquals(0xFF,AaNavigationStatus.glyph(4,0))
        assertEquals(0x14,AaNavigationStatus.glyph(4,1))
        assertEquals(0x3D,AaNavigationStatus.glyph(6,1))
        assertEquals(0x1A,AaNavigationStatus.glyph(6,2))
        assertEquals(0x0C,AaNavigationStatus.glyph(13,1,2))
        assertEquals(0x33,AaNavigationStatus.glyph(13,2,2))
        assertEquals(0xFF,AaNavigationStatus.glyph(13,1,20))
        assertEquals(0 to 0,AaNavigationStatus.distance(null))
        assertEquals(120 to 0x30,AaNavigationStatus.distance(120))
        assertEquals(700 to 0x10,AaNavigationStatus.distance(70000))
    }
    @Test fun hiddenModePreservesRoutePacketAndBothGlyphsStayHiddenUntilEnabled() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dash=DashSession(scope); dash.projectionLabel="Android Auto"
        val method=DashSession::class.java.getDeclaredMethod("liveRouteCard",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        fun field(packet:ByteArray,sub:Int):Int {
            var i=17
            while(i+4<=packet.size) {
                val len=((packet[i+2].toInt() and 255) shl 8)+(packet[i+3].toInt() and 255)
                if(packet[i].toInt()==5 && packet[i+1].toInt()==sub) return (0 until len).fold(0) { v,j -> (v shl 8)+(packet[i+4+j].toInt() and 255) }
                i+=4+len
            }
            error("Missing field")
        }
        try {
            var idle=method.invoke(dash,true) as ByteArray
            assertEquals(0,field(idle,9)); assertEquals(0x30,field(idle,0x46))
            dash.setProjectionGuidance(true,0x14,250,0x30); dash.cleanProjection=true
            var packet=method.invoke(dash,true) as ByteArray
            assertEquals(255,field(packet,2)); assertEquals(255,field(packet,3)); assertEquals(0,field(packet,4))
            dash.cleanProjection=false
            packet=method.invoke(dash,true) as ByteArray
            assertEquals(0x14,field(packet,2)); assertEquals(250,field(packet,4))
            dash.setProjectionGuidance(true,0x14,250,0x30,185,0x10)
            dash.cleanProjection=true
            packet=method.invoke(dash,true) as ByteArray
            assertEquals(255,field(packet,2)); assertEquals(185,field(packet,9)); assertEquals(0x10,field(packet,0x46))
            dash.setProjectionGuidance(false,255,0,0)
            packet=method.invoke(dash,true) as ByteArray
            assertEquals(255,field(packet,2)); assertEquals(0,field(packet,4))
            assertEquals(0,field(packet,9)); assertEquals(0x30,field(packet,0x46))
        } finally { scope.cancel() }
    }
}
