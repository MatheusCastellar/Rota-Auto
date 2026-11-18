package com.example.opendash.auto

import android.content.Context
import android.graphics.*

internal data class ProfileSlot(val single:String,val double:String?,val inactive:Boolean)
internal data class ProfilePopupState(val title:String,val slots:List<ProfileSlot>,val durationMs:Long=2_000L)

/** Informational overlay: never intercepts or generates motorcycle/Android Auto input. */
internal object ProfilePopup {
    const val ENABLED="profile-popup-enabled"
    const val DURATION="profile-popup-half-seconds"
    const val DEFAULT_STEPS=4
    fun duration(prefs:android.content.SharedPreferences)=prefs.getInt(DURATION,DEFAULT_STEPS).coerceIn(1,20)*500L
    val buttons=listOf(TripperControls.Action.UP,TripperControls.Action.RIGHT,TripperControls.Action.DOWN,
        TripperControls.Action.LEFT,TripperControls.Action.SELECT)
    fun read(context:Context):ProfilePopupState {
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val profile=BikeControlLayout.active(prefs)
        val language=UiLanguage.selected(context)
        fun name(binding:BikeBinding)=CommandNames.short(binding.command,language,
            if(binding.command==BikeCommand.OPEN_OTHER) CarApplications.label(context,binding.appPackage)
                ?: binding.appPackage?.substringAfterLast('.') else null)
        return ProfilePopupState(ProfileNames.label(context,false,profile),buttons.map { button ->
            val single=BikeControlLayout.binding(prefs,profile,button,false)
            val double=BikeControlLayout.binding(prefs,profile,button,true)
            ProfileSlot(name(single),if(double.command==BikeCommand.NONE) null else name(double),single.command==BikeCommand.NONE)
        },duration(prefs))
    }
    fun bitmap(state:ProfilePopupState):Bitmap {
        val bitmap=Bitmap.createBitmap(1052,600,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap).apply { scale(2f,2f) }
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val green=Color.rgb(189,249,99); val white=Color.rgb(240,246,242)
        val cx=263f; val cy=162f; val radius=122f; val center=40f
        p.color=0x88000000.toInt(); c.drawRect(0f,0f,526f,300f,p)
        p.color=0xF5101E18.toInt(); c.drawCircle(cx,cy,radius,p)
        p.color=0xFF203A2B.toInt(); c.drawCircle(cx,cy,center,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=1f; p.color=0xFF4D6958.toInt()
        c.drawCircle(cx,cy,radius,p)
        for(angle in listOf(45.0,135.0,225.0,315.0)) {
            val a=Math.toRadians(angle); val x=kotlin.math.cos(a).toFloat(); val y=kotlin.math.sin(a).toFloat()
            c.drawLine(cx+x*center,cy+y*center,cx+x*radius,cy+y*radius,p)
        }
        p.style=Paint.Style.STROKE; p.color=green; c.drawCircle(cx,cy,center,p); p.style=Paint.Style.FILL
        fun text(value:String,x:Float,y:Float,size:Float,color:Int,maxWidth:Float) {
            p.textAlign=Paint.Align.CENTER; p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            p.textSize=size; p.color=color
            if(p.measureText(value)>maxWidth) p.textSize=size*maxWidth/p.measureText(value)
            c.drawText(value,x,y,p)
        }
        // Center the logo and localized title together, keeping the wheel clear.
        p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); p.textSize=17f
        val titleWidth=p.measureText(state.title).coerceAtMost(180f)
        val titleCenterY=25f+(p.fontMetrics.ascent+p.fontMetrics.descent)/2f
        val logoWidth=14f; val gap=6f; val logoScale=logoWidth/30f
        val logoLeft=cx-(logoWidth+gap+titleWidth)/2f
        c.save(); c.translate(logoLeft-9f*logoScale,titleCenterY-22.5f*logoScale); c.scale(logoScale,logoScale)
        p.color=Color.rgb(185,246,101)
        c.drawPath(Path().apply { moveTo(24f,6f); lineTo(39f,39f); lineTo(24f,31f); lineTo(9f,39f); close() },p)
        c.restore()
        text(state.title,cx+(logoWidth+gap)/2f,25f,17f,green,180f)
        val points=listOf(263f to 81f,348f to 162f,263f to 244f,178f to 162f,263f to 162f)
        for((index,slot) in state.slots.withIndex()) {
            val (x,y)=points[index]; val width=if(index==0 || index==2) 116f else if(index==4) 72f else 86f
            // Small directional marks make the physical positions explicit, independent of the action.
            p.color=green; p.style=Paint.Style.STROKE; p.strokeWidth=1.8f; p.strokeCap=Paint.Cap.ROUND
            val iy=y-17f
            if(index==4) c.drawCircle(x,iy,3f,p) else {
                val dx=when(index) { 1 -> 1f; 3 -> -1f; else -> 0f }
                val dy=when(index) { 0 -> -1f; 2 -> 1f; else -> 0f }
                c.drawLine(x-dx*4,iy-dy*4,x+dx*4,iy+dy*4,p)
                c.drawLine(x+dx*4,iy+dy*4,x-dx*1-dy*3,iy-dy*1+dx*3,p)
                c.drawLine(x+dx*4,iy+dy*4,x-dx*1+dy*3,iy-dy*1-dx*3,p)
            }
            p.style=Paint.Style.FILL
            text(slot.single,x,y+4,12f,if(slot.inactive) 0xFF9BAAA0.toInt() else white,width)
            slot.double?.let { text("2× $it",x,y+20,10f,green,width) }
        }
        return bitmap
    }
    fun mesh():FloatArray=floatArrayOf(-1f,1f,0f,1f, -1f,-1f,0f,0f, 1f,1f,1f,1f,
        1f,1f,1f,1f, -1f,-1f,0f,0f, 1f,-1f,1f,0f)
}
