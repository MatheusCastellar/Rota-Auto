package com.example.opendash.auto

enum class PanelLayout(val label:String,val hidesBar:Boolean) {
    ORIGINAL("A · Com barra",false),
    FIT("B · Sem barra, sem deformar",true),
    FILL("C · Sem barra, preencher altura",true),
    CURVED("D · Sem barra, personalizado",true);

    companion object {
        const val PREFERENCE="panel-layout"
        const val DISTORTION="panel-distortion"
        const val DEFAULT_DISTORTION=30
        const val POSITION_Y="panel-position-y"
        const val POSITION_X="panel-position-x"
        const val SIZE_X="panel-size-x"
        const val SIZE_Y="panel-size-y"
        fun resolve(saved:String?,legacyHideBar:Boolean=false)=entries.firstOrNull { it.name==saved }
            ?: if(legacyHideBar) FIT else ORIGINAL
    }
}

/** Approximate round display in the 526x300 video plane. All meshes are precomputed. */
object PanelGeometry {
    const val WIDTH=526f
    const val HEIGHT=300f
    const val CURVE_TOP=0f
    const val BOTTOM=300f
    fun halfWidth(y:Float):Float = kotlin.math.sqrt((263f*263f-(y-263f)*(y-263f)).coerceAtLeast(0f))


    fun safeRect():AaGeometry.Rect {
        // Largest 2:1 rectangle whose top corners lie inside the estimated arc.
        var low=0f; var high=518f
        repeat(30) {
            val w=(low+high)/2; val top=BOTTOM-w/2
            if(w/2<=halfWidth(top)) low=w else high=w
        }
        return AaGeometry.Rect((WIDTH-low)/2,BOTTOM-low/2,low,low/2)
    }

    private fun appendBand(out:MutableList<Float>,top:Float,bottom:Float,sourceTop:Float,sourceBottom:Float,
        left:Float=0f,right:Float=WIDTH,curved:Boolean=false) {
        fun vertex(y:Float,s:Float,u:Float) {
            val x=if(curved) 263f+(u*2-1)*halfWidth(y) else left+(right-left)*u
            out.add(x/WIDTH*2-1); out.add(1-y/HEIGHT*2)
            out.add(u); out.add(1-s/480f)
        }
        // Subdivide horizontally too: two full-width triangles per row produce
        // alternating shear in text when the adjacent arc widths differ.
        val rows=if(curved) 96 else 1
        val columns=if(curved) 32 else 1
        for(row in 0 until rows) {
            val t0=row.toFloat()/rows; val t1=(row+1f)/rows
            val y0=top+(bottom-top)*t0; val y1=top+(bottom-top)*t1
            val s0=sourceTop+(sourceBottom-sourceTop)*t0; val s1=sourceTop+(sourceBottom-sourceTop)*t1
            for(column in 0 until columns) {
                val u0=column.toFloat()/columns; val u1=(column+1f)/columns
                vertex(y0,s0,u0); vertex(y1,s1,u0); vertex(y0,s0,u1)
                vertex(y0,s0,u1); vertex(y1,s1,u0); vertex(y1,s1,u1)
            }
        }
    }
    fun mesh(mode:PanelLayout,distortion:Int=100,positionY:Int=0,sizeX:Int=100,sizeY:Int=100,
        style:CurveStyle=CurveStyle.LATERAL,positionX:Int=0):FloatArray {
        val out=ArrayList<Float>()
        when(mode) {
            PanelLayout.ORIGINAL -> appendBand(out,0f,300f,0f,480f,13f,513f)
            PanelLayout.FIT -> safeRect().let { appendBand(out,it.top,it.top+it.height,0f,400f,it.left,it.left+it.width) }
            PanelLayout.FILL -> appendBand(out,0f,300f,0f,400f)
            PanelLayout.CURVED -> appendBand(out,CURVE_TOP,BOTTOM,0f,400f,curved=true)
        }
        val mesh=out.toFloatArray()
        if(mode==PanelLayout.CURVED && style!=CurveStyle.LATERAL) {
            val width=halfWidth(BOTTOM)*2
            for(i in mesh.indices step 4) {
                val x=(WIDTH-width)/2+mesh[i+2]*width
                val top=263f-kotlin.math.sqrt((263f*263f-(x-263f)*(x-263f)).coerceAtLeast(0f))
                val row=((1-mesh[i+3])*480/400).coerceIn(0f,1f)
                val targetX=x/WIDTH*2-1
                val targetY=1-(top+(BOTTOM-top)*row)/HEIGHT*2
                val blend=if(style==CurveStyle.ARC) 1f else .5f
                mesh[i]+=(targetX-mesh[i])*blend
                mesh[i+1]+=(targetY-mesh[i+1])*blend
            }
        }
        if(mode==PanelLayout.CURVED && distortion<100) {
            val amount=distortion.coerceIn(0,100)/100f
            val rect=safeRect()
            for(i in mesh.indices step 4) {
                val flatX=(rect.left+rect.width*mesh[i+2])/WIDTH*2-1
                val sourceRow=(1-mesh[i+3])*480/400
                val flatY=1-(rect.top+rect.height*sourceRow)/HEIGHT*2
                mesh[i]=flatX+(mesh[i]-flatX)*amount
                mesh[i+1]=flatY+(mesh[i+1]-flatY)*amount
            }
        }
        if(mode==PanelLayout.CURVED) {
            val ys=(1 until mesh.size step 4).map { mesh[it] }
            val centerY=(ys.min()+ys.max())/2
            for(i in mesh.indices step 4) {
                mesh[i]=mesh[i]*sizeX.coerceIn(50,150)/100f+positionX.coerceIn(-526,526)/263f
                mesh[i+1]=centerY+(mesh[i+1]-centerY)*sizeY.coerceIn(50,150)/100f-positionY.coerceIn(-300,300)/150f
            }
        }
        return mesh
    }
    fun logoRect(aspect:Float):AaGeometry.Rect {
        val bottom=safeRect().top-8
        var low=0f; var high=(bottom-8).coerceAtLeast(0f)
        repeat(24) {
            val height=(low+high)/2
            val top=(8+bottom-height)/2
            if(height*aspect/2<=halfWidth(top)-8) low=height else high=height
        }
        return AaGeometry.Rect((WIDTH-low*aspect)/2,(8+bottom-low)/2,low*aspect,low)
    }
    fun logoMesh(aspect:Float,size:Int=100,positionX:Int=0,positionY:Int=0):FloatArray=ArrayList<Float>().also { out ->
        val r=logoRect(aspect); val scale=size.coerceIn(25,200)/100f
        val left=r.left+r.width*(1-scale)/2+positionX.coerceIn(-526,526)
        val top=r.top+r.height*(1-scale)/2+positionY.coerceIn(-300,300)
        appendBand(out,top,top+r.height*scale,0f,480f,left,left+r.width*scale)
    }.toFloatArray()
    fun bounds(mesh:FloatArray):AaGeometry.Rect {
        var left=Float.POSITIVE_INFINITY; var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY; var bottom=Float.NEGATIVE_INFINITY
        for(i in mesh.indices step 4) {
            val x=(mesh[i]+1)*WIDTH/2; val y=(1-mesh[i+1])*HEIGHT/2
            left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y)
        }
        return AaGeometry.Rect(left,top,right-left,bottom-top)
    }
    fun barMesh(progress:Float):FloatArray = ArrayList<Float>().also {
        val top=HEIGHT-50f*progress.coerceIn(0f,1f)
        appendBand(it,top,top+50,400f,480f,4f,522f)
    }.toFloatArray()
}

/** GPU-thread owner. Repeated presses extend visibility, including while sliding out. */
class TemporaryBar {
    companion object { const val SECONDS="bar-seconds"; const val TOGGLE="bar-toggle"; const val DEFAULT_SECONDS=5 }
    private var started:Long?=null
    private var from=0f
    private var target=1f
    private var seconds=DEFAULT_SECONDS
    private var toggle=false
    fun configure(duration:Int,toggleMode:Boolean) { seconds=duration.coerceIn(1,30); toggle=toggleMode; clear() }
    fun show(now:Long) {
        from=progress(now)
        target=if(toggle && started!=null && target==1f) 0f else 1f
        started=now
    }
    fun clear() { started=null; from=0f; target=1f }
    fun progress(now:Long):Float {
        val start=started ?: return 0f
        val elapsed=(now-start).coerceAtLeast(0)
        fun ease(v:Float)=v*v*(3-2*v)
        val end=250L+seconds*1000L
        return when {
            elapsed<250 -> from+(target-from)*ease(elapsed/250f)
            toggle -> target
            elapsed<end -> 1f
            elapsed<end+250 -> 1-ease((elapsed-end)/250f)
            else -> 0f
        }
    }
}
