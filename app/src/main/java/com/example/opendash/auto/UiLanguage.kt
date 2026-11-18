package com.example.opendash.auto

import android.app.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.*
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import org.json.JSONArray
import java.util.WeakHashMap

internal object UiLanguage {
    const val KEY="app-language"
    val codes=listOf("pt-BR","en","es","hi")
    private var catalog:Map<String,List<String>>?=null
    private val watched=WeakHashMap<TextView,Boolean>()
    fun selected(c:Context)=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).getString(KEY,"pt-BR").let { if(it in codes) it!! else "pt-BR" }
    @Synchronized private fun dictionary(c:Context):Map<String,List<String>> {
        if(catalog==null) {
            val rows=JSONArray(c.assets.open("languages.json").bufferedReader().use { it.readText() })
            catalog=(0 until rows.length()).associate { i -> val row=rows.getJSONArray(i); row.getString(0) to (0..3).map(row::getString) }
        }
        return catalog!!
    }
    fun text(c:Context,value:String):String=translate(dictionary(c),codes.indexOf(selected(c)),value)
    internal fun translate(words:Map<String,List<String>>,language:Int,value:String):String {
        if(language<=0) return value
        words[value]?.let { return it[language] }
        fun tr(s:String)=translate(words,language,s)
        if(value.contains('\n')) return value.split('\n').joinToString("\n",transform=::tr)
        Regex("^Perfil ([1234])(.*)$").matchEntire(value)?.let { return tr("Perfil")+" "+it.groupValues[1]+tr(it.groupValues[2]) }
        if(value.startsWith("Testar ")) return tr("Testar")+" "+tr(value.removePrefix("Testar "))
        if(value.contains(" · ")) return value.split(" · ").joinToString(" · ",transform=::tr)
        val colon=value.indexOf(": ")
        if(colon>0) return tr(value.substring(0,colon))+": "+tr(value.substring(colon+2))
        return value
    }
    fun apply(root:View) {
        if(root is TextView && root !is EditText && root.getTag(com.example.opendash.R.id.profile_name_literal)!="profile-name" && !watched.containsKey(root)) {
            watched[root]=true
            var changing=false
            fun update() {
                if(changing) return
                val out=text(root.context,root.text.toString())
                if(out!=root.text.toString()) {
                    changing=true
                    root.text=if(root.tag=="control-tile" && out.endsWith(text(root.context,"Compartilhado"))) SpannableString(out).apply {
                        val at=out.lastIndexOf('\n')+1
                        setSpan(ForegroundColorSpan(Color.parseColor("#FF7878")),at,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(.85f),at,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else out
                    changing=false
                }
            }
            root.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
                override fun afterTextChanged(s:Editable?) { update() }
            })
            update()
        }
        if(root is EditText) root.hint=root.hint?.let { text(root.context,it.toString()) }
        root.contentDescription=root.contentDescription?.let { text(root.context,it.toString()) }
        if(root is ViewGroup) for(i in 0 until root.childCount) apply(root.getChildAt(i))
    }
    fun choose(activity:Activity) {
        val density=activity.resources.displayMetrics.density
        fun dp(n:Int)=(n*density).toInt()
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val dialog=AlertDialog.Builder(activity).setTitle(text(activity,"Idioma / Language")).setView(list).setNegativeButton(text(activity,"Cancelar"),null).create()
        for((index,label) in listOf("Português · Brasil","English","Español","हिन्दी").withIndex()) {
            list.addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(60); isClickable=true; isFocusable=true
                contentDescription=label
                addView(ImageView(activity).apply { setImageDrawable(LanguageFlag(index)) },LinearLayout.LayoutParams(dp(42),dp(28)))
                addView(TextView(activity).apply { text=label; textSize=17f; setTextColor(Color.WHITE); setPadding(dp(16),0,0,0) })
                setOnClickListener {
                    activity.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).edit().putString(KEY,codes[index]).apply()
                    dialog.dismiss(); activity.recreate()
                }
            })
        }
        dialog.show()
    }
    fun flagButton(activity:Activity):View {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val index=codes.indexOf(selected(activity)).coerceAtLeast(0)
        return FrameLayout(activity).apply {
            tag="language-selector"; contentDescription=text(activity,"Idioma / Language"); tooltipText=contentDescription
            isClickable=true; isFocusable=true
            val shape=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(10).toFloat(); setColor(Color.parseColor("#1B2A22")) }
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF),shape,null)
            addView(ImageView(activity).apply { setImageDrawable(LanguageFlag(index)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(dp(30),dp(20),Gravity.CENTER))
            setOnClickListener { choose(activity) }
        }
    }
}

/** Small vector country flags. English is one flag split between US and UK. */
internal class LanguageFlag(private val language:Int):Drawable() {
    override fun draw(canvas:Canvas) {
        val save=canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat()); canvas.scale(bounds.width()/60f,bounds.height()/40f)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(color:Int,l:Float,t:Float,r:Float,b:Float) { p.color=color; p.style=Paint.Style.FILL; canvas.drawRect(l,t,r,b,p) }
        val red=Color.rgb(200,25,45); val blue=Color.rgb(10,35,95)
        when(language) {
            0 -> {
                rect(Color.rgb(0,145,65),0f,0f,60f,40f); p.color=Color.rgb(255,220,0)
                canvas.drawPath(Path().apply { moveTo(30f,4f); lineTo(55f,20f); lineTo(30f,36f); lineTo(5f,20f); close() },p)
                p.color=blue; canvas.drawCircle(30f,20f,11f,p); p.color=Color.WHITE; p.strokeWidth=2f; canvas.drawLine(20f,17f,40f,23f,p)
            }
            1 -> {
                val us=canvas.save(); canvas.clipRect(0f,0f,30f,40f)
                for(i in 0..12) rect(if(i%2==0) red else Color.WHITE,0f,i*40f/13,60f,(i+1)*40f/13)
                rect(blue,0f,0f,24f,22f); p.color=Color.WHITE
                for(y in 0..4) for(x in 0..4) canvas.drawCircle(3f+x*4.4f,3f+y*4f,.7f,p)
                canvas.restoreToCount(us)
                val uk=canvas.save(); canvas.clipRect(30f,0f,60f,40f); rect(blue,0f,0f,60f,40f)
                p.color=Color.WHITE; p.strokeWidth=8f; canvas.drawLine(0f,0f,60f,40f,p); canvas.drawLine(0f,40f,60f,0f,p)
                p.color=red; p.strokeWidth=3f; canvas.drawLine(0f,0f,60f,40f,p); canvas.drawLine(0f,40f,60f,0f,p)
                rect(Color.WHITE,0f,15f,60f,25f); rect(Color.WHITE,25f,0f,35f,40f)
                rect(red,0f,17f,60f,23f); rect(red,28f,0f,32f,40f); canvas.restoreToCount(uk)
            }
            2 -> { rect(red,0f,0f,60f,40f); rect(Color.rgb(255,200,0),0f,10f,60f,30f); rect(red,15f,16f,21f,25f) }
            3 -> {
                rect(Color.rgb(255,153,51),0f,0f,60f,13.33f); rect(Color.WHITE,0f,13.33f,60f,26.67f); rect(Color.rgb(19,136,8),0f,26.67f,60f,40f)
                p.color=blue; p.style=Paint.Style.STROKE; p.strokeWidth=.7f; canvas.drawCircle(30f,20f,5.4f,p)
                for(i in 0..23) { val a=i*Math.PI/12; canvas.drawLine(30f,20f,30+5f*kotlin.math.cos(a).toFloat(),20+5f*kotlin.math.sin(a).toFloat(),p) }
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int) {}
    override fun setColorFilter(filter:ColorFilter?) {}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
