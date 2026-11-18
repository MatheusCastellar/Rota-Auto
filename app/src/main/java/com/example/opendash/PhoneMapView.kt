package com.example.opendash

import android.content.Context
import android.graphics.*
import android.view.*
import com.example.opendash.dash.map.Mercator
import com.example.opendash.dash.map.TileProvider
import com.example.opendash.dash.nav.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlin.math.*

/** Visible tiles only. Map browsing works independently of the motorcycle service. */
class PhoneMapView(context:Context,scope:CoroutineScope):View(context) {
    private val tiles=TileProvider(context,scope)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var center=GeoPoint(-14.2,-51.9); private set
    var zoom=4; private set
    var marker:GeoPoint?=null
    var position:GeoPoint?=null
    var route:List<GeoPoint> = emptyList()
    var onPick:((GeoPoint)->Unit)?=null
    private var scaling=0f
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector:ScaleGestureDetector):Boolean { scaling=1f; return true }
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            scaling*=detector.scaleFactor
            if(scaling>1.5f) { changeZoom(1); scaling=1f }
            else if(scaling<.66f) { changeZoom(-1); scaling=1f }
            return true
        }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:android.view.MotionEvent)=true
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!scale.isInProgress) {
                val x=Mercator.lngToTileX(center.lng,zoom)+dx/256
                val y=Mercator.latToTileY(center.lat,zoom)+dy/256
                center=GeoPoint(Mercator.tileYToLat(y,zoom).coerceIn(-85.0,85.0),((Mercator.tileXToLng(x,zoom)+540)%360)-180)
                invalidate()
            }; return true
        }
        override fun onDoubleTap(e:MotionEvent):Boolean { changeZoom(1); return true }
        override fun onLongPress(e:MotionEvent) {
            val x=Mercator.lngToTileX(center.lng,zoom)+(e.x-width/2f)/256
            val y=Mercator.latToTileY(center.lat,zoom)+(e.y-height/2f)/256
            val point=GeoPoint(Mercator.tileYToLat(y,zoom).coerceIn(-85.0,85.0),((Mercator.tileXToLng(x,zoom)+540)%360)-180)
            onPick?.invoke(point)
        }
    })
    init { contentDescription="Mapa: arraste para mover, use dois dedos para ampliar, segure para escolher um destino" }
    fun focus(point:GeoPoint,z:Int=15) { center=point; zoom=z.coerceIn(2,19); invalidate() }
    fun changeZoom(delta:Int) { zoom=(zoom+delta).coerceIn(2,19); invalidate() }
    fun fit(points:List<GeoPoint>) {
        if(points.isEmpty()) return
        val minLat=points.minOf { it.lat }; val maxLat=points.maxOf { it.lat }
        val minLng=points.minOf { it.lng }; val maxLng=points.maxOf { it.lng }
        center=GeoPoint((minLat+maxLat)/2,(minLng+maxLng)/2)
        zoom=(18 downTo 2).firstOrNull { z ->
            abs(Mercator.lngToTileX(maxLng,z)-Mercator.lngToTileX(minLng,z))*256<width*.8 &&
                abs(Mercator.latToTileY(maxLat,z)-Mercator.latToTileY(minLat,z))*256<height*.8
        } ?: 2; invalidate()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP && event.actionMasked!=MotionEvent.ACTION_CANCEL)
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick():Boolean { super.performClick(); return true }
    override fun onDraw(c:Canvas) {
        super.onDraw(c); c.drawColor(Color.rgb(222,229,220))
        val tx=Mercator.lngToTileX(center.lng,zoom); val ty=Mercator.latToTileY(center.lat,zoom)
        val sx=floor(tx-width/512.0).toInt(); val ex=ceil(tx+width/512.0).toInt()
        val sy=floor(ty-height/512.0).toInt(); val ey=ceil(ty+height/512.0).toInt()
        var loaded=0
        for(x in sx..ex) for(y in sy..ey) tiles.get(zoom,x,y)?.let {
            c.drawBitmap(it,(width/2+(x-tx)*256).toFloat(),(height/2+(y-ty)*256).toFloat(),p); loaded++
        }
        fun xy(point:GeoPoint)=Pair((width/2+(Mercator.lngToTileX(point.lng,zoom)-tx)*256).toFloat(),
            (height/2+(Mercator.latToTileY(point.lat,zoom)-ty)*256).toFloat())
        if(route.isNotEmpty()) {
            val path=Path(); route.forEachIndexed { i,point -> val (x,y)=xy(point); if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
            p.style=Paint.Style.STROKE; p.strokeWidth=9f; p.color=Color.WHITE; c.drawPath(path,p)
            p.strokeWidth=5f; p.color=Color.rgb(0,100,230); c.drawPath(path,p); p.style=Paint.Style.FILL
        }
        fun dot(point:GeoPoint,color:Int) { val (x,y)=xy(point); p.color=Color.WHITE; c.drawCircle(x,y,14f,p); p.color=color; c.drawCircle(x,y,10f,p) }
        position?.let { dot(it,Color.rgb(0,100,230)) }; marker?.let { dot(it,Color.rgb(220,65,30)) }
        p.textSize=12*resources.displayMetrics.scaledDensity
        if(loaded==0) { p.color=Color.DKGRAY; c.drawText("Carregando mapa · confira a internet",12f,height/2f,p) }
        p.color=Color.argb(230,255,255,255); c.drawRect(0f,height-p.textSize*1.7f,width.toFloat(),height.toFloat(),p)
        p.color=Color.DKGRAY; c.drawText("© OpenStreetMap contributors",8f,height-8f,p)
        if(isShown && windowVisibility==VISIBLE) postInvalidateDelayed(350)
    }
}
