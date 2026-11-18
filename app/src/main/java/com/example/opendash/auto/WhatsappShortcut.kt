package com.example.opendash.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas

/** Matches the installed WhatsApp icon, never a generic green button or text/message. */
internal class WhatsappIcon private constructor(private val template:BooleanArray) {
    data class Point(val x:Int,val y:Int)
    fun find(frame:AaSourceFrame):Point? {
        if(frame.width!=400 || frame.height!=240 || frame.pixels.size!=96000) return null
        val regions=components(frame,18,56)
        val content=regions.filter { it.bottom<200 }
        val found=(if(content.isNotEmpty()) content else regions).mapNotNull { r ->
            val mask=mask(frame,r) ?: return@mapNotNull null
            val shared=mask.indices.count { mask[it] && template[it] }
            val count=mask.count { it }+template.count { it }
            // Downsampling an icon to 24px shifts thin white edges by a pixel.
            fun covered(a:BooleanArray,b:BooleanArray)=a.indices.count { i -> a[i] &&
                (-1..1).any { dy -> (-1..1).any { dx ->
                    val x=i%24+dx; val y=i/24+dy
                    x in 0..23 && y in 0..23 && b[y*24+x]
                } }
            }
            val edgeScore=if(count==0) 0.0 else (covered(mask,template)+covered(template,mask)).toDouble()/count
            if(count==0 || 2.0*shared/count<.72 || edgeScore<.92) null else Point((r.left+r.right),(r.top+r.bottom))
        }
        return found.singleOrNull()
    }
    companion object {
        fun load(context:Context):WhatsappIcon?=runCatching {
            val drawable=context.packageManager.getApplicationIcon("com.whatsapp")
            val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0,0,128,128); drawable.draw(Canvas(bitmap))
                val pixels=IntArray(128*128); bitmap.getPixels(pixels,0,128,0,0,128,128)
                fromTemplate(AaSourceFrame(128,128,pixels))
            } finally { bitmap.recycle() }
        }.getOrNull()
        internal fun fromTemplate(frame:AaSourceFrame):WhatsappIcon? {
            val region=components(frame,16,frame.width).maxByOrNull { (it.right-it.left)*(it.bottom-it.top) } ?: return null
            return mask(frame,region)?.let(::WhatsappIcon)
        }
        private data class Region(val left:Int,val top:Int,val right:Int,val bottom:Int)
        private fun green(c:Int):Boolean {
            val r=c ushr 16 and 255; val g=c ushr 8 and 255; val b=c and 255
            return g>100 && g-r>35 && g-b>20
        }
        private fun white(c:Int)=((c ushr 24 and 255)>150 && (c ushr 16 and 255)>190 && (c ushr 8 and 255)>190 && (c and 255)>190)
        private fun components(f:AaSourceFrame,min:Int,max:Int):List<Region> {
            if(f.width<=0 || f.height<=0 || f.pixels.size!=f.width*f.height) return emptyList()
            val visited=BooleanArray(f.pixels.size); val queue=IntArray(f.pixels.size); val result=mutableListOf<Region>()
            for(start in f.pixels.indices) {
                if(visited[start] || !green(f.pixels[start])) continue
                var head=0; var tail=0; queue[tail++]=start; visited[start]=true
                var l=f.width; var t=f.height; var r=0; var b=0
                while(head<tail) {
                    val index=queue[head++]; val x=index%f.width; val y=index/f.width
                    l=minOf(l,x); r=maxOf(r,x); t=minOf(t,y); b=maxOf(b,y)
                    for(next in intArrayOf(if(x>0) index-1 else -1,if(x+1<f.width) index+1 else -1,index-f.width,index+f.width)) {
                        if(next in f.pixels.indices && !visited[next] && green(f.pixels[next])) { visited[next]=true; queue[tail++]=next }
                    }
                }
                val w=r-l+1; val h=b-t+1
                if(w in min..max && h in min..max && kotlin.math.abs(w-h)<w*.22) {
                    // The white speech-bubble outline separates green islands. Count all
                    // green pixels inside the icon, not just the exterior connected island.
                    var greenPixels=0
                    for(y in t..b) for(x in l..r) if(green(f.pixels[y*f.width+x])) greenPixels++
                    if(greenPixels>w*h*.35) result+=Region(l,t,r,b)
                }
            }
            return result.filter { r -> result.none { outer -> outer!==r && outer.left<=r.left && outer.top<=r.top && outer.right>=r.right && outer.bottom>=r.bottom } }
        }
        private fun mask(f:AaSourceFrame,r:Region):BooleanArray? {
            var l=r.right; var t=r.bottom; var right=r.left; var bottom=r.top; var count=0
            for(y in r.top..r.bottom) for(x in r.left..r.right) if(white(f.pixels[y*f.width+x])) {
                l=minOf(l,x); right=maxOf(right,x); t=minOf(t,y); bottom=maxOf(bottom,y); count++
            }
            val area=(r.right-r.left+1)*(r.bottom-r.top+1)
            if(count<area*.08 || count>area*.5 || right-l<5 || bottom-t<5) return null
            return BooleanArray(24*24) { i ->
                val x=l+(i%24)*(right-l)/23; val y=t+(i/24)*(bottom-t)/23
                white(f.pixels[y*f.width+x])
            }
        }
    }
}

internal class MapSpotifyToggle {
    private var last=BikeCommand.OPEN_MAPS
    fun next()=if(last==BikeCommand.OPEN_SPOTIFY) BikeCommand.OPEN_MAPS else BikeCommand.OPEN_SPOTIFY
    fun opened(command:BikeCommand) { if(command==BikeCommand.OPEN_MAPS || command==BikeCommand.OPEN_SPOTIFY) last=command }
}
