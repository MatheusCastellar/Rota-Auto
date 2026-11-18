package com.example.opendash.auto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*

/** One instruction at a time; progress survives leaving Maps or rotating the activity. */
class DiagnosticActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var title:TextView
    private lateinit var instruction:TextView
    private lateinit var status:TextView
    private lateinit var note:EditText
    private lateinit var start:Button
    private lateinit var begin:Button
    private lateinit var finish:Button
    private lateinit var save:Button
    private val answers=mutableListOf<Button>()
    private val connectionButtons=mutableListOf<Button>()
    private lateinit var intro:TextView
    private var shown=-1
    private var exporting=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        GuidedDiagnostics.initialize(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(20)); setBackgroundColor(Color.rgb(9,18,13)) }
        fun label(value:String,size:Float)=TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(0,dp(8),0,dp(8)); root.addView(this) }
        fun button(value:String,action:()->Unit)=Button(this).apply {
            text=value; isAllCaps=false; setTextColor(Color.WHITE); textSize=15f
            backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(38,65,48))
            setOnClickListener { action() }
            root.addView(this,LinearLayout.LayoutParams(-1,dp(56)).apply { bottomMargin=dp(8) })
        }
        label("Diagnóstico guiado",24f)
        intro=label("Faça os testes com a moto parada. Os atalhos do app ficam bloqueados, mas os botões ainda podem mudar telas na própria moto. Você pode pular ou repetir etapas. Nada é enviado automaticamente.",14f)
        status=label("",14f)
        start=button("Iniciar diagnóstico") { GuidedDiagnostics.start(this); shown=-1; render() }
        connectionButtons+=button("Ligar Android Auto") { AutoBridgeService.command(this,AutoBridgeService.START) }
        connectionButtons+=button("Reconectar Moto") { AutoBridgeService.command(this,AutoBridgeService.BIKE) }
        button("Abrir conexão / Maps") { startActivity(Intent(this,AutoActivity::class.java)) }
        title=label("",21f); instruction=label("",16f)
        begin=button("Começar etapa") { GuidedDiagnostics.beginStep(); render() }
        note=EditText(this).apply { hint="Anotação opcional: o que apareceu?"; setHintTextColor(Color.LTGRAY); setTextColor(Color.WHITE); minLines=2; maxLines=4 }
        root.addView(note,LinearLayout.LayoutParams(-1,-2))
        note.setText(savedInstanceState?.getString("note").orEmpty())
        shown=GuidedDiagnostics.index
        for(result in listOf("Feito / apareceu como esperado","Não funcionou / não apareceu","Resultado diferente")) {
            answers+=button(result) { GuidedDiagnostics.answer(result,note.text.toString()); render() }
        }
        answers+=button("Pular etapa") { GuidedDiagnostics.answer("PULADA",note.text.toString(),skip=true); render() }.apply { tag="skip" }
        answers+=button("Repetir etapa") { GuidedDiagnostics.repeat(); render() }.apply { tag="repeat" }
        finish=button("Encerrar diagnóstico") { GuidedDiagnostics.finish(); render() }
        save=button("Salvar relatório") {
            GuidedDiagnostics.finish("ENCERRADO PARA EXPORTAÇÃO")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"RotaAuto-diagnostico-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}.txt"),801)
        }
        label("O relatório reúne etapas, respostas, conexão, pacotes da moto e navegação do Android Auto. Pode conter ruas da rota. Fica salvo no celular; use Salvar relatório para escolher a pasta. Fechar esta tela mantém a gravação. Encerrar restaura os símbolos e libera os atalhos.",13f)
        button("Voltar ao Rota Auto") { finish() }
        setContentView(ScrollView(this).apply {
            isFillViewport=true; addView(root)
            setOnApplyWindowInsetsListener { view,insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)
                insets
            }
        })
        scope.launch { while(isActive) { render(); delay(500) } }
    }
    private fun render() {
        val d=GuidedDiagnostics
        if(d.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val state=AutoBridgeService.state.value
        val nav=d.navigation
        status.text="${state.aa}\n${state.bike}\nPacotes: ${d.rx} · Botões: ${d.buttons} · Último: ${d.lastButton.ifBlank { "nenhum" }}\n"+
            "Navegação: ${if(nav.active) "ativa" else "inativa"} · Próxima: ${nav.distanceMeters?.let { "$it m" } ?: "sem dado"} · Destino: ${nav.destinationMeters?.let { "$it m" } ?: "sem dado"}"+
            if(d.error.isNotBlank()) "\n${d.error}" else ""
        start.isEnabled=!d.active && !exporting; finish.isEnabled=d.active; save.isEnabled=d.available() && !exporting
        start.visibility=if(d.active) android.view.View.GONE else android.view.View.VISIBLE
        intro.visibility=start.visibility
        connectionButtons.forEach { it.visibility=if(!d.active || d.index==0) android.view.View.VISIBLE else android.view.View.GONE }
        if(shown!=d.index) { shown=d.index; note.setText("") }
        if(d.active) {
            val step=d.steps[d.index]
            title.text="${d.index+1}/${d.steps.size} · ${step.title}"
            instruction.text=step.instruction
        } else {
            title.text=if(d.available()) "Relatório disponível" else "Pronto para começar"
            instruction.text="Conecte à moto e inicie o diagnóstico. Siga uma etapa por vez. Se o app for encerrado, salve o relatório parcial antes de começar outro."
        }
        begin.text=if(d.measuring) { if(d.remaining>0) "Gravando · ${d.remaining} s" else "Marque o resultado abaixo" } else "Começar etapa"
        begin.isEnabled=d.active && !d.measuring
        answers.forEach { it.isEnabled=d.active && (it.tag in listOf("skip","repeat") || d.measuring && d.remaining==0) }
        (answers+listOf(start,begin,finish,save)).forEach { it.alpha=if(it.isEnabled) 1f else .4f }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("note",note.text.toString()); super.onSaveInstanceState(outState) }
    @Deprecated("Uses system document picker on supported Android versions")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=801 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        exporting=true
        scope.launch {
            val success=withContext(Dispatchers.IO) { runCatching {
                checkNotNull(contentResolver.openOutputStream(uri,"wt")).use(GuidedDiagnostics::exportTo)
            }.isSuccess }
            exporting=false
            Toast.makeText(this@DiagnosticActivity,if(success) "Relatório salvo" else "Não foi possível salvar. Tente outra pasta.",Toast.LENGTH_LONG).show()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun dp(value:Int)=(resources.displayMetrics.density*value).toInt()
}
