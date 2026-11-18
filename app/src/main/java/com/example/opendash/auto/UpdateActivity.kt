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

class UpdateActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var status:TextView
    private lateinit var action:Button
    private var release:AppRelease?=null
    private var ready:File?=null
    private var busy=false
    private fun t(s:String)=UiLanguage.text(this,s)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(24),dp(20),dp(20)); setBackgroundColor(Color.BLACK) }
        fun label(s:String,size:Float)=TextView(this).apply { text=t(s); textSize=size; setTextColor(Color.WHITE); setPadding(0,dp(12),0,dp(12)); column.addView(this) }
        label("Atualizações",24f)
        label("Rota Auto ${BuildConfig.VERSION_NAME}",17f)
        label("Verifica novas versões no GitHub. O Android pede confirmação para instalar. Suas configurações são mantidas.",15f)
        column.addView(Switch(this).apply {
            text=t("Buscar atualizações automaticamente"); setTextColor(Color.WHITE)
            isChecked=GitHubUpdates.preferences(this@UpdateActivity).getBoolean(GitHubUpdates.AUTO,true)
            setOnCheckedChangeListener { _,v -> GitHubUpdates.preferences(this@UpdateActivity).edit().putBoolean(GitHubUpdates.AUTO,v).apply() }
        })
        status=label("Verificando atualização…",16f)
        action=Button(this).apply {
            text=t("Verificar agora"); isAllCaps=false
            setOnClickListener { if(!busy) when { ready!=null -> install(); release!=null -> download(); else -> check() } }
        }
        column.addView(action,LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(12); bottomMargin=dp(12) })
        column.addView(Button(this).apply { text=t("Voltar"); isAllCaps=false; setOnClickListener { finish() } },LinearLayout.LayoutParams(-1,dp(56)))
        setContentView(ScrollView(this).apply { addView(column) })
        val cached=File(cacheDir,"updates/update.apk")
        if(cached.isFile && runCatching { GitHubUpdates.validateApk(this,cached) }.isSuccess) {
            ready=cached; status.text=t("Atualização pronta para instalar"); action.text=t("Instalar atualização")
        } else check()
    }
    private fun check() {
        busy=true; action.isEnabled=false; status.text=t("Verificando atualização…")
        scope.launch {
            try {
                release=withContext(Dispatchers.IO) { GitHubUpdates.latest(this@UpdateActivity) }
                GitHubUpdates.preferences(this@UpdateActivity).edit().putLong("last-check",System.currentTimeMillis())
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
                ready=withContext(Dispatchers.IO) { GitHubUpdates.download(this@UpdateActivity,next) { percent ->
                    runOnUiThread { status.text=t("Baixando atualização")+": $percent%" }
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
            GitHubUpdates.validateApk(this,file)
            if(!packageManager.canRequestPackageInstalls()) {
                status.text=t("Permita instalar pelo Rota Auto e volte para tocar em Instalar atualização.")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))); return
            }
            val uri=FileProvider.getUriForFile(this,"$packageName.updates",file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_:Exception) { status.text=t("Não foi possível abrir o instalador da atualização.") }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
