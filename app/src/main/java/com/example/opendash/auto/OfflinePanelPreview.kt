package com.example.opendash.auto

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.view.View

/** Local illustration only: no GPS, SDK, network, projection service or motorcycle packets. */
internal class OfflinePanelPreview(context:Context,private val panel:Boolean):View(context) {
    private val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> postInvalidateOnAnimation() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var sample:Bitmap?=null
    private val temporaryBar=TemporaryBar()
    private var barConfig:Pair<Int,Boolean>?=null
    private var cropped:Bitmap?=null
    private var blurred:Bitmap?=null
    private var logo:Bitmap?=null
    private var logoKey:Pair<PanelLogo,OverlayBackground>?=null
    private var widget:Bitmap?=null
    private var widgetStyle:WidgetStyle?=null
    init { contentDescription=UiLanguage.text(context,"Prévia de exemplo sem conexão"); importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable=true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(listener) }
    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        listOf(sample,cropped,blurred,logo,widget).forEach { it?.recycle() }
        sample=null; cropped=null; blurred=null; logo=null; widget=null; logoKey=null; widgetStyle=null
        super.onDetachedFromWindow()
    }
    private fun example():Bitmap {
        sample?.let { return it }
        val bitmap=offlineImage(480)
        sample=bitmap;cropped=offlineImage(400)
        blurred=Bitmap.createScaledBitmap(cropped!!,32,16,true)
        return bitmap
    }
    private fun offlineImage(height:Int):Bitmap {
        val bitmap=Bitmap.createBitmap(800,height,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.WHITE)
        // Center the emblem itself, independently of the caption and the taskbar crop.
        val outer=Path().apply {
            moveTo(-50f,30f);lineTo(-9f,-47f)
            quadTo(0f,-64f,9f,-47f);lineTo(50f,30f)
        }
        val inner=Path().apply {
            moveTo(-7f,-16f);quadTo(0f,-29f,7f,-16f)
            lineTo(36f,39f);quadTo(42f,52f,29f,48f)
            lineTo(0f,39f);lineTo(-29f,48f)
            quadTo(-42f,52f,-36f,39f);close()
        }
        p.style=Paint.Style.STROKE;p.strokeWidth=14f
        p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        val outline=Path();p.getFillPath(outer,outline)
        val bounds=RectF();outline.computeBounds(bounds,true)
        val innerBounds=RectF();inner.computeBounds(innerBounds,true);bounds.union(innerBounds)
        val save=c.save()
        c.translate(400f-bounds.centerX(),height/2f-bounds.centerY())
        p.color=Color.rgb(202,202,202);c.drawPath(outer,p)
        p.style=Paint.Style.FILL;c.drawPath(inner,p)
        c.restoreToCount(save)
        p.color=Color.rgb(117,117,117)
        p.textSize=24f;p.textAlign=Paint.Align.CENTER
        p.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val textTop=height/2f+bounds.height()/2f+20f
        c.drawText(UiLanguage.text(context,"Offline"),400f,textTop-p.fontMetrics.ascent,p)
        return bitmap
    }
    fun showBar() {
        val config=prefs.getInt(TemporaryBar.SECONDS,5) to prefs.getBoolean(TemporaryBar.TOGGLE,false)
        if(config!=barConfig) { temporaryBar.configure(config.first,config.second);barConfig=config }
        temporaryBar.show(android.os.SystemClock.elapsedRealtime());postInvalidateDelayed(16)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val source=example()
        canvas.drawColor(if(panel) Color.rgb(13,48,36) else Color.WHITE)
        val scale=minOf(width/526f,height/300f)
        val save=canvas.save();canvas.translate((width-526*scale)/2,(height-300*scale)/2);canvas.scale(scale,scale)
        if(!panel) {
            canvas.drawBitmap(source,null,RectF(13f,0f,513f,300f),paint)
        } else {
            val clip=canvas.save()
            canvas.clipRect(0f,0f,526f,300f)
            canvas.clipPath(Path().apply { addCircle(263f,263f,263f,Path.Direction.CW) })
            canvas.drawColor(Color.BLACK)
            val mode=PanelLayout.resolve(prefs.getString(PanelLayout.PREFERENCE,null),prefs.getBoolean("hide-aa-bar",false))
            val mesh=PanelGeometry.mesh(mode,prefs.getInt(PanelLayout.DISTORTION,PanelLayout.DEFAULT_DISTORTION),prefs.getInt(PanelLayout.POSITION_Y,0),
                prefs.getInt(PanelLayout.SIZE_X,100),prefs.getInt(PanelLayout.SIZE_Y,100),CurveStyle.resolve(prefs.getString(CurveStyle.PREFERENCE,null)),prefs.getInt(PanelLayout.POSITION_X,0))
            val bounds=PanelGeometry.bounds(mesh)
            val area=BlurArea.resolve(prefs.getString(BlurArea.PREFERENCE,null),prefs.getBoolean("panel-blur",true))
            if(mode in listOf(PanelLayout.FIT,PanelLayout.CURVED) && area!=BlurArea.OFF) {
                val blurSave=canvas.save()
                canvas.clipRect(0f,if(area==BlurArea.ALL) 0f else bounds.top,526f,if(area==BlurArea.SIDES) bounds.top+bounds.height else 300f)
                canvas.drawBitmap(blurred!!,null,RectF(0f,0f,526f,300f),paint)
                paint.color=0x59000000;canvas.drawRect(0f,0f,526f,300f,paint);paint.alpha=255;canvas.restoreToCount(blurSave)
            }
            val columns=if(mode==PanelLayout.CURVED) 32 else 1;val rows=if(mode==PanelLayout.CURVED) 96 else 1
            canvas.drawBitmapMesh(if(mode.hidesBar) cropped!! else source,columns,rows,grid(mesh,columns,rows),0,null,0,paint)
            val barProgress=temporaryBar.progress(android.os.SystemClock.elapsedRealtime())
            if(barProgress>0f) {
                val top=300-50*barProgress
                paint.color=Color.BLACK;canvas.drawRect(0f,top,526f,top+50,paint)
                canvas.drawBitmap(source,Rect(0,400,800,480),RectF(4f,top,522f,top+50),paint)
                postInvalidateDelayed(33)
            }
            val style=WidgetStyle.read(prefs)
            if(widgetStyle!=style) {
                widget?.recycle();widget=if(style.enabled) PanelWidget.bitmap(WidgetState("21/11","12:30",75,false,"5G"),style.background,style.layout,style.height) else null
                widgetStyle=style
            }
            fun drawWidget() { widget?.let { drawOverlay(canvas,it,PanelWidget.bounds(style.size,style.x,style.y,style.layout,style.height)) } }
            if(!style.front) drawWidget()
            if(mode in listOf(PanelLayout.FIT,PanelLayout.CURVED)) {
                val key=PanelLogo.resolve(prefs.getString(PanelLogo.PREFERENCE,null)) to OverlayBackground.resolve(prefs.getString(PanelLogo.BACKGROUND,null))
                if(logoKey!=key) { logo?.recycle();logo=key.first.bitmap(resources,key.second);logoKey=key }
                logo?.let { b -> drawOverlay(canvas,b,PanelGeometry.bounds(PanelGeometry.logoMesh(b.width.toFloat()/b.height,prefs.getInt(PanelLogo.SIZE,100),prefs.getInt(PanelLogo.POSITION_X,0),prefs.getInt(PanelLogo.POSITION_Y,0)))) }
            }
            if(style.front) drawWidget()
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(save)
    }
    private fun drawOverlay(canvas:Canvas,bitmap:Bitmap,r:AaGeometry.Rect) { canvas.drawBitmap(bitmap,null,RectF(r.left,r.top,r.left+r.width,r.top+r.height),paint) }
    companion object {
        /** Reuse the exact GPU geometry; extract its regular grid for Canvas bitmap sampling. */
        internal fun grid(mesh:FloatArray,columns:Int,rows:Int):FloatArray {
            require(mesh.size==columns*rows*24)
            val result=FloatArray((columns+1)*(rows+1)*2)
            fun vertex(row:Int,col:Int,offset:Int) {
                val i=(row*(columns+1)+col)*2;result[i]=(mesh[offset]+1)*263f;result[i+1]=(1-mesh[offset+1])*150f
            }
            for(row in 0 until rows) for(col in 0 until columns) {
                val base=(row*columns+col)*24
                vertex(row,col,base);vertex(row+1,col,base+4);vertex(row,col+1,base+8);vertex(row+1,col+1,base+20)
            }
            return result
        }
    }
}
