package com.example.opendash.auto

import android.app.Activity
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.TextView
import kotlinx.coroutines.*

/** Explicit, reversible screen-darkening fallback. This does NOT lock or power off the display. */
class BlackScreenActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer:Job?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val detector=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:MotionEvent)=true
            override fun onDoubleTap(e:MotionEvent):Boolean { finish(); return true }
        })
        val content=TextView(this).apply {
            text="Tela preta · celular desbloqueado\nToque duas vezes para voltar"
            contentDescription="Voltar ao Rota Auto"
            gravity=Gravity.CENTER; textSize=16f; setTextColor(Color.DKGRAY); setBackgroundColor(Color.BLACK)
            setOnTouchListener { _,event -> detector.onTouchEvent(event) }
            setOnClickListener { finish() } // accessibility activation
        }
        setContentView(content); UiLanguage.apply(content)
        content.postDelayed({ content.text="" },3000)
        if(Build.VERSION.SDK_INT>=30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply { screenBrightness=0f }
        observer=scope.launch { AutoBridgeService.state.collect { if(!it.active) finish() } }
    }
    override fun onStop() {
        observer?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply { screenBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
