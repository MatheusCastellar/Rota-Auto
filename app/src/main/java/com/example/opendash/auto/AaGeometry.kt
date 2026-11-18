package com.example.opendash.auto

/** Preserve the entire AA image inside the Tripper video rectangle; never crop controls. */
object AaGeometry {
    const val SOURCE_WIDTH=800
    const val SOURCE_HEIGHT=480
    const val OUTPUT_WIDTH=526
    const val OUTPUT_HEIGHT=300
    data class Rect(val left:Float,val top:Float,val width:Float,val height:Float)
    const val HIDDEN_BAR_HEIGHT=80
    fun viewport(width:Int,height:Int,hideBar:Boolean=false):Rect {
        val sourceHeight=if(hideBar) SOURCE_HEIGHT-HIDDEN_BAR_HEIGHT else SOURCE_HEIGHT
        val outer=minOf(width.toFloat()/OUTPUT_WIDTH,height.toFloat()/OUTPUT_HEIGHT)
        val scale=minOf(OUTPUT_WIDTH.toFloat()/SOURCE_WIDTH,OUTPUT_HEIGHT.toFloat()/sourceHeight)*outer
        val w=SOURCE_WIDTH*scale; val h=sourceHeight*scale
        return Rect((width-w)/2,(height-h)/2,w,h)
    }
    fun touch(x:Float,y:Float,width:Int,height:Int):Pair<Int,Int>? {
        if(width<=0 || height<=0) return null
        val r=viewport(width,height)
        if(x<r.left || y<r.top || x>=r.left+r.width || y>=r.top+r.height) return null
        return (((x-r.left)*SOURCE_WIDTH/r.width).toInt().coerceIn(0,SOURCE_WIDTH-1) to
            ((y-r.top)*SOURCE_HEIGHT/r.height).toInt().coerceIn(0,SOURCE_HEIGHT-1))
    }
}
