package com.example.opendash.auto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.FileProvider
import com.example.opendash.BuildConfig
import kotlinx.coroutines.*
import java.io.File

internal class UpdatePanel(private val activity:Activity, private val scope:CoroutineScope) {
    private lateinit var status:TextView
    private lateinit var action:Button
    private var release:AppRelease?=null
    private var ready:File?=null
    private var busy=false
    private fun t(s:String)=UiLanguage.text(activity,s)
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private var opened=false
    init {
        fun label(s:String,size:Float)=TextView(activity).apply {
            text=t(s); textSize=size; setTextColor(Color.parseColor("#A3B7A9"))
            setPadding(0,0,0,dp(16)); view.addView(this)
        }
        label("Rota Auto ${BuildConfig.VERSION_NAME}",15f)
        label("Verifica novas versões no GitHub. O Android pede confirmação para instalar. Suas configurações são mantidas.",13f)
        view.addView(Switch(activity).apply {
            text=t("Buscar atualizações automaticamente"); setTextColor(Color.WHITE)
            isChecked=GitHubUpdates.preferences(activity).getBoolean(GitHubUpdates.AUTO,true)
            setOnCheckedChangeListener { _,v -> GitHubUpdates.preferences(activity).edit().putBoolean(GitHubUpdates.AUTO,v).apply() }
        })
        status=label("Verificar agora",14f).apply { setPadding(0,dp(16),0,dp(16)) }
        action=Button(activity).apply {
            text=t("Verificar agora"); isAllCaps=false
            setOnClickListener { if(!busy) when { ready!=null -> install(); release!=null -> download(); else -> check() } }
        }
        view.addView(action,LinearLayout.LayoutParams(-1,dp(56)))
    }
    fun open() {
        if(opened) return
        opened=true
        val cached=File(activity.cacheDir,"updates/update.apk")
        if(cached.isFile && runCatching { GitHubUpdates.validateApk(activity,cached) }.isSuccess) {
            ready=cached; status.text=t("Atualização pronta para instalar"); action.text=t("Instalar atualização")
        } else check()
    }
    private fun check() {
        busy=true; action.isEnabled=false; status.text=t("Verificando atualização…")
        scope.launch {
            try {
                release=withContext(Dispatchers.IO) { GitHubUpdates.latest(activity) }
                GitHubUpdates.preferences(activity).edit().putLong("last-check",System.currentTimeMillis())
                    .putString("available-version",release?.version).apply()
                status.text=release?.let { t("Atualização disponível")+": "+it.version } ?: t("Você está na versão mais recente.")
                action.text=t(if(release==null) "Verificar agora" else "Baixar atualização")
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { status.text=t("Não foi possível verificar. Confira a internet e tente novamente.") }
            finally { busy=false; action.isEnabled=true }
        }
    }
    private fun download() {
        val next=release ?: return
        busy=true; action.isEnabled=false
        scope.launch {
            try {
                ready=withContext(Dispatchers.IO) { GitHubUpdates.download(activity,next) { percent ->
                    activity.runOnUiThread { status.text=t("Baixando atualização")+": $percent%" }
                } }
                status.text=t("Atualização pronta para instalar"); action.text=t("Instalar atualização")
                // Explicit installation click keeps a download from interrupting projection.
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { status.text=t("Não foi possível validar ou baixar a atualização. Tente novamente.") }
            finally { busy=false; action.isEnabled=true }
        }
    }
    private fun install() {
        val file=ready ?: return
        try {
            GitHubUpdates.validateApk(activity,file)
            if(!activity.packageManager.canRequestPackageInstalls()) {
                status.text=t("Permita instalar pelo Rota Auto e volte para tocar em Instalar atualização.")
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}"))); return
            }
            val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.updates",file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_:Exception) { status.text=t("Não foi possível abrir o instalador da atualização.") }
    }
}
