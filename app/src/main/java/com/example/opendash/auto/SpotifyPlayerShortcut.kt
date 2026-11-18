package com.example.opendash.auto

internal data class AaSourceFrame(val width:Int,val height:Int,val pixels:IntArray)

/** Conservative recognizer for our margin-free 800x480 / 160 dpi AA layout, sampled at half size.
 * Never scans the transformed panel image or the AA bottom app bar. Ambiguous images do nothing.
 */
internal object SpotifyPlayerButton {
    data class Point(val x:Int,val y:Int)
    fun find(frame:AaSourceFrame):Point? {
        if(frame.width!=400 || frame.height!=240 || frame.pixels.size!=400*240) return null
        fun green(x:Int,y:Int):Boolean {
            val c=frame.pixels[y*400+x]; val r=c ushr 16 and 255; val g=c ushr 8 and 255; val b=c and 255
            return g>145 && r<110 && b in 35..165 && g-r>80 && g-b>40
        }
        // Spotify's green brand icon is present on its browse header; player header is monochrome.
        val brand=(8..52).sumOf { x -> (8..52).count { y -> green(x,y) } } > 170 ||
            (348..392).sumOf { x -> (8..52).count { y -> green(x,y) } } > 170
        if(!brand) return null
        val candidates=mutableListOf<Point>()
        for(cx in listOf(26,374)) {
            val left=cx-24; val right=cx+24
            var minX=400; var minY=240; var maxX=0; var maxY=0; var count=0
            for(y in 140..196) for(x in left..right) if(green(x,y)) {
                minX=minOf(minX,x); maxX=maxOf(maxX,x); minY=minOf(minY,y); maxY=maxOf(maxY,y); count++
            }
            val w=maxX-minX+1; val h=maxY-minY+1
            if(w !in 25..44 || h !in 25..44 || kotlin.math.abs(w-h)>5 || count < w*h*0.68) continue
            val centerX=(minX+maxX)/2; val centerY=(minY+maxY)/2
            // Filled green rounded-square with a small dark equalizer, not a green repeat outline.
            var dark=0
            for(y in centerY-h/5..centerY+h/5) for(x in centerX-w/5..centerX+w/5) {
                val c=frame.pixels[y*400+x]
                if((c ushr 16 and 255)<85 && (c ushr 8 and 255)<100 && (c and 255)<85) dark++
            }
            if(dark in 10..100) candidates+=Point(centerX*2,centerY*2)
        }
        return candidates.singleOrNull()
    }
}

/** Explicit command arms one attempt. Manual navigation/reconnect never arms it. */
internal class SpotifyPlayerShortcut {
    private var token=0L
    private var deadline=0L
    private var previous:SpotifyPlayerButton.Point?=null
    private var active=false
    fun arm(now:Long):Long { token++; deadline=now+4500; previous=null; active=true; return token }
    fun cancel() { active=false; previous=null; token++ }
    fun observe(id:Long,frame:AaSourceFrame?,now:Long):SpotifyPlayerButton.Point? {
        if(!active || id!=token || now>deadline) return null
        val point=frame?.let(SpotifyPlayerButton::find)
        if(point!=null && previous?.let { kotlin.math.abs(it.x-point.x)<=4 && kotlin.math.abs(it.y-point.y)<=4 }==true) {
            active=false; previous=null; return point
        }
        previous=point
        return null
    }
}
