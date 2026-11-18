package com.example.opendash.dash

import com.example.opendash.dash.protocol.DashCommands

/** Immutable snapshot: do not mix fields from two commands on the sending thread. */
data class DashMediaCard(val title:String,val album:String,val artist:String) {
    companion object {
        // Official APK's A1 fallback puts NowPlaying in the FIRST wire field.
        // WayFindR also observed this field on the compact navigation/media strip.
        // Preserve album/title/artist wire order; only this synthetic label uses album.
        fun panelLabel(label:String)=DashMediaCard("",label,"")
    }
}

/** Session-local state. Activation is a transition, not a 1 Hz keep-alive. */
class DashMediaUpdates {
    private var previous:DashMediaCard?=null
    private var lastMetadataAt=0L
    private var previousPlaying:Boolean?=null
    fun next(card:DashMediaCard?,now:Long,playing:Boolean=false):List<ByteArray> {
        val packets=mutableListOf<ByteArray>()
        if(card==null) {
            if(previous!=null) packets+=DashCommands.musicActive(false)
            previousPlaying=null
        } else {
            if(previous==null) {
                packets+=DashCommands.musicActive(true)
            }
            if(playing!=previousPlaying) {
                packets+=DashCommands.musicPlaying(playing)
                previousPlaying=playing
            }
            if(card!=previous || now-lastMetadataAt>=5_000) {
                packets+=DashCommands.nowPlaying(card.title,card.album,card.artist)
                lastMetadataAt=now
            }
        }
        previous=card
        return packets
    }
}
