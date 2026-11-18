package com.example.opendash.auto

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.opendash.R

enum class PanelLogo(val label:String,val resource:Int) {
    NONE("Sem nada",0),
    HIMALAYAN("Himalayan",R.drawable.panel_himalayan),
    GUERRILLA("Guerrilla",R.drawable.panel_guerrilla),
    BEAR("Bear",R.drawable.panel_bear);

    companion object {
        const val PREFERENCE="panel-logo"
        const val SIZE="panel-logo-size"
        const val POSITION_X="panel-logo-x"
        const val POSITION_Y="panel-logo-y"
        const val BACKGROUND="panel-logo-background"
        fun resolve(value:String?)=entries.firstOrNull { it.name==value } ?: NONE
    }

    /** Trim and turn monochrome artwork into white + alpha once, never per video frame. */
    fun bitmap(resources:Resources,background:OverlayBackground=OverlayBackground.TRANSPARENT):Bitmap? {
        if(this==NONE) return null
        val source=BitmapFactory.decodeResource(resources,resource) ?: return null
        val pixels=IntArray(source.width*source.height)
        source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
        var left=source.width; var top=source.height; var right=-1; var bottom=-1
        for(y in 0 until source.height) for(x in 0 until source.width) {
            val p=pixels[y*source.width+x]
            if((p ushr 24)>0 && maxOf((p ushr 16) and 255,(p ushr 8) and 255,p and 255)>96) {
                left=minOf(left,x); top=minOf(top,y); right=maxOf(right,x); bottom=maxOf(bottom,y)
            }
        }
        if(right<left) { source.recycle(); return null }
        val width=right-left+1; val height=bottom-top+1
        val mask=IntArray(width*height)
        for(y in 0 until height) for(x in 0 until width) {
            val pixel=pixels[(top+y)*source.width+left+x]
            val light=maxOf((pixel ushr 16) and 255,(pixel ushr 8) and 255,pixel and 255)
            // Supplied backgrounds include RGB(5,6,8) and RGB(5,7,8), not just zero.
            // Remove that near-black floor, retaining grayscale coverage at letter edges.
            val alpha=(pixel ushr 24)*(light-16).coerceAtLeast(0)/(255-16)
            mask[y*width+x]=(alpha shl 24) or 0x00ffffff
        }
        source.recycle()
        val result=Bitmap.createBitmap(mask,width,height,Bitmap.Config.ARGB_8888)
        if(background==OverlayBackground.TRANSPARENT) return result
        val padding=(height*.12f).toInt().coerceAtLeast(2)
        val backed=Bitmap.createBitmap(width+padding*2,height+padding*2,Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(backed); background.paint(canvas,backed.width,backed.height)
        canvas.drawBitmap(result,padding.toFloat(),padding.toFloat(),null); result.recycle()
        return backed
    }
}
