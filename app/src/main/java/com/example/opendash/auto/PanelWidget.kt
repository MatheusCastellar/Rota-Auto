package com.example.opendash.auto

import android.content.*
import android.graphics.*
import android.net.*
import android.os.*
import android.telephony.*
import java.time.*
import java.time.format.DateTimeFormatter

enum class OverlayBackground(val label:String) {
    TRANSPARENT("Transparente"), ROUND("Preto · cantos arredondados"), SQUARE("Preto · cantos retos");
    companion object { fun resolve(value:String?)=entries.firstOrNull { it.name==value } ?: TRANSPARENT }
    fun paint(canvas:Canvas,width:Int,height:Int) {
        if(this==TRANSPARENT) return
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK }
        if(this==ROUND) canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),height*.22f,height*.22f,p)
        else canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),p)
    }
}

internal data class WidgetState(val date:String,val time:String,val battery:Int,val charging:Boolean,val network:String)
internal enum class WidgetLayout(val label:String) {
    LINEAR("Linear"), BLOCK("Quadrado");
    companion object { fun resolve(value:String?)=entries.firstOrNull { it.name==value } ?: LINEAR }
}
internal data class WidgetStyle(val enabled:Boolean=false,val size:Int=100,val x:Int=0,val y:Int=0,
    val background:OverlayBackground=OverlayBackground.TRANSPARENT,val front:Boolean=true,val layout:WidgetLayout=WidgetLayout.LINEAR,val height:Int=100) {
    companion object { fun read(p:SharedPreferences)=WidgetStyle(p.getBoolean(PanelWidget.ENABLED,false),p.getInt(PanelWidget.SIZE,100),
        p.getInt(PanelWidget.X,0),p.getInt(PanelWidget.Y,0),OverlayBackground.resolve(p.getString(PanelWidget.BACKGROUND,null)),p.getBoolean(PanelWidget.FRONT,true),WidgetLayout.resolve(p.getString(PanelWidget.LAYOUT,null)),p.getInt(PanelWidget.HEIGHT,100)) }
}
internal object PanelWidget {
    const val ENABLED="widget-enabled"; const val SIZE="widget-size"; const val X="widget-x"; const val Y="widget-y"
    const val BACKGROUND="widget-background"; const val FRONT="widget-front"
    const val LAYOUT="widget-layout"
    const val HEIGHT="widget-height"
    fun bounds(size:Int=100,x:Int=0,y:Int=0,layout:WidgetLayout=WidgetLayout.LINEAR,heightPercent:Int=100):AaGeometry.Rect {
        val scale=size.coerceIn(25,200)/100f
        val width=if(layout==WidgetLayout.LINEAR) 280f else 150f
        val height=(if(layout==WidgetLayout.LINEAR) 36f else 48f)*heightPercent.coerceIn(75,200)/100f
        // Both formats share the same anchor; changing layout must not move its center.
        val centerY=72f
        return AaGeometry.Rect(263-width/2*scale+x,centerY-height/2*scale+y,width*scale,height*scale)
    }
    fun mesh(size:Int,x:Int,y:Int,layout:WidgetLayout=WidgetLayout.LINEAR,heightPercent:Int=100):FloatArray {
        val r=bounds(size,x,y,layout,heightPercent)
        fun v(x:Float,y:Float,u:Float,v:Float)=listOf(x/263-1,1-y/150,u,1-v)
        return (v(r.left,r.top,0f,0f)+v(r.left,r.top+r.height,0f,1f)+v(r.left+r.width,r.top,1f,0f)+
            v(r.left+r.width,r.top,1f,0f)+v(r.left,r.top+r.height,0f,1f)+v(r.left+r.width,r.top+r.height,1f,1f)).toFloatArray()
    }
    fun bitmap(s:WidgetState,background:OverlayBackground,layout:WidgetLayout=WidgetLayout.LINEAR,heightPercent:Int=100):Bitmap {
        val block=layout==WidgetLayout.BLOCK
        val out=Bitmap.createBitmap(if(block) 384 else 560,((if(block) 123 else 72)*heightPercent.coerceIn(75,200)/100f).toInt(),Bitmap.Config.ARGB_8888)
        val c=Canvas(out); background.paint(c,out.width,out.height)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=if(block) 32f else 27f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        val battery=if(s.battery<0) "—" else "${s.battery}%"
        val chargeWidth=if(s.charging) 6f+p.measureText("+") else 0f
        val dateWidth=p.measureText(s.date)+16f+p.measureText(s.time)
        val statusWidth=42f+8f+p.measureText(battery)+chargeWidth+18f+22f+8f+p.measureText(s.network)
        fun label(value:String,x:Float,cy:Float) { p.style=Paint.Style.FILL; c.drawText(value,x,cy-(p.fontMetrics.ascent+p.fontMetrics.descent)/2,p) }
        fun dateTime(x:Float,cy:Float) { label(s.date,x,cy); label(s.time,x+p.measureText(s.date)+16,cy) }
        fun status(x:Float,cy:Float) {
            p.style=Paint.Style.STROKE; p.strokeWidth=2.5f
            c.drawRoundRect(x,cy-12,x+38,cy+12,3f,3f,p); c.drawLine(x+41,cy-5,x+41,cy+5,p)
            p.style=Paint.Style.FILL
            if(s.battery>=0) c.drawRect(x+4,cy-8,x+4+30*s.battery.coerceIn(0,100)/100f,cy+8,p)
            var next=x+50
            label(battery,next,cy); next+=p.measureText(battery)
            if(s.charging) { next+=6; label("+",next,cy); next+=p.measureText("+") }
            next+=18
            // Antenna describes cellular technology, not invented reception bars.
            p.style=Paint.Style.STROKE
            c.drawLine(next+11,cy-8,next+11,cy+11,p); c.drawLine(next+5,cy+11,next+17,cy+11,p)
            c.drawArc(next,cy-16,next+22,cy+6,205f,130f,false,p); c.drawCircle(next+11,cy-7,2f,p)
            label(s.network,next+30,cy)
        }
        if(block) {
            val textHeight=p.fontMetrics.descent-p.fontMetrics.ascent
            val gap=maxOf(8f,(out.height-2*textHeight)*.35f)
            dateTime((out.width-dateWidth)/2,out.height/2f-(textHeight+gap)/2)
            status((out.width-statusWidth)/2,out.height/2f+(textHeight+gap)/2)
        } else {
            val left=(out.width-dateWidth-24-statusWidth)/2
            dateTime(left,out.height/2f); status(left+dateWidth+24,out.height/2f)
        }
        return out
    }
}

/** Reads only display information; never phone numbers, call audio, identifiers or location. */
internal class PhoneWidgetStatus(private val context:Context):AutoCloseable {
    private var manager:TelephonyManager?=null
    private var callback:TelephonyCallback?=null
    private var subscription=Int.MIN_VALUE
    @Volatile private var networkType=0
    @Volatile private var overrideType=0
    private fun listen() {
        if(Build.VERSION.SDK_INT<31) {
            // Old releases may require a phone permission; an unavailable value stays unknown.
            @Suppress("MissingPermission")
            networkType=runCatching { context.getSystemService(TelephonyManager::class.java)?.dataNetworkType ?: 0 }.getOrDefault(0)
            return
        }
        val id=SubscriptionManager.getDefaultDataSubscriptionId()
        if(id==subscription) return
        close(); subscription=id; networkType=0; overrideType=0
        if(!SubscriptionManager.isValidSubscriptionId(id)) return
        val tm=context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(id) ?: return
        val listener=object:TelephonyCallback(),TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info:TelephonyDisplayInfo) { networkType=info.networkType; overrideType=info.overrideNetworkType }
        }
        runCatching { tm.registerTelephonyCallback(context.mainExecutor,listener) }.onSuccess { manager=tm; callback=listener }
    }
    fun read(now:ZonedDateTime=ZonedDateTime.now()):WidgetState {
        runCatching { listen() }
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val percent=if(scale>0 && level>=0) (level*100/scale).coerceIn(0,100) else -1
        val charging=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)!=0
        val network=cellular(networkType,overrideType)
        return WidgetState(now.format(DateTimeFormatter.ofPattern("dd/MM")),now.format(DateTimeFormatter.ofPattern("HH:mm")),percent,charging,network)
    }
    override fun close() {
        if(Build.VERSION.SDK_INT>=31) callback?.let { cb -> runCatching { manager?.unregisterTelephonyCallback(cb) } }
        callback=null; manager=null; subscription=Int.MIN_VALUE
    }
    companion object {
        fun cellular(type:Int,override:Int):String=when {
            type==20 || override in listOf(3,4,5) -> "5G"
            type in listOf(13,19) -> "4G"
            type in listOf(3,5,6,8,9,10,12,14,15,17) -> "3G"
            type in listOf(1,2,4,7,11,16) -> "2G"
            else -> "—"
        }
    }
}
