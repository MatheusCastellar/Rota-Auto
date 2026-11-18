package com.example.opendash.auto

import android.app.*
import android.content.*
import android.graphics.Color
import android.view.*
import android.widget.*

internal class MenuControlsEditor(
    private val activity:Activity,
    private val prefs:SharedPreferences,
    private val chooseApp:((CarApplications.App)->Unit)->Unit,
    private val feedback:(String)->Unit,
    private val decorate:(View)->Unit
):LinearLayout(activity) {
    private var inside=false
    private var selected=MenuDirection.UP
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key==MenuControls.ACTIVE || key?.startsWith("profile-name.menu.")==true) rebuild() }
    init { orientation=VERTICAL; rebuild() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(listener) }
    override fun onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onDetachedFromWindow() }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun tr(s:String)=UiLanguage.text(context,s)
    private fun note(s:String)=TextView(context).apply { text=tr(s); textSize=13f; setTextColor(Color.parseColor("#A9BEB0")); setPadding(0,dp(8),0,dp(8)) }
    private fun button(label:String,action:()->Unit)=Button(context).apply { text=tr(label); isAllCaps=false; setOnClickListener { action() } }
    private fun selector(label:String,choices:List<String>,position:Int,changed:(Int)->Unit) {
        addView(note(label))
        addView(Spinner(context).apply {
            contentDescription=tr(label)
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,choices.map(::tr))
            setSelection(position,false)
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?) {}
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,i:Int,id:Long) { changed(i) }
            }
        },LayoutParams(-1,dp(52)))
    }
    private fun rebuild() {
        removeAllViews()
        addView(note("Ao fechar, volta ao Perfil 1. Cada toque reinicia o tempo."))
        val durationLabel=note("")
        fun updateTime(seconds:Int) { durationLabel.text=tr("Tempo do menu")+": $seconds s" }
        updateTime((MenuControls.duration(prefs)/1000).toInt()); addView(durationLabel)
        addView(SeekBar(context).apply {
            max=12; progress=(MenuControls.duration(prefs)/1000).toInt()-3; contentDescription=tr("Tempo do menu")
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,n:Int,user:Boolean) { updateTime(n+3); if(user) prefs.edit().putInt(MenuControls.SECONDS,n+3).apply() }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        },LayoutParams(-1,dp(44)))
        addView(LinearLayout(context).apply {
            for((value,label) in listOf(false to "Menu fechado",true to "Menu aberto")) {
                addView(button(label) { inside=value; rebuild() }.apply { isSelected=inside==value },LayoutParams(0,dp(48),1f).apply { if(value) marginStart=dp(8) })
            }
        })
        val profile=if(inside) MenuControls.active(prefs) else 0
        if(inside) {
            addView(note("Três perfis do menu. Trocar perfil mantém o círculo aberto."))
            addView(LinearLayout(context).apply {
                for(p in 1..3) addView(button(ProfileNames.label(context,true,p)) { if(MenuControls.active(prefs)!=p) prefs.edit().putInt(MenuControls.ACTIVE,p).apply() }
                    .apply { text=ProfileNames.label(context,true,p); setTag(com.example.opendash.R.id.profile_name_literal,"profile-name"); isSelected=profile==p; tag="menu-profile"; textSize=12f; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END },LayoutParams(0,dp(48),1f).apply { if(p>1) marginStart=dp(6) })
            })
            addView(button("Renomear perfil") { ProfileNames.edit(activity,true,profile) },LayoutParams(-1,dp(48)))
        } else addView(note("Comandos únicos: não mudam ao trocar de perfil."))
        val fixed=if(inside) MenuControls.cycle(prefs) else MenuControls.opener(prefs)
        selector(if(inside) "Trocar perfil com" else "Abrir menu com",MenuDirection.entries.map { it.label },fixed.ordinal) { position ->
            val choice=MenuDirection.entries[position]
            val current=if(inside) MenuControls.cycle(prefs) else MenuControls.opener(prefs)
            if(choice!=current) { prefs.edit().putString(if(inside) MenuControls.CYCLE else MenuControls.OPENER,choice.name).apply(); rebuild() }
        }
        addView(note("Selecione um botão"))
        val grid=LinearLayout(context).apply { orientation=VERTICAL }
        for(group in MenuDirection.entries.chunked(3)) grid.addView(LinearLayout(context).apply {
            tag="control-row"
            for((i,d) in group.withIndex()) {
                val b=MenuControls.binding(prefs,profile,d)
                val action=if(d==fixed) tr(if(inside) "Trocar perfil" else "Abrir menu") else CommandNames.short(b.command,UiLanguage.selected(context),
                    if(b.command==BikeCommand.OPEN_OTHER) CarApplications.label(context,b.appPackage) ?: b.appPackage?.substringAfterLast('.') else null)
                addView(button("${d.symbol} ${tr(d.label)}\n$action") { selected=d; rebuild() }.apply {
                    isSelected=d==selected; tag="control-tile"; textSize=12f; maxLines=3
                },LayoutParams(0,dp(72),1f).apply { if(i>0) marginStart=dp(8) })
            }
            repeat(3-group.size) { addView(View(context),LayoutParams(0,1,1f).apply { marginStart=dp(8) }) }
        },LayoutParams(-1,-2).apply { topMargin=dp(8) })
        addView(grid)
        addView(note("${selected.symbol} ${tr(selected.label)}"))
        if(selected==fixed) addView(note(if(inside) "Reservado para trocar entre os três perfis." else "Reservado para abrir o menu. O primeiro toque não executa a opção de dentro."))
        else {
            val direction=selected
            val binding=MenuControls.binding(prefs,profile,direction)
            selector("Ação",MenuControls.commands.map { it.label },MenuControls.commands.indexOf(binding.command)) { i ->
                val command=MenuControls.commands[i]
                if(MenuControls.binding(prefs,profile,direction).command!=command) {
                    prefs.edit().putString(MenuControls.key(profile,direction),command.name).apply(); rebuild()
                }
            }
            if(binding.command==BikeCommand.OPEN_OTHER) {
                val app=CarApplications.label(context,binding.appPackage) ?: tr(if(binding.appPackage==null) "Escolher aplicativo" else "Não instalado · escolher outro")
                addView(button(tr("Aplicativo:")+" "+app) { chooseApp {
                    prefs.edit().putString(MenuControls.appKey(profile,direction),it.packageName).apply(); rebuild()
                } },LayoutParams(-1,dp(52)))
                addView(button("Testar aplicativo") {
                    if(!AutoBridgeService.state.value.active) feedback("Inicie o Android Auto para testar o aplicativo.")
                    else AutoBridgeService.testApplication(MenuControls.binding(prefs,profile,direction).appPackage)
                },LayoutParams(-1,dp(48)))
            }
        }
        addView(button("Testar ${selected.label}") {
            if(!AutoBridgeService.state.value.active) feedback("Inicie o Android Auto para testar o aplicativo.")
            else AutoBridgeService.testControl(selected.action)
        },LayoutParams(-1,dp(48)))
        if(inside) addView(button("Prévia dos comandos") {
            val bitmap=ProfilePopup.bitmap(MenuControls.popup(context,profile))
            val image=ImageView(context).apply { setImageBitmap(bitmap); adjustViewBounds=true }
            AlertDialog.Builder(activity).setTitle(ProfileNames.label(context,true,profile)).setView(image).setPositiveButton(tr("Fechar"),null).create().apply {
                setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() }; show()
            }
        },LayoutParams(-1,dp(48)))
        addView(note("Testar usa o estado atual do menu na moto."))
        decorate(this); UiLanguage.apply(this)
    }
}
