package com.example.opendash.auto

import com.google.protobuf.CodedInputStream
import com.example.opendash.dash.protocol.DashCommands

data class AaNavigation(val active:Boolean=false,val glyph:Int=0xFF,val distanceMeters:Int?=null,val destinationMeters:Int?=null,val currentRoad:String?=null) {
    val panelTitle:String get()=currentRoad?.takeIf { active && it.isNotBlank() } ?: "Android Auto"
}

/** Decodes AA navigation status/turn/distance metadata; never reads or modifies GPS. */
internal class AaNavigationStatus {
    private var current=AaNavigation()
    private var lastTurn:ByteArray?=null
    fun receive(type:Int,data:ByteArray):AaNavigation? {
        if(type !in 0x8003..0x8007 || data.size>262144) return null
        val input=CodedInputStream.newInstance(data)
        var status:Int?=null; var direction=0; var maneuver=0; var exit=0; var meters:Int?=null
        var destinationMeters:Int?=null; var destinationSeen=false
        var road:String?=null
        var firstStep:ByteArray?=null
        while(!input.isAtEnd) {
            val tag=input.readTag()
            when {
                type==0x8003 && tag==8 -> status=input.readEnum()
                type==0x8004 && tag==16 -> direction=input.readEnum()
                type==0x8004 && tag==24 -> maneuver=input.readEnum()
                type==0x8004 && tag==40 -> exit=input.readUInt32().coerceAtLeast(0)
                type==0x8005 && tag==8 -> meters=input.readUInt32().takeIf { it>=0 }
                type==0x8006 && tag==10 -> {
                    val step=input.readByteArray()
                    if(firstStep==null) firstStep=step
                }
                type==0x8007 && tag==10 -> meters=wrappedDistance(input.readByteArray())
                type==0x8007 && tag==18 -> {
                    val destination=input.readByteArray()
                    // The first destination is the current target (including a waypoint).
                    if(!destinationSeen) destinationMeters=wrappedDistance(destination)
                    destinationSeen=true
                }
                // NavigationCurrentPosition.currentRoad -> NavigationText.text.
                // Legacy turn.road_name and step road_info describe the NEXT road.
                type==0x8007 && tag==26 -> road=roadText(input.readByteArray())
                !input.skipField(tag) -> break
            }
        }
        val next=when(type) {
            0x8003 -> when(status) {
                0,2 -> AaNavigation()
                1 -> if(current.active) current else AaNavigation(active=true)
                3 -> AaNavigation(active=true,glyph=0x1C)
                else -> current
            }
            0x8004 -> if(current.active) current.copy(glyph=glyph(maneuver,direction,exit),
                distanceMeters=if(lastTurn?.contentEquals(data)==true) current.distanceMeters else null) else current
            0x8006 -> if(current.active) current.copy(glyph=firstStep?.let(::stepGlyph) ?: 0xff,
                distanceMeters=if(firstStep!=null && lastTurn?.contentEquals(firstStep)==true) current.distanceMeters else null) else current
            0x8007 -> if(current.active) current.copy(distanceMeters=meters,destinationMeters=destinationMeters,currentRoad=road) else current
            else -> if(current.active) current.copy(distanceMeters=meters) else current
        }
        if(type==0x8004 && current.active) lastTurn=data.copyOf()
        if(type==0x8006 && current.active) lastTurn=firstStep
        if(type==0x8003 && status!=1) lastTurn=null
        if(next==current) return null
        current=next
        return next
    }
    private fun roadText(data:ByteArray):String? {
        val text=CodedInputStream.newInstance(data)
        while(!text.isAtEnd) {
            val tag=text.readTag()
            if(tag==10) return text.readStringRequireUtf8().replace(Regex("[\\p{Cc}\\p{Cf}]")," ")
                .replace(Regex("\\s+")," ").trim().take(120).takeIf { it.isNotEmpty() }
            if(!text.skipField(tag)) break
        }
        return null
    }
    private fun stepGlyph(data:ByteArray):Int {
        val step=CodedInputStream.newInstance(data)
        while(!step.isAtEnd) {
            val tag=step.readTag()
            if(tag==10) {
                val maneuver=CodedInputStream.newInstance(step.readByteArray())
                var type=0; var exit=0
                while(!maneuver.isAtEnd) {
                    val field=maneuver.readTag()
                    when(field) {
                        8 -> type=maneuver.readEnum()
                        16 -> exit=maneuver.readInt32()
                        else -> if(!maneuver.skipField(field)) break
                    }
                }
                return modernGlyph(type,exit)
            } else if(!step.skipField(tag)) break
        }
        return 0xff
    }
    /** 0x8007: step/destination wrapper field 1 -> Distance field 1 (raw metres).
     * Display strings, units and ETA are deliberately not interpreted as metres. */
    private fun wrappedDistance(data:ByteArray):Int? {
        val wrapper=CodedInputStream.newInstance(data)
        var meters:Int?=null
        while(!wrapper.isAtEnd) {
            val tag=wrapper.readTag()
            if(tag==10) {
                val distance=CodedInputStream.newInstance(wrapper.readByteArray())
                var displayed:String?=null; var unit=0
                while(!distance.isAtEnd) {
                    val inner=distance.readTag()
                    if(inner==8) meters=distance.readInt32().takeIf { it>=0 }
                    else if(inner==18) displayed=distance.readString()
                    else if(inner==24) unit=distance.readEnum()
                    else if(!distance.skipField(inner)) break
                }
                // AA may omit raw metres and supply a numeric display value + explicit unit.
                if(meters==null) meters=displayMeters(displayed,unit)
            } else if(!wrapper.skipField(tag)) break
        }
        return meters
    }
    companion object {
        fun displayMeters(text:String?,unit:Int):Int? {
            val value=text?.trim()?.takeIf { it.matches(Regex("[0-9]{1,9}([.,][0-9]{1,3})?")) }
                ?.replace(',','.')?.toDoubleOrNull() ?: return null
            val factor=when(unit) { 1->1.0; 2,3->1000.0; 4,5->1609.344; 6->0.3048; 7->0.9144; else->return null }
            val meters=value*factor
            return if(meters in 0.0..Int.MAX_VALUE.toDouble()) kotlin.math.round(meters).toInt() else null
        }
        fun destinationDistance(meters:Int?,active:Boolean=false):Pair<Int,Int> = when {
            meters==null || meters<0 -> 0 to if(active) 0 else DashCommands.NAV_UNIT_METERS
            meters<1000 -> meters to DashCommands.NAV_UNIT_METERS
            else -> (meters/100).coerceAtMost(65535) to DashCommands.NAV_UNIT_KM_TENTHS
        }
        fun modernGlyph(type:Int,exit:Int=0):Int = when(type) {
            1,2,36 -> 0x09
            3,25 -> glyph(9,1); 4,26 -> glyph(9,2)
            5 -> glyph(3,1); 6 -> glyph(3,2)
            7 -> glyph(4,1); 8 -> glyph(4,2)
            9 -> glyph(5,1); 10 -> glyph(5,2)
            11,19 -> glyph(6,1); 12,20 -> glyph(6,2)
            13,15,17 -> glyph(7,1); 14,16,18 -> glyph(7,2)
            21,23 -> glyph(8,1); 22,24 -> glyph(8,2)
            27 -> glyph(10,1); 28 -> glyph(10,2)
            32,33,43,44 -> glyph(13,2,exit)
            34,35,45,46 -> glyph(13,1,exit)
            37,47,48 -> 0x3e
            38,49,50 -> 0x3f
            39,40,41,42 -> 0x3c
            else -> 0xff
        }
        // Protocol values, independently mapped against TripperDashPlusPlus's glyph catalog.
        fun glyph(type:Int,direction:Int,exit:Int=0):Int {
            fun side(left:Int,right:Int)=when(direction) { 1->left; 2->right; else->0xFF }
            return when(type) {
                1,2,14 -> 0x09
                3 -> side(0x18,0x19)
                4 -> side(0x14,0x15)
                5 -> side(0x16,0x17)
                6 -> side(0x3D,0x1A)
                7,10 -> side(0x03,0x04)
                8 -> side(0x28,0x27)
                9 -> side(0x06,0x05)
                11,12,13 -> if(direction !in 1..2 || exit !in 0..19) 0xFF else if(direction==1) {
                    if(exit<=9) 0x0A+exit else 0x50+exit-10
                } else { if(exit<=9) 0x31+exit else 0x46+exit-10 }
                16 -> 0x3E
                17 -> 0x3F
                19 -> 0x3C
                else -> 0xFF
            }
        }
        fun distance(meters:Int?):Pair<Int,Int> = when {
            meters==null || meters<0 -> 0 to 0
            meters<=65535 -> meters to DashCommands.NAV_UNIT_METERS
            else -> (meters/100).coerceAtMost(65535) to DashCommands.NAV_UNIT_KM_TENTHS
        }
    }
}
