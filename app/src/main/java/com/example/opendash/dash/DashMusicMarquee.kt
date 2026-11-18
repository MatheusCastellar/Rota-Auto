package com.example.opendash.dash

data class DashTrack(val title:String="",val artist:String="",val playing:Boolean?=null)

/** The native right-hand artist field is 19 single-byte characters, not video pixels. */
class DashMusicMarquee {
    private var previous=""
    private var startedAt=0L
    fun text(track:DashTrack,now:Long):String {
        fun clean(value:String)=value.replace(Regex("[\\p{Cntrl}\\s]+")," ").trim().take(256)
            .codePoints().toArray().joinToString("") { if(it in 32..255) it.toChar().toString() else "?" }
        val title=clean(track.title)
        val line=if(title.isBlank()) "" else listOf(title,clean(track.artist)).filter { it.isNotBlank() }.joinToString(" - ")
        if(line!=previous) { previous=line; startedAt=now }
        if(track.playing==false) { startedAt=now; return line.take(19) }
        if(line.length<=19) return line
        val loop=line+"   "
        val elapsed=(now-startedAt).coerceAtLeast(0)
        val cycle=1500L+loop.length*650L
        val phase=elapsed%cycle
        val offset=if(phase<1500) 0 else ((phase-1500)/650+1).toInt()%loop.length
        return (loop+loop).substring(offset,offset+19)
    }
}
