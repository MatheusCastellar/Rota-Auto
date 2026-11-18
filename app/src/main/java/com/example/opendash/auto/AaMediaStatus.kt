package com.example.opendash.auto

import com.google.protobuf.CodedInputStream

data class AaNowPlaying(val title:String="",val artist:String="",val album:String="",val playing:Boolean?=null)

/** AA media status channel: metadata 0x8003 (title/artist/album), state 0x8001. */
internal class AaMediaStatus {
    private var current=AaNowPlaying()
    fun receive(type:Int,data:ByteArray):AaNowPlaying? {
        if(type !in listOf(0x8001,0x8003) || data.size>1_048_576) return null
        val input=CodedInputStream.newInstance(data)
        var title=""; var artist=""; var album=""; var state:Int?=null
        while(!input.isAtEnd) {
            val tag=input.readTag()
            when {
                type==0x8003 && tag==10 -> title=input.readStringRequireUtf8().take(512)
                type==0x8003 && tag==18 -> artist=input.readStringRequireUtf8().take(512)
                type==0x8003 && tag==26 -> album=input.readStringRequireUtf8().take(512)
                type==0x8001 && tag==8 -> state=input.readEnum()
                !input.skipField(tag) -> break
            }
        }
        val next=if(type==0x8003) current.copy(title=title,artist=artist,album=album) else when(state) {
            0 -> AaNowPlaying(playing=false)
            2 -> current.copy(playing=true)
            3 -> current.copy(playing=false)
            else -> current // Changing/unknown states do not erase a track in transit.
        }
        if(next==current) return null
        current=next
        return next
    }
}
