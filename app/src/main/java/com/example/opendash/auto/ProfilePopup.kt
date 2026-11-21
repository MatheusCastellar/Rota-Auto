package com.example.opendash.auto

import android.content.Context
import android.graphics.*

internal data class ProfileSlot(val single:String,val double:String?,val inactive:Boolean,val command:BikeCommand=BikeCommand.NONE)
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
            ProfileSlot(name(single),if(double.command==BikeCommand.NONE) null else name(double),single.command==BikeCommand.NONE,single.command)
        },duration(prefs))
    }
    fun bitmap(state:ProfilePopupState):Bitmap {
        val bitmap=Bitmap.createBitmap(1052,600,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap).apply { scale(2f,2f) }
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val white=Color.WHITE
        val cx=263f; val cy=162f; val radius=122f; val center=48f
        p.color=0xBB000000.toInt(); c.drawRect(0f,0f,526f,300f,p)
        // Only the narrow ring and divider channels expose the translucent scrim.
        val sectors=Path().apply { addCircle(cx,cy,radius,Path.Direction.CW) }
        sectors.op(Path().apply { addCircle(cx,cy,center+3,Path.Direction.CW) },Path.Op.DIFFERENCE)
        val dividerPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=3f }
        for(angle in listOf(45.0,135.0,225.0,315.0)) {
            val a=Math.toRadians(angle); val x=kotlin.math.cos(a).toFloat(); val y=kotlin.math.sin(a).toFloat()
            val line=Path().apply { moveTo(cx,cy);lineTo(cx+x*(radius+2),cy+y*(radius+2)) }
            val channel=Path();dividerPaint.getFillPath(line,channel)
            sectors.op(channel,Path.Op.DIFFERENCE)
        }
        p.color=0xFF887644.toInt();c.drawPath(sectors,p);c.drawCircle(cx,cy,center,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=white
        c.drawPath(sectors,p)
        p.strokeWidth=2f;c.drawCircle(cx,cy,center,p)
        p.style=Paint.Style.FILL
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
        text(state.title,cx+(logoWidth+gap)/2f,25f,17f,white,180f)
        val points=listOf(263f to 80f,350f to 162f,263f to 244f,176f to 162f,263f to 162f)
        for((index,slot) in state.slots.withIndex()) {
            val (x,y)=points[index]
            val width=if(index==0 || index==2) 110f else if(index==4) 86f else 70f
            CommandIcon.draw(c,slot.command,x,y-12f,30f)
            text(slot.single,x,y+17,12f,white,width)
            slot.double?.let { text("2× $it",x,y+31,9f,white,width) }
        }
        return bitmap
    }
    fun mesh():FloatArray=floatArrayOf(-1f,1f,0f,1f, -1f,-1f,0f,0f, 1f,1f,1f,1f,
        1f,1f,1f,1f, -1f,-1f,0f,0f, 1f,-1f,1f,0f)
}
