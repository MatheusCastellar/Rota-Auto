package com.example.opendash.auto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText

internal object ProfileNames {
    fun key(menu:Boolean,profile:Int)="profile-name.${if(menu) "menu" else "traditional"}.$profile"
    val keys=listOf(false,true).flatMap { mode -> (1..3).map { key(mode,it) } }.toSet()
    fun clean(value:String)=CommandNames.fit(value,20)
    fun label(c:Context,menu:Boolean,profile:Int):String {
        val raw=c.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).getString(key(menu,profile),null)
        return raw?.let(::clean)?.takeIf { it.isNotBlank() } ?: UiLanguage.text(c,"Perfil $profile")
    }
    fun edit(a:Activity,menu:Boolean,profile:Int) {
        val prefs=a.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        val input=EditText(a).apply {
            setSingleLine(); setText(prefs.getString(key(menu,profile),""))
            hint=UiLanguage.text(a,"Perfil $profile"); selectAll()
            contentDescription=UiLanguage.text(a,"Nome do perfil")
        }
        val pad=(20*a.resources.displayMetrics.density).toInt()
        val box=android.widget.LinearLayout(a).apply { setPadding(pad,0,pad,0); addView(input,android.widget.LinearLayout.LayoutParams(-1,-2)) }
        AlertDialog.Builder(a).setTitle(UiLanguage.text(a,"Renomear perfil"))
            .setMessage(UiLanguage.text(a,"Até 20 caracteres. Deixe vazio para usar o nome padrão."))
            .setView(box).setNegativeButton(UiLanguage.text(a,"Cancelar"),null)
            .setPositiveButton(UiLanguage.text(a,"Salvar")) { _,_ ->
                val name=clean(input.text.toString())
                prefs.edit().apply { if(name.isBlank()) remove(key(menu,profile)) else putString(key(menu,profile),name) }.apply()
            }.show()
    }
}
