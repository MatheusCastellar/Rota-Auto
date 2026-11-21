package com.example.opendash.auto

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small native toolbar icons; independent of the video, controls and saved settings. */
internal class NavigationIcon(private val kind:String,color:Int):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; style=Paint.Style.STROKE; strokeWidth=1.7f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    override fun draw(canvas:Canvas) {
        val save=canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat()); canvas.scale(bounds.width()/24f,bounds.height()/24f)
        when(kind) {
            "back" -> { canvas.drawLine(3f,12f,21f,12f,paint); canvas.drawPath(Path().apply { moveTo(10f,5f);lineTo(3f,12f);lineTo(10f,19f) },paint) }
            "navigate" -> { canvas.drawPath(Path().apply { moveTo(12f,2f);lineTo(21f,21f);lineTo(12f,17f);lineTo(3f,21f);close() },paint) }
            "wifi" -> { canvas.drawArc(2f,5f,22f,24f,220f,100f,false,paint); canvas.drawArc(6f,10f,18f,22f,220f,100f,false,paint); canvas.drawCircle(12f,19f,.8f,paint) }
            "panel" -> { canvas.drawCircle(12f,12f,9f,paint); canvas.drawLine(12f,12f,16f,7f,paint); canvas.drawLine(6f,17f,18f,17f,paint) }
            "controls" -> { canvas.drawRoundRect(2f,6f,22f,19f,4f,4f,paint); canvas.drawLine(5f,12f,10f,12f,paint); canvas.drawLine(7.5f,9.5f,7.5f,14.5f,paint); canvas.drawCircle(16f,11f,.6f,paint); canvas.drawCircle(19f,14f,.6f,paint) }
            else -> { for(y in listOf(6f,12f,18f)) { canvas.drawLine(3f,y,21f,y,paint) }; canvas.drawCircle(8f,6f,2f,paint); canvas.drawCircle(16f,12f,2f,paint); canvas.drawCircle(10f,18f,2f,paint) }
        }
        canvas.restoreToCount(save)
    }
    override fun setTint(tintColor:Int) { paint.color=tintColor; invalidateSelf() }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
