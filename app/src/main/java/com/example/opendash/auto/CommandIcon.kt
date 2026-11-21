package com.example.opendash.auto

import android.graphics.*

/** Action symbols shared by the menu wheel and the profile reminder. */
internal object CommandIcon {
    fun draw(canvas:Canvas,command:BikeCommand,x:Float,y:Float,size:Float) {
        val save=canvas.save()
        canvas.translate(x-size/2,y-size/2);canvas.scale(size/24,size/24)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=1.7f
            strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
        }
        fun line(a:Float,b:Float,c:Float,d:Float)=canvas.drawLine(a,b,c,d,p)
        fun path(vararg xy:Float,close:Boolean=false) {
            canvas.drawPath(Path().apply {
                moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2) lineTo(xy[i],xy[i+1]);if(close) close()
            },p)
        }
        fun arrow(rotation:Float) {
            val s=canvas.save();canvas.rotate(rotation,12f,12f)
            line(4f,12f,20f,12f);path(14f,6f,20f,12f,14f,18f);canvas.restoreToCount(s)
        }
        fun grid() { for(a in listOf(3f,14f)) for(b in listOf(3f,14f)) canvas.drawRoundRect(a,b,a+7,b+7,1f,1f,p) }
        fun note() {
            path(9f,18f,9f,5f,20f,3f,20f,16f)
            canvas.drawOval(3f,16f,9f,21f,p);canvas.drawOval(14f,14f,20f,19f,p)
        }
        when(command) {
            BikeCommand.NONE -> { canvas.drawCircle(12f,12f,8f,p);line(6f,18f,18f,6f) }
            BikeCommand.VOICE,BikeCommand.VOICE_AND_BAR -> {
                canvas.drawRoundRect(9f,2f,15f,14f,3f,3f,p)
                canvas.drawArc(6f,5f,18f,18f,0f,180f,false,p);line(12f,18f,12f,22f);line(8f,22f,16f,22f)
                if(command==BikeCommand.VOICE_AND_BAR) line(19f,3f,23f,3f)
            }
            BikeCommand.PLAY_PAUSE -> { path(3f,4f,3f,20f,12f,12f,close=true);line(17f,5f,17f,19f);line(22f,5f,22f,19f) }
            BikeCommand.PREVIOUS,BikeCommand.NEXT -> {
                if(command==BikeCommand.PREVIOUS) { canvas.translate(24f,0f);canvas.scale(-1f,1f) }
                path(4f,4f,4f,20f,17f,12f,close=true);line(20f,4f,20f,20f)
            }
            BikeCommand.PROFILE_NEXT,BikeCommand.SWITCH_MAPS_SPOTIFY -> {
                path(3f,7f,21f,7f,17f,3f);path(21f,17f,3f,17f,7f,21f)
            }
            BikeCommand.VOLUME_UP,BikeCommand.VOLUME_DOWN -> {
                path(3f,9f,7f,9f,12f,4f,12f,20f,7f,15f,3f,15f,close=true)
                line(16f,12f,23f,12f);if(command==BikeCommand.VOLUME_UP) line(19.5f,8.5f,19.5f,15.5f)
            }
            BikeCommand.BACK,BikeCommand.AA_LEFT,BikeCommand.AA_PREVIOUS_ITEM -> arrow(180f)
            BikeCommand.AA_RIGHT,BikeCommand.AA_NEXT_ITEM -> arrow(0f)
            BikeCommand.AA_UP -> arrow(-90f)
            BikeCommand.AA_DOWN -> arrow(90f)
            BikeCommand.AA_SELECT,BikeCommand.AA_ENTER -> path(3f,12f,9f,18f,21f,5f)
            BikeCommand.AA_HOME -> { path(2f,11f,12f,2f,22f,11f);path(5f,9f,5f,22f,10f,22f,10f,15f,15f,15f,15f,22f,19f,22f,19f,9f) }
            BikeCommand.AA_APPS,BikeCommand.OPEN_OTHER -> grid()
            BikeCommand.OPEN_MAPS -> {
                canvas.drawPath(Path().apply { moveTo(12f,22f);cubicTo(-5f,6f,8f,-3f,16f,3f);cubicTo(25f,9f,17f,17f,12f,22f);close() },p)
                canvas.drawCircle(12f,9f,3f,p)
            }
            BikeCommand.OPEN_SPOTIFY -> note()
            BikeCommand.AA_DASHBOARD_LEFT,BikeCommand.AA_DASHBOARD_RIGHT -> {
                canvas.drawRoundRect(2f,3f,22f,21f,2f,2f,p);line(10f,3f,10f,21f);line(10f,12f,22f,12f)
            }
            BikeCommand.SHOW_BAR -> {
                canvas.drawRoundRect(2f,3f,22f,21f,2f,2f,p);line(2f,16f,22f,16f)
                for(a in listOf(7f,12f,17f)) canvas.drawCircle(a,18.5f,.5f,p)
            }
            BikeCommand.OPEN_WHATSAPP -> {
                canvas.drawPath(Path().apply { moveTo(5f,17f);cubicTo(-4f,4f,15f,-4f,21f,6f);cubicTo(27f,18f,14f,25f,8f,20f);lineTo(2f,22f);close() },p)
                path(8f,7f,8f,11f,13f,16f,17f,16f)
            }
            BikeCommand.TOGGLE_GPS_BUBBLE -> {
                canvas.drawCircle(12f,12f,9f,p);path(8f,15f,12f,5f,16f,15f,12f,13f,close=true)
            }
        }
        canvas.restoreToCount(save)
    }
}
