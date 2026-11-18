package com.example.opendash.auto

import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.*
import android.opengl.*
import android.os.*
import android.view.Surface
import com.example.opendash.dash.video.DashEncoder
import java.nio.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Decode AA frames, fit the full image on the GPU, encode at the selected dash frame rate.
 * The EGL pbuffer belongs to the service; neither the Activity nor a lit screen is required.
 */
class AaVideoBridge(private val encoded:(ByteArray,Long)->Unit,private val info:(String)->Unit,private val failed:(String)->Unit,
    private var profile:VideoProfile=VideoProfile.forFps(VideoProfile.DEFAULT_FPS).copy(automatic=true),initialLayout:PanelLayout=PanelLayout.ORIGINAL):AutoCloseable {
    private data class Chunk(val data:ByteArray,val pts:Long,val config:Boolean,val arrived:Long)
    // Apply back-pressure, never discard interdependent compressed P frames.
    private val queue=ArrayBlockingQueue<Chunk>(3)
    @Volatile var inputWaitMs=0L
        private set
    @Volatile var encodeMs=0L
        private set
    private var encodedPtsUs=0L
    private var surfacePending=false
    private val thread=HandlerThread("aa-gpu").apply { start() }
    private val handler=Handler(thread.looper)
    @Volatile private var closed=false
    val received=AtomicLong()
    val transmitted=AtomicLong()
    private var display:EGLDisplay=EGL14.EGL_NO_DISPLAY
    private var context:EGLContext=EGL14.EGL_NO_CONTEXT
    private var config:EGLConfig?=null
    private var parking:EGLSurface=EGL14.EGL_NO_SURFACE
    private var target:EGLSurface=EGL14.EGL_NO_SURFACE
    private var preview:EGLSurface=EGL14.EGL_NO_SURFACE
    private var previewWidth=0; private var previewHeight=0
    private var decoder:MediaCodec?=null
    private var decoderSurface:Surface?=null
    private var texture:SurfaceTexture?=null
    private var encoder:DashEncoder?=null
    private var textureId=0
    private var program=0
    private var statusProgram=0
    private var statusTexture=0
    private var logoTexture=0
    private var widgetTexture=0
    private var profileTexture=0
    private var profileVertices:FloatBuffer?=null
    private var profileShownAt=0L
    private val dismissProfile=Runnable { profileVertices=null; cadence.changed() }
    private var widgetVertices:FloatBuffer?=null
    private var widgetFront=true
    private var widgetSnapshot:Pair<WidgetState?,WidgetStyle>?=null
    private var logoVertices:FloatBuffer?=null
    private var blurArea=BlurArea.SIDES
    private var logoAspect:Float?=null
    private var logoSize=100; private var logoX=0; private var logoY=0
    private var showingStatus=false
    private var statusUntilFrame=false
    private val released=CountDownLatch(1)
    private var haveFrame=false
    private val cadence=VideoCadence(profile.intervalMs)
    private var pending:Chunk?=null
    private val transform=FloatArray(16)
    private var layout=initialLayout
    private var simulatePreview=false
    private val temporaryBar=TemporaryBar()
    private var barProgress=0f
    private val meshes=PanelLayout.entries.associateWith { mode -> floatBuffer(PanelGeometry.mesh(mode,PanelLayout.DEFAULT_DISTORTION)) }.toMutableMap()
    private val barVertices=floatBuffer(FloatArray(24))
    private fun floatBuffer(values:FloatArray)=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    // Coalesce slider updates while the GPU is busy; never queue obsolete bitrates.
    @Volatile private var requestedProfile=profile
    private val applyProfile=Runnable {
        if(!closed) {
            val next=requestedProfile
            var appliedBitrate=profile.bitrate
            var rejected=false
            if(next.bitrate!=profile.bitrate) {
                try { encoder?.setBitrate(next.bitrate); appliedBitrate=next.bitrate }
                catch(_:Exception) { rejected=true }
            }
            profile=next.copy(bitrate=appliedBitrate)
            cadence.setInterval(profile.intervalMs)
            info("Vídeo ao vivo · até "+profile.fps+" fps · "+(if(profile.automatic) "automático · alvo " else "")+(profile.bitrate/1000)+" kb/s · "+encoder?.rateModeLabel+
                if(rejected) " · celular recusou a nova taxa ao vivo; reinicie para aplicá-la" else "")
        }
    }
    fun setProfile(value:VideoProfile) {
        requestedProfile=value
        handler.removeCallbacks(applyProfile)
        handler.post(applyProfile)
    }
    fun setLayout(mode:PanelLayout) { handler.post { layout=mode; temporaryBar.clear(); barProgress=0f; cadence.changed() } }
    private var customBounds=PanelGeometry.bounds(PanelGeometry.mesh(PanelLayout.CURVED,PanelLayout.DEFAULT_DISTORTION))
    fun setDistortion(percent:Int,positionY:Int=0,sizeX:Int=100,sizeY:Int=100,style:CurveStyle=CurveStyle.LATERAL,positionX:Int=0) { handler.post {
        val mesh=PanelGeometry.mesh(PanelLayout.CURVED,percent.coerceIn(0,100),positionY,sizeX,sizeY,style,positionX)
        meshes[PanelLayout.CURVED]=floatBuffer(mesh); customBounds=PanelGeometry.bounds(mesh)
        cadence.changed()
    } }
    fun setBlur(area:BlurArea) { handler.post { blurArea=area; cadence.changed() } }
    fun setLogoPlacement(size:Int,x:Int,y:Int) { handler.post {
        logoSize=size; logoX=x; logoY=y; updateLogoMesh(); cadence.changed()
    } }
    private fun updateLogoMesh() { logoVertices=logoAspect?.let { floatBuffer(PanelGeometry.logoMesh(it,logoSize,logoX,logoY)) } }
    fun setLogo(logo:PanelLogo,resources:android.content.res.Resources,background:OverlayBackground=OverlayBackground.TRANSPARENT) { handler.post {
        if(closed) return@post
        try {
            current(parking)
            val bitmap=logo.bitmap(resources,background)
            if(bitmap==null) { logoAspect=null; logoVertices=null } else {
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,logoTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
                    logoAspect=bitmap.width.toFloat()/bitmap.height; updateLogoMesh()
                } finally { bitmap.recycle() }
            }
            cadence.changed()
        } catch(_:Exception) { logoVertices=null; info("Não foi possível carregar o logo") }
    } }
    internal fun setWidget(state:WidgetState?,style:WidgetStyle) { handler.post {
        if(closed || config==null || widgetSnapshot==(state to style)) return@post
        try {
            current(parking)
            widgetFront=style.front
            if(!style.enabled || state==null) widgetVertices=null else {
                    val bitmap=PanelWidget.bitmap(state,style.background,style.layout,style.height)
                try {
                    if(widgetTexture==0) { val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); widgetTexture=ids[0] }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,widgetTexture)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
                    widgetVertices=floatBuffer(PanelWidget.mesh(style.size,style.x,style.y,style.layout,style.height))
                } finally { bitmap.recycle() }
            }
            widgetSnapshot=state to style; cadence.changed()
        } catch(_:Exception) { widgetVertices=null }
    } }
    internal fun keepProfilePopup(durationMs:Long) { handler.post {
        if(profileVertices!=null) {
            handler.removeCallbacks(dismissProfile)
            profileShownAt=SystemClock.elapsedRealtime()
            handler.postDelayed(dismissProfile,durationMs.coerceIn(500L,15_000L))
        }
    } }
    internal fun setProfilePopupDuration(durationMs:Long) { handler.post {
        if(profileVertices!=null) {
            handler.removeCallbacks(dismissProfile)
            val remaining=durationMs.coerceIn(500L,10_000L)-(SystemClock.elapsedRealtime()-profileShownAt)
            if(remaining<=0) dismissProfile.run() else handler.postDelayed(dismissProfile,remaining)
        }
    } }
    internal fun showProfilePopup(state:ProfilePopupState?) { handler.post {
        handler.removeCallbacks(dismissProfile)
        profileVertices=null
        if(!closed && config!=null && state!=null) {
            try {
                current(parking)
                val bitmap=ProfilePopup.bitmap(state)
                try {
                    if(profileTexture==0) { val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); profileTexture=ids[0] }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,profileTexture)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
                    profileVertices=floatBuffer(ProfilePopup.mesh())
                    profileShownAt=SystemClock.elapsedRealtime()
                    handler.postDelayed(dismissProfile,state.durationMs.coerceIn(500L,15_000L))
                } finally { bitmap.recycle() }
            } catch(_:Exception) { profileVertices=null }
        }
        cadence.changed()
    } }
    fun simulatePreview(enabled:Boolean) { handler.post { simulatePreview=enabled; cadence.changed() } }
    fun setBarOptions(seconds:Int,toggle:Boolean) { handler.post { temporaryBar.configure(seconds,toggle); cadence.changed() } }
    fun showBar() { handler.post { if(layout.hidesBar) { temporaryBar.show(SystemClock.elapsedRealtime()); cadence.changed() } } }
    /** Occasional on-demand readback before all panel effects; never in the normal frame loop. */
    internal fun sourceFrame(callback:(AaSourceFrame?)->Unit) {
        if(closed) { callback(null); return }
        if(!handler.post {
            var result:AaSourceFrame?=null
            val framebuffer=IntArray(1); val color=IntArray(1)
            try {
                if(!closed && haveFrame && !showingStatus) {
                    current(parking)
                    GLES20.glGenTextures(1,color,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,color[0])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,400,240,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
                    GLES20.glGenFramebuffers(1,framebuffer,0); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0])
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,color[0],0)
                    check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE)
                    GLES20.glViewport(0,0,400,240); GLES20.glUseProgram(program)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"sTexture"),0)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMatrix"),1,false,transform,0)
                    for(name in listOf("uMask","uBackground","uBlur")) GLES20.glUniform1f(GLES20.glGetUniformLocation(program,name),0f)
                    val pos=GLES20.glGetAttribLocation(program,"aPos"); val uv=GLES20.glGetAttribLocation(program,"aUv")
                    vertices.position(0); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(pos)
                    vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                    val rgba=ByteBuffer.allocateDirect(400*240*4)
                    GLES20.glReadPixels(0,0,400,240,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba)
                    check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
                    val pixels=IntArray(400*240)
                    for(y in 0 until 240) for(x in 0 until 400) {
                        val i=((239-y)*400+x)*4
                        pixels[y*400+x]=(255 shl 24) or ((rgba.get(i).toInt() and 255) shl 16) or
                            ((rgba.get(i+1).toInt() and 255) shl 8) or (rgba.get(i+2).toInt() and 255)
                    }
                    result=AaSourceFrame(400,240,pixels)
                }
            } catch(_:Exception) { /* A failed shortcut must not interrupt projection. */ }
            finally {
                if(framebuffer[0]!=0) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0); GLES20.glDeleteFramebuffers(1,framebuffer,0) }
                if(color[0]!=0) GLES20.glDeleteTextures(1,color,0)
            }
            callback(result)
        }) callback(null)
    }
    private val vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f,1f,-1f,1f,0f,-1f,1f,0f,1f,1f,1f,1f,1f)); position(0)
    }
    fun start() {
        val ready=CountDownLatch(1); var error:Throwable?=null
        handler.post { try { initialize() } catch(e:Throwable) { error=e; release() } finally { ready.countDown() } }
        check(ready.await(12,TimeUnit.SECONDS)) { "Codificador demorou para iniciar" }
        error?.let { throw IllegalStateException("Não foi possível preparar vídeo: ${it.message}",it) }
        handler.post(tick)
    }
    fun submit(data:ByteArray,pts:Long,csd:Boolean) {
        check(!closed)
        require(data.size<=2*1024*1024) { "Quadro Android Auto muito grande" }
        check(queue.offer(Chunk(data,pts,csd,SystemClock.elapsedRealtime()),2,TimeUnit.SECONDS)) { "Decodificador não acompanhou o Android Auto" }
    }
    fun attach(surface:Surface?,width:Int,height:Int,after:()->Unit={}) {
        if(!handler.post {
            try {
                if(!closed && config!=null) {
                    current(parking)
                    if(preview!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,preview)
                    preview=EGL14.EGL_NO_SURFACE
                    if(surface!=null && surface.isValid && width>0 && height>0) {
                        preview=EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
                        previewWidth=width; previewHeight=height
                    }
                }
            } catch(_:Exception) { info("Prévia indisponível; transmissão independente permanece ativa") }
            finally { after() }
        }) after()
    }
    fun afterPreviewWork(after:()->Unit) { if(!handler.post { after() }) after() }
    fun waiting(resumeOnFrame:Boolean=false) {
        handler.post {
            if(closed) return@post
            try {
                current(parking)
                val bitmap=Bitmap.createBitmap(526,300,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap); canvas.drawColor(Color.rgb(10,20,15))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=24f; textAlign=Paint.Align.CENTER }
                canvas.drawText("Reconectando Android Auto",263f,135f,paint)
                paint.textSize=19f; canvas.drawText("Navegação temporariamente indisponível",263f,175f,paint)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,statusTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0); bitmap.recycle()
                showingStatus=true; statusUntilFrame=resumeOnFrame; cadence.changed()
            } catch(e:Exception) { fail(e) }
        }
    }
    private fun initialize() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version=IntArray(2); check(EGL14.eglInitialize(display,version,0,version,1))
        val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,0x3142,1,EGL14.EGL_NONE)
        val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
        check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) && count[0]>0)
        config=configs[0]; context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        parking=EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        current(parking)
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPos; attribute vec2 aUv; uniform mat4 uMatrix; varying vec2 vUv; varying vec2 vPanel; void main(){gl_Position=vec4(aPos,0.,1.); vUv=(uMatrix*vec4(aUv,0.,1.)).xy; vPanel=vec2((aPos.x+1.)*263.,(1.-aPos.y)*150.);}"))
        GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; varying vec2 vPanel; uniform samplerExternalOES sTexture; uniform float uMask; uniform float uBlack; uniform float uBackground; uniform float uBlur; uniform float uBlurTop; uniform float uBlurBottom; uniform float uBlurArea; void main(){vec2 p=(vPanel-vec2(263.,263.))/263.; if(uBackground>0.5){gl_FragColor=dot(p,p)>1.?vec4(16./255.,20./255.,17./255.,1.):vec4(0.,0.,0.,1.);return;} if(uMask>0.5 && dot(p,p)>1.) discard; if(uBlack>0.5){gl_FragColor=vec4(0.,0.,0.,1.);return;} if(uBlur>0.5){if(uBlurArea<2.5 && vPanel.y<uBlurTop) discard; if(uBlurArea<1.5 && vPanel.y>uBlurBottom) discard; vec4 c=vec4(0.); for(int x=-4;x<=4;x++){for(int y=-4;y<=4;y++){float w=(5.-abs(float(x)))*(5.-abs(float(y))); vec2 uv=clamp(vUv+vec2(float(x)*0.0025,float(y)*0.004167),vec2(0.),vec2(1.)); c+=texture2D(sTexture,uv)*w;}} gl_FragColor=vec4(c.rgb/625.*0.65,1.);return;} gl_FragColor=texture2D(sTexture,vUv);}"))
        GLES20.glLinkProgram(program); val linked=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0); check(linked[0]!=0)
        statusProgram=GLES20.glCreateProgram()
        GLES20.glAttachShader(statusProgram,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; varying vec2 vPanel; void main(){gl_Position=vec4(aPos,0.,1.);vUv=vec2(aUv.x,1.-aUv.y);vPanel=vec2((aPos.x+1.)*263.,(1.-aPos.y)*150.);}"))
        GLES20.glAttachShader(statusProgram,shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 vUv; uniform sampler2D sTexture; uniform float uLogo; uniform float uLogoMask; varying vec2 vPanel; void main(){vec2 p=(vPanel-vec2(263.,263.))/263.; if(uLogoMask>0.5 && dot(p,p)>1.) discard; vec4 c=texture2D(sTexture,vUv); gl_FragColor=uLogo>0.5?vec4(1.,1.,1.,c.a):c;}"))
        GLES20.glLinkProgram(statusProgram); GLES20.glGetProgramiv(statusProgram,GLES20.GL_LINK_STATUS,linked,0); check(linked[0]!=0)
        val statusIds=IntArray(1); GLES20.glGenTextures(1,statusIds,0); statusTexture=statusIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,statusTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val logoIds=IntArray(1); GLES20.glGenTextures(1,logoIds,0); logoTexture=logoIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,logoTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); textureId=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        texture=SurfaceTexture(textureId).apply {
            setDefaultBufferSize(800,480)
            setOnFrameAvailableListener({
                if(!closed) try {
                    current(parking); updateTexImage(); getTransformMatrix(transform); haveFrame=true; received.incrementAndGet()
                    surfacePending=false
                    if(statusUntilFrame) { showingStatus=false; statusUntilFrame=false }
                    if(!showingStatus) cadence.changed()
                } catch(e:Exception) { fail(e) }
            },handler)
        }
        decoderSurface=Surface(texture)
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,800,480).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024)
            setInteger(MediaFormat.KEY_PRIORITY,0)
        }
        val codecInfo=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true) } &&
                runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }.getOrDefault(false)
        } ?: error("Não há decodificador H.264 por hardware compatível")
        if(Build.VERSION.SDK_INT>=30 && codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) format.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
        decoder=MediaCodec.createByCodecName(codecInfo.name).apply { configure(format,decoderSurface,null,0); start() }
        createEncoder(profile)
        info("${codecInfo.name} → ${encoder!!.encoderName} · 526×300 / até ${profile.fps} fps · ${encoder!!.rateModeLabel}")
    }
    private fun createEncoder(next:VideoProfile) {
        val enc=DashEncoder { data,_ -> encoded(data,encodedPtsUs) }
        encoder=enc
        try {
            enc.onPresentationTimeUs={ pts ->
                encodedPtsUs=pts
                encodeMs=(SystemClock.elapsedRealtimeNanos()/1000-pts).coerceAtLeast(0)/1000
            }
            // Reserve the highest supported feed rate; cadence controls actual frames at runtime.
            enc.prepare(12,next.bitrate)
            target=EGL14.eglCreateWindowSurface(display,config,enc.surfaceForGl(),intArrayOf(EGL14.EGL_NONE),0)
            check(target!=EGL14.EGL_NO_SURFACE)
        } catch(e:Exception) { enc.release(); encoder=null; throw e }
    }
    private val tick=object:Runnable {
        override fun run() {
            if(closed) return
            try {
                val dec=decoder ?: return
                // Feed the small bounded queue without a separate 4 ms wait for each NAL.
                for(attempt in 0 until 4) {
                    if(pending==null) pending=queue.poll()
                    val chunk=pending ?: break
                    val index=dec.dequeueInputBuffer(0)
                    if(index>=0) {
                        val b=dec.getInputBuffer(index)!!; b.clear(); require(b.remaining()>=chunk.data.size); b.put(chunk.data)
                        dec.queueInputBuffer(index,0,chunk.data.size,chunk.pts,if(chunk.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                        inputWaitMs=SystemClock.elapsedRealtime()-chunk.arrived; pending=null
                    } else break
                }
                val buffer=MediaCodec.BufferInfo()
                // Decode every reference frame, but only present the newest available image.
                // Releasing stale *decoded* buffers is safe; dropping compressed P frames is not.
                var latest=-1
                for(attempt in 0 until 8) {
                    val index=dec.dequeueOutputBuffer(buffer,0)
                    if(index>=0) {
                        if(latest>=0) dec.releaseOutputBuffer(latest,false)
                        latest=index
                    } else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        info("Android Auto: ${dec.outputFormat.getInteger(MediaFormat.KEY_WIDTH)}×${dec.outputFormat.getInteger(MediaFormat.KEY_HEIGHT)} → 526×300")
                    } else break
                }
                if(latest>=0) { surfacePending=true; dec.releaseOutputBuffer(latest,true) }
                val now=SystemClock.elapsedRealtime()
                val nextBar=if(layout.hidesBar) temporaryBar.progress(now) else 0f
                if(nextBar!=barProgress) { barProgress=nextBar; cadence.changed() }
                if((haveFrame || showingStatus) && (!surfacePending || showingStatus) && cadence.take(now)) {
                    draw(target,526,300)
                    EGLExt.eglPresentationTimeANDROID(display,target,SystemClock.elapsedRealtimeNanos())
                    check(EGL14.eglSwapBuffers(display,target)); transmitted.incrementAndGet()
                    // Send any ready video before drawing the phone's optional preview.
                    encoder?.drain(0)
                    renderPreview()
                }
                // Drain every tick: encoded buffers should not wait until the next video frame.
                encoder?.drain(0)
                handler.postDelayed(this,4)
            } catch(e:Exception) { fail(e) }
        }
    }
    private fun renderPreview() {
        if(preview==EGL14.EGL_NO_SURFACE) return
        try { draw(preview,previewWidth,previewHeight); check(EGL14.eglSwapBuffers(display,preview)) }
        catch(_:Exception) { current(parking); EGL14.eglDestroySurface(display,preview); preview=EGL14.EGL_NO_SURFACE }
    }
    private fun draw(surface:EGLSurface,width:Int,height:Int) {
        val simulation=surface==preview && simulatePreview
        current(surface); GLES20.glViewport(0,0,width,height)
        if(simulation) GLES20.glClearColor(16f/255,20f/255,17f/255,1f) else GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scale=minOf(width/526f,height/300f)
        val r=AaGeometry.Rect((width-526*scale)/2,(height-300*scale)/2,526*scale,300*scale)
        GLES20.glViewport(r.left.toInt(),r.top.toInt(),r.width.toInt(),r.height.toInt())
        val activeProgram=if(showingStatus) statusProgram else program
        GLES20.glUseProgram(activeProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if(showingStatus) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES,if(showingStatus) statusTexture else textureId)
        val pos=GLES20.glGetAttribLocation(activeProgram,"aPos"); val uv=GLES20.glGetAttribLocation(activeProgram,"aUv")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgram,"sTexture"),0)
        fun drawMesh(buffer:FloatBuffer,strip:Boolean=false) {
            buffer.position(0); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,buffer); GLES20.glEnableVertexAttribArray(pos)
            buffer.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,buffer); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(if(strip) GLES20.GL_TRIANGLE_STRIP else GLES20.GL_TRIANGLES,0,buffer.capacity()/4)
        }
        if(showingStatus) {
            GLES20.glUniform1f(GLES20.glGetUniformLocation(statusProgram,"uLogo"),0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(statusProgram,"uLogoMask"),0f)
            drawMesh(vertices,true)
        }
        else {
            val panel=surface==target || simulatePreview
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(activeProgram,"uMatrix"),1,false,transform,0)
            val background=GLES20.glGetUniformLocation(activeProgram,"uBackground")
            if(simulation) {
                GLES20.glUniform1f(background,1f)
                drawMesh(vertices,true)
            }
            GLES20.glUniform1f(background,0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(activeProgram,"uMask"),if(simulation) 1f else 0f)
            val blur=GLES20.glGetUniformLocation(program,"uBlur")
            if(panel && layout in listOf(PanelLayout.FIT,PanelLayout.CURVED) && blurArea!=BlurArea.OFF) {
                GLES20.glUniform1f(blur,1f)
                val bounds=if(layout==PanelLayout.FIT) PanelGeometry.safeRect() else customBounds
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uBlurTop"),bounds.top)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uBlurBottom"),bounds.top+bounds.height)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uBlurArea"),blurArea.ordinal.toFloat())
                drawMesh(meshes.getValue(PanelLayout.FILL))
            }
            GLES20.glUniform1f(blur,0f)
            drawMesh(meshes.getValue(if(panel) layout else PanelLayout.ORIGINAL))
            if(panel && barProgress>0f) {
                val black=GLES20.glGetUniformLocation(program,"uBlack")
                GLES20.glUniform1f(black,1f)
                barVertices.position(0); barVertices.put(PanelGeometry.barBackgroundMesh(barProgress)); barVertices.position(0)
                drawMesh(barVertices)
                GLES20.glUniform1f(black,0f)
                barVertices.position(0); barVertices.put(PanelGeometry.barMesh(barProgress)); barVertices.position(0)
                drawMesh(barVertices)
            }
            fun overlay(id:Int,mesh:FloatBuffer?) {
                if(mesh==null) return
                GLES20.glUseProgram(statusProgram)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(statusProgram,"sTexture"),0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(statusProgram,"uLogo"),0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(statusProgram,"uLogoMask"),if(simulation) 1f else 0f)
                val lp=GLES20.glGetAttribLocation(statusProgram,"aPos"); val lu=GLES20.glGetAttribLocation(statusProgram,"aUv")
                mesh.position(0); GLES20.glVertexAttribPointer(lp,2,GLES20.GL_FLOAT,false,16,mesh); GLES20.glEnableVertexAttribArray(lp)
                mesh.position(2); GLES20.glVertexAttribPointer(lu,2,GLES20.GL_FLOAT,false,16,mesh); GLES20.glEnableVertexAttribArray(lu)
                GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,mesh.capacity()/4); GLES20.glDisable(GLES20.GL_BLEND)
            }
            if(panel) {
                if(!widgetFront) overlay(widgetTexture,widgetVertices)
                if(layout in listOf(PanelLayout.FIT,PanelLayout.CURVED)) overlay(logoTexture,logoVertices)
                if(widgetFront) overlay(widgetTexture,widgetVertices)
                overlay(profileTexture,profileVertices)
            }
        }
    }
    private fun current(surface:EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "Falha na superfície de vídeo" } }
    private fun shader(type:Int,text:String):Int {
        val id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,text); GLES20.glCompileShader(id)
        val ok=IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { GLES20.glGetShaderInfoLog(id) }; return id
    }
    private fun fail(e:Exception) { if(!closed) { closed=true; failed("Vídeo: ${e.javaClass.simpleName} · ${e.message}"); release(); thread.quitSafely() } }
    override fun close() {
        if(closed) return
        closed=true; queue.clear()
        handler.post { release(); thread.quitSafely() }
    }
    fun closeAndWait() { close(); check(released.await(3,TimeUnit.SECONDS)) { "Vídeo anterior não encerrou a tempo" } }
    private fun release() {
        if(released.count==0L) return
        handler.removeCallbacks(tick)
        handler.removeCallbacks(dismissProfile)
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder=null
        runCatching { decoderSurface?.release() }; runCatching { texture?.release() }
        if(display!=EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT) }
            for(s in listOf(target,preview,parking)) if(s!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,s)
            EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
        encoder?.release(); encoder=null
        released.countDown()
    }
}




