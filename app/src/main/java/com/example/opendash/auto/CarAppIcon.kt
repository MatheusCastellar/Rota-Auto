package com.example.opendash.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.abs

/** Matches the selected installed icon in AA's original frame. Ambiguous matches do nothing. */
internal class CarAppIcon private constructor(private val template:AaSourceFrame) {
    data class Point(val x:Int,val y:Int)
    private data class Candidate(val x:Int,val y:Int,val size:Int,val error:Double)
    private val samples=(0 until 16*16).filter { i ->
        val x=i%16-7.5; val y=i/16-7.5; x*x+y*y<49
    }
    private val coarse=samples.filterIndexed { index,_ -> index%5==0 }
    private fun error(frame:AaSourceFrame,x:Int,y:Int,size:Int,indices:List<Int>):Double {
        var total=0.0
        for(i in indices) {
            val tx=(i%16*template.width/16).coerceAtMost(template.width-1)
            val ty=(i/16*template.height/16).coerceAtMost(template.height-1)
            val a=template.pixels[ty*template.width+tx]
            val b=frame.pixels[(y+i/16*size/16)*frame.width+x+i%16*size/16]
            total+=(abs((a ushr 16 and 255)-(b ushr 16 and 255))+abs((a ushr 8 and 255)-(b ushr 8 and 255))+abs((a and 255)-(b and 255)))/3.0
        }
        return total/indices.size
    }
    fun find(frame:AaSourceFrame):Point? {
        if(frame.width!=400 || frame.height!=240 || frame.pixels.size!=96000) return null
        val candidates=java.util.PriorityQueue<Candidate>(compareByDescending { it.error })
        for(size in 24..48 step 2) for(y in 0..(205-size) step 3) for(x in 0..(400-size) step 3) {
            val score=error(frame,x,y,size,coarse)
            if(score<32 && (candidates.size<48 || score<candidates.peek().error)) {
                candidates.add(Candidate(x,y,size,score)); if(candidates.size>48) candidates.poll()
            }
        }
        val refined=mutableListOf<Candidate>()
        for(c in candidates) for(size in (c.size-1)..(c.size+1)) for(y in (c.y-2)..(c.y+2)) for(x in (c.x-2)..(c.x+2)) {
            if(x<0 || y<0 || x+size>400 || y+size>205) continue
            val score=error(frame,x,y,size,samples)
            if(score<24) refined+=Candidate(x,y,size,score)
        }
        val best=refined.minByOrNull { it.error } ?: return null
        val cx=best.x+best.size/2; val cy=best.y+best.size/2
        val other=refined.filter { abs(it.x+it.size/2-cx)>best.size/2 || abs(it.y+it.size/2-cy)>best.size/2 }.minOfOrNull { it.error }
        if(other!=null && other-best.error<5) return null
        return Point(cx*2,cy*2)
    }
    companion object {
        fun load(context:Context,packageName:String):CarAppIcon?=runCatching {
            val drawable=context.packageManager.getApplicationIcon(packageName)
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0,0,64,64); drawable.draw(Canvas(bitmap))
                val pixels=IntArray(4096); bitmap.getPixels(pixels,0,64,0,0,64,64)
                fromTemplate(AaSourceFrame(64,64,pixels))
            } finally { bitmap.recycle() }
        }.getOrNull()
        internal fun fromTemplate(frame:AaSourceFrame):CarAppIcon? {
            if(frame.width<=0 || frame.height<=0 || frame.pixels.size!=frame.width*frame.height) return null
            val model=CarAppIcon(frame)
            val values=model.samples.map { i ->
                val c=frame.pixels[(i/16*frame.height/16)*frame.width+i%16*frame.width/16]
                ((c ushr 16 and 255)+(c ushr 8 and 255)+(c and 255))/3
            }
            if(values.max()-values.min()<50) return null
            return model
        }
    }
}
