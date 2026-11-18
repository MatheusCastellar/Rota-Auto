package com.example.opendash

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import com.example.opendash.dash.DashConfig
import com.example.opendash.net.Internet
import com.example.opendash.util.LocationParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.example.opendash.dash.map.LocationTracker
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.Router
import org.json.JSONArray
import org.json.JSONObject

/** Original Rota Livre interface. All visible labels and permission guidance are pt-BR. */
class MainActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer: Job?=null
    private val ink=Color.rgb(16,20,17)
    private val accent=Color.rgb(185,246,101)
    private val muted=Color.rgb(167,183,170)
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var metrics: TextView
    private lateinit var guidance: TextView
    private lateinit var connect: Button
    private lateinit var destinationInput: EditText
    private lateinit var selectedText: TextView
    private lateinit var navigate: Button
    private var selected: Destination?=null
    private var pendingAction: (() -> Unit)?=null
    private var resolving=false
    private var inputRevision=0
    private lateinit var phoneMap: PhoneMapView
    private lateinit var searchResults: LinearLayout
    private lateinit var searchStatus: TextView
    private var searchJob: Job?=null
    private var previewRouteJob: Job?=null
    private var searchGeneration=0
    private var currentPosition:GeoPoint?=null
    private var positionTime=0L
    private lateinit var wallpaperPreview: ImageView
    private lateinit var wallpaperStatus: TextView
    private lateinit var wallpaperToggle: Switch
    private lateinit var chooseWallpaper: Button
    private lateinit var removeWallpaper: Button
    private var wallpaperBusy=false
    private var wallpaperPreviewJob: Job?=null
    private val prefs by lazy { getSharedPreferences("rota",MODE_PRIVATE) }
    data class Destination(val name:String,val lat:Double,val lng:Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Internet.init(this)
        window.statusBarColor=ink; window.navigationBarColor=ink
        buildUi()
        savedInstanceState?.getString("draft")?.let { destinationInput.setText(it) }
        savedInstanceState?.getString("selected")?.let { restoreDestination(it) }
        if(savedInstanceState==null) acceptShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptShare(intent) }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("draft",destinationInput.text.toString())
        selected?.let { out.putString("selected",toJson(it).toString()) }
        super.onSaveInstanceState(out)
    }
    override fun onStart() {
        super.onStart()
        observer=scope.launch { RideService.ui.collect { s ->
            status.text=s.stage; detail.text=s.message
            guidance.text=if(s.instruction.isNotBlank()) s.instruction+if(s.distance.isNotBlank()) "\nFaltam ${s.distance}" else "" else "Seu próximo caminho começa aqui."
            metrics.text="${s.gps}   •   ${s.frames} quadros\n${s.destination}"
            connect.text=if(s.active) "Parar conexão e navegação" else "Conectar à moto"
        } }
    }
    override fun onStop() { observer?.cancel(); observer=null; super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun bg(color:Int,radius:Float=20f)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius.toInt()).toFloat() }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun label(text:String,size:Float=15f,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
        this.text=text; textSize=size; setTextColor(color); if(bold) typeface=Typeface.DEFAULT_BOLD
        setPadding(0,dp(5),0,dp(5))
    }
    private fun button(text:String,primary:Boolean=false,action:()->Unit)=Button(this).apply {
        this.text=text; isAllCaps=false; textSize=16f; typeface=Typeface.DEFAULT_BOLD
        setTextColor(if(primary) ink else accent); background=bg(if(primary) accent else Color.rgb(38,48,40),14f)
        layoutParams=LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin=dp(10); bottomMargin=dp(4) }
        setOnClickListener { action() }
    }
    private fun input(hint:String)=EditText(this).apply {
        this.hint=hint; textSize=15f; setTextColor(Color.WHITE); setHintTextColor(muted)
        setPadding(dp(12),dp(12),dp(12),dp(12)); background=bg(Color.rgb(16,23,18),12f)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) }
    }
    private fun card(parent:LinearLayout,title:String,content:LinearLayout.()->Unit) {
        val box=column().apply {
            background=bg(Color.rgb(27,35,29)); setPadding(dp(18),dp(16),dp(18),dp(18))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) }
            addView(label(title,12f,accent,true)); content()
        }; parent.addView(box)
    }
    private fun buildUi() {
        val root=column().apply { setPadding(dp(20),dp(20),dp(20),dp(24)); setBackgroundColor(ink) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(20),dp(20)+insets.systemWindowInsetTop,dp(20),dp(24)+insets.systemWindowInsetBottom)
            insets
        }
        root.addView(label("ROTA LIVRE",13f,accent,true))
        root.addView(label("Para onde vamos?",30f,Color.WHITE,true))
        root.addView(label("Seu Tripper Dash, do seu jeito.\nSem cadastro • Português do Brasil",15f,muted).apply { setPadding(0,dp(5),0,dp(24)) })
        card(root,"01  /  DESTINO E MAPA") {
            addView(label("Escolha seu destino",22f,Color.WHITE,true))
            addView(label("Busque um endereço ou estabelecimento. Inclua a cidade para encontrar melhor.",14f,muted))
            destinationInput=input("Endereço, lugar, link ou coordenadas"); addView(destinationInput)
            destinationInput.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            destinationInput.isSingleLine=true
            destinationInput.setOnEditorActionListener { _, action, _ ->
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { resolveDestination(); true } else false
            }
            destinationInput.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
                override fun afterTextChanged(s:android.text.Editable?) {
                    inputRevision++; selected=null
                    searchGeneration++; searchJob?.cancel(); previewRouteJob?.cancel()
                    if(::searchResults.isInitialized) searchResults.removeAllViews()
                    if(::phoneMap.isInitialized) { phoneMap.route=emptyList(); phoneMap.marker=null; phoneMap.invalidate() }
                    if(::navigate.isInitialized) { navigate.isEnabled=false; navigate.alpha=.4f }
                    if(::selectedText.isInitialized) selectedText.text="Confira o novo destino antes de iniciar."
                }
            })
            addView(button("Buscar destino",true) { resolveDestination() })
            searchStatus=label("Mapa e busca funcionam antes de conectar à moto.",13f,muted); addView(searchStatus)
            phoneMap=PhoneMapView(this@MainActivity,scope).apply {
                layoutParams=LinearLayout.LayoutParams(-1,dp(280)).apply { topMargin=dp(12) }
                onPick={ point -> choose(Destination("Ponto escolhido no mapa",point.lat,point.lng)) }
            }; addView(phoneMap)
            val mapTools=LinearLayout(this@MainActivity)
            listOf("−" to { phoneMap.changeZoom(-1) }, "+" to { phoneMap.changeZoom(1) }, "Minha posição" to { locate { phoneMap.focus(it) } }).forEach { (title,action) ->
                mapTools.addView(button(title,action=action).apply { layoutParams=LinearLayout.LayoutParams(0,dp(50),1f) })
            }; addView(mapTools)
            addView(label("Arraste o mapa · segure um ponto para escolher · dois dedos para ampliar",12f,muted))
            addView(button("Lugares próximos · até 10 km") { nearbyDialog() })
            searchResults=column(); addView(searchResults)
            selectedText=label("Nenhum destino selecionado",14f,muted); addView(selectedText)
            addView(button("Ver rota no mapa") { previewRoute() })
            navigate=button("Iniciar navegação",true) {
                val d=selected ?: return@button
                ensureBike { withPermissions { RideService.command(this@MainActivity,RideService.DESTINATION) { putExtra("lat",d.lat); putExtra("lng",d.lng); putExtra("name",d.name) } } }
            }.apply { isEnabled=false; alpha=.4f }; addView(navigate)
            addView(button("Salvar destino nos favoritos") { saveFavorite() })
            addView(button("Abrir favoritos") { showFavorites() })
            addView(button("Encerrar rota · voltar à tela de espera") {
                if(RideService.ui.value.active) RideService.command(this@MainActivity,RideService.CLEAR)
            })
        }
        card(root,"02  /  SUA MOTO") {
            status=label("Pronto para sair",22f,Color.WHITE,true); addView(status)
            detail=label("Conecte sua moto para começar.",14f,muted); addView(detail)
            connect=button("Conectar à moto",true) {
                if(RideService.ui.value.active) RideService.command(this@MainActivity,RideService.STOP)
                else ensureBike { withPermissions { RideService.command(this@MainActivity,RideService.START) } }
            }; addView(connect)
            addView(button("Escolher outra moto · redes RE_") { chooseAnotherBike() })
            addView(button("Diagnóstico da conexão") { connectionReport() })
        }
        card(root,"03  /  NA ESTRADA") {
            guidance=label("Seu próximo caminho começa aqui.",20f,Color.WHITE,true); addView(guidance)
            metrics=label("Aguardando GPS",13f,muted); addView(metrics)
            addView(label("Confirme a imagem no painel antes de apagar a tela. Mapas novos e recálculo usam internet do celular.",14f,muted))
            fun toggle(title:String,key:String,default:Boolean) {
                val s=Switch(this@MainActivity).apply {
                    text=title; textSize=15f; setTextColor(Color.WHITE); setPadding(0,dp(12),0,dp(12))
                    isChecked=prefs.getBoolean(key,default)
                    setOnCheckedChangeListener { _, value ->
                        prefs.edit().putBoolean(key,value).apply()
                        if(RideService.ui.value.active) RideService.command(this@MainActivity,RideService.OPTIONS)
                    }
                }; addView(s)
            }
            toggle("Economia de energia · 2 quadros/s","economy",false)
            toggle("Orientação por voz em português","voice",true)
            toggle("Mapa noturno","night",false)
        }
        card(root,"04  /  PAPEL DE PAREDE") {
            addView(label("Uma imagem no seu painel",22f,Color.WHITE,true))
            addView(label("Aparece quando não há destino ativo. Ao iniciar uma rota, o mapa tem prioridade. A conexão com o celular precisa continuar ativa.",14f,muted))
            wallpaperPreview=ImageView(this@MainActivity).apply {
                contentDescription="Prévia do papel de parede do painel"
                adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER
                layoutParams=LinearLayout.LayoutParams(-1,dp(180))
                setBackgroundColor(Color.BLACK)
            }; addView(wallpaperPreview)
            wallpaperStatus=label("Nenhuma imagem escolhida",13f,muted); addView(wallpaperStatus)
            chooseWallpaper=button("Escolher imagem do celular") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type="image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },71)
            }; addView(chooseWallpaper)
            wallpaperToggle=Switch(this@MainActivity).apply {
                text="Mostrar imagem quando não houver rota"; setTextColor(Color.WHITE); textSize=14f
                isChecked=prefs.getBoolean("wallpaper_enabled",false)
                setOnCheckedChangeListener { _,enabled ->
                    prefs.edit().putBoolean("wallpaper_enabled",enabled).apply(); notifyWallpaperChanged()
                }
            }; addView(wallpaperToggle)
            addView(Switch(this@MainActivity).apply {
                text="Preencher a tela · recortar as bordas"; setTextColor(Color.WHITE); textSize=14f
                isChecked=prefs.getBoolean("wallpaper_fill",false)
                setOnCheckedChangeListener { _,fill ->
                    prefs.edit().putBoolean("wallpaper_fill",fill).apply()
                    refreshWallpaperPreview(); notifyWallpaperChanged()
                }
            })
            removeWallpaper=button("Remover papel de parede") {
                if(wallpaperBusy) return@button
                AlertDialog.Builder(this@MainActivity).setTitle("Remover papel de parede?")
                    .setMessage("A foto original permanece na galeria. Apenas a cópia do Rota Livre será removida.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Remover") { _,_->
                        scope.launch {
                            setWallpaperBusy(true)
                            try {
                                withContext(Dispatchers.IO) { WallpaperStore(this@MainActivity).remove() }
                                wallpaperToggle.isChecked=false; refreshWallpaperPreview(); notifyWallpaperChanged()
                            } finally { setWallpaperBusy(false) }
                        }
                    }.show()
            }; addView(removeWallpaper)
            addView(label("Imagem estática, salva somente neste celular. O recorte no display circular precisa ser conferido na moto.",12f,muted))
        }
        root.addView(button("Ajuda e diagnóstico") { help() })
        root.addView(label("Versão experimental 0.2 • Android 10+\nCompatibilidade e consumo ainda precisam de teste na moto.\nProjeto independente, sem vínculo com a Royal Enfield.",12f,muted))
        root.addView(button("Créditos e privacidade") { credits() })
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(root); clipToPadding=false }
        if(Build.VERSION.SDK_INT>=30) scroll.setOnApplyWindowInsetsListener { v,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(0,bars.top,0,bars.bottom); insets
        }
        setContentView(scroll)
        refreshWallpaperPreview()
    }
    private fun notifyWallpaperChanged() {
        if(RideService.ui.value.active) RideService.command(this,RideService.OPTIONS)
    }
    private fun setWallpaperBusy(busy:Boolean) {
        wallpaperBusy=busy; chooseWallpaper.isEnabled=!busy; removeWallpaper.isEnabled=!busy
        wallpaperToggle.isEnabled=!busy && WallpaperStore(this).exists
        if(busy) wallpaperStatus.text="Preparando imagem…"
    }
    private fun refreshWallpaperPreview() {
        wallpaperPreviewJob?.cancel()
        wallpaperPreviewJob=scope.launch {
            val fill=prefs.getBoolean("wallpaper_fill",false)
            val preview=withContext(Dispatchers.IO) {
                WallpaperStore(this@MainActivity).load()?.let { image ->
                    try {
                        android.graphics.Bitmap.createBitmap(526,300,android.graphics.Bitmap.Config.ARGB_8888).also {
                            WallpaperLayout.draw(android.graphics.Canvas(it),image,fill,android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        }
                    } finally { image.recycle() }
                }
            }
            wallpaperPreview.setImageBitmap(preview)
            wallpaperToggle.isEnabled=preview!=null && !wallpaperBusy
            wallpaperStatus.text=if(preview==null) "Nenhuma imagem escolhida" else if(fill) "Prévia · preenchimento com recorte central" else "Prévia · imagem inteira, com bordas quando necessário"
        }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=71 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        scope.launch {
            setWallpaperBusy(true)
            try {
                val app=applicationContext
                withContext(Dispatchers.IO) {
                    WallpaperStore(app).importPhoto(uri)
                    if(RideService.ui.value.active) RideService.command(app,RideService.OPTIONS)
                }
                wallpaperToggle.isChecked=true; refreshWallpaperPreview(); notifyWallpaperChanged()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { wallpaperStatus.text="Não consegui abrir essa imagem. Tente uma foto JPEG, PNG ou WebP de até 25 MB." }
            finally { setWallpaperBusy(false) }
        }
    }
    private fun acceptShare(intent:Intent?) {
        val text=when(intent?.action) { Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT); Intent.ACTION_VIEW -> intent.dataString; else -> null }
        if(!text.isNullOrBlank()) { destinationInput.setText(text); resolveDestination() }
    }
    private fun choose(d:Destination) {
        inputRevision++; searchGeneration++; searchJob?.cancel(); previewRouteJob?.cancel()
        selected=d; selectedText.text="${d.name}\n${d.lat}, ${d.lng}\nConfira o destino antes de iniciar."
        navigate.isEnabled=true; navigate.alpha=1f
        phoneMap.route=emptyList(); phoneMap.marker=GeoPoint(d.lat,d.lng); phoneMap.focus(phoneMap.marker!!)
    }
    private fun restoreDestination(json:String) { runCatching { val o=JSONObject(json); choose(Destination(o.getString("name"),o.getDouble("lat"),o.getDouble("lng"))) } }
    private fun resolveDestination() {
        if(resolving) return
        val raw=destinationInput.text.toString().trim()
        val revision=inputRevision
        selected=null; navigate.isEnabled=false; navigate.alpha=.4f
        val pair=Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(raw)
        if(pair!=null) {
            val lat=pair.groupValues[1].toDouble(); val lng=pair.groupValues[2].toDouble()
            if(lat in -85.0..85.0 && lng in -180.0..180.0) choose(Destination("Destino marcado",lat,lng))
            else selectedText.text="Coordenadas inválidas. Use ponto para os decimais."
            return
        }
        if(!raw.contains("://") && !raw.startsWith("geo:") && !raw.contains("maps.app.goo.gl")) { searchPlaces(raw); return }
        resolving=true; selectedText.text="Conferindo link…"
        scope.launch {
            try {
                val parsed=LocationParser.parse(raw)
                var lat=parsed.lat; var lng=parsed.lng; var name=parsed.name
                if(parsed.needsExpansion && parsed.url!=null) {
                    val result=LocationParser.resolve(parsed.url); lat=result.first?.first; lng=result.first?.second
                    name=result.second ?: name
                }
                if(revision!=inputRevision) return@launch
                if(lat!=null && lng!=null && lat in -85.0..85.0 && lng in -180.0..180.0) choose(Destination(name,lat,lng))
                else selectedText.text="Não consegui localizar. Compartilhe um ponto específico do Maps ou informe latitude, longitude."
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(revision==inputRevision) selectedText.text="Link inválido ou internet indisponível. Tente coordenadas." }
            finally { resolving=false }
        }
    }
    private fun ensureBike(action:()->Unit) {
        action() // Android presents nearby RE_ networks when no bike is saved.
    }
    private fun chooseAnotherBike() {
        if(RideService.ui.value.active) { toast("Pare a conexão antes de escolher outra moto."); return }
        DashConfig.get(this).forgetDash()
        withPermissions { RideService.command(this,RideService.START) }
    }
    private fun connectionReport() {
        val report=ConnectionDiagnostics.report()
        AlertDialog.Builder(this).setTitle("Diagnóstico da conexão").setMessage(report)
            .setPositiveButton("Fechar",null).setNeutralButton("Copiar diagnóstico") { _,_->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnóstico Rota Livre",report))
                toast("Diagnóstico copiado.")
            }.show()
    }
    private fun withPermissions(action:()->Unit) {
        val lm=getSystemService(android.location.LocationManager::class.java)
        if(!lm.isLocationEnabled) { AlertDialog.Builder(this).setMessage("Ative a localização do celular para usar o GPS.").setPositiveButton("Abrir ajustes") { _,_->startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.setNegativeButton("Cancelar",null).show(); return }
        val required=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT>=33) required+=Manifest.permission.NEARBY_WIFI_DEVICES
        if(required.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) {
            pendingAction=action
            if(Build.VERSION.SDK_INT>=33) required+=Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(required.toTypedArray(),42)
        } else action()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==43) {
            val action=pendingAction; pendingAction=null
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) action?.invoke()
            else searchStatus.text="Sem localização, você ainda pode buscar por nome e cidade ou escolher no mapa."
            return
        }
        if(code!=42) return
        val allowed=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED)
        val action=pendingAction; pendingAction=null
        if(allowed) action?.invoke() else AlertDialog.Builder(this).setMessage("Para conectar, permita localização precisa e dispositivos próximos. Você pode ajustar as permissões do Rota Livre.")
            .setPositiveButton("Abrir permissões") { _,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }.setNegativeButton("Agora não",null).show()
    }
    private fun toJson(d:Destination)=JSONObject().put("name",d.name).put("lat",d.lat).put("lng",d.lng)
    private fun favorites()=runCatching { JSONArray(prefs.getString("favorites","[]")) }.getOrDefault(JSONArray())
    private fun saveFavorite() {
        val d=selected ?: run { toast("Confira um destino primeiro."); return }
        val name=input("Nome do favorito").apply { setText(d.name) }
        AlertDialog.Builder(this).setTitle("Salvar favorito").setView(name).setNegativeButton("Cancelar",null).setPositiveButton("Salvar") { _,_->
            val list=favorites()
            if(list.length()>=30) toast("Limite de 30 favoritos. Remova um para adicionar outro.")
            else { list.put(toJson(d.copy(name=name.text.toString().ifBlank { d.name }))); prefs.edit().putString("favorites",list.toString()).apply(); toast("Favorito salvo no celular.") }
        }.show()
    }
    private fun showFavorites() {
        val list=favorites()
        if(list.length()==0) { toast("Você ainda não tem favoritos."); return }
        AlertDialog.Builder(this).setTitle("Favoritos").setItems(Array(list.length()) { list.getJSONObject(it).getString("name") }) { _,i ->
            val item=list.getJSONObject(i)
            AlertDialog.Builder(this).setTitle(item.getString("name")).setPositiveButton("Usar destino") { _,_->restoreDestination(item.toString()) }
                .setNeutralButton("Excluir") { _,_->list.remove(i); prefs.edit().putString("favorites",list.toString()).apply() }.setNegativeButton("Cancelar",null).show()
        }.show()
    }
    private fun help() {
        val s=RideService.ui.value
        AlertDialog.Builder(this).setTitle("Conectar e testar").setMessage(
            "1. Com a moto parada, ative o Wi-Fi do Tripper Dash e feche a conexão do aplicativo oficial.\n\n2. Toque em Conectar e escolha sua rede RE_ na lista do Android. A senha padrão é automática. Mantenha dados móveis ligados.\n\n3. Busque um endereço ou lugar, veja a rota e inicie a navegação. Confira a imagem no painel antes de apagar a tela.\n\nSe o Android interromper a viagem, confira em Bateria se Rota Livre pode funcionar sem restrição.\n\nEstado: ${s.stage}\n${s.gps}\nQuadros enviados: ${s.frames}\nCodificador: ${s.codec.ifBlank { "Ainda não iniciado" }}\n\nQuadros enviados não comprovam que o painel os exibiu. Teste necessário na sua moto."
        ).setPositiveButton("Fechar",null).setNeutralButton("Ajustes do app") { _,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }.show()
    }
    private fun credits() {
        AlertDialog.Builder(this).setTitle("Créditos e privacidade").setMessage(
            "Rota Livre ${BuildConfig.VERSION_NAME} — versão experimental pessoal.\n\nIntegração derivada de OpenDash (Nukeio), NorthStar e better-dash, sob Apache 2.0.\n\n© OpenStreetMap contributors, dados ODbL. Mapas públicos sob demanda; rotas pelo servidor demonstrativo OSRM, sem trânsito em tempo real e sem garantia de disponibilidade. Não há mapas offline completos.\n\nSem conta, Firebase, anúncios ou análise de uso. Favoritos, papel de parede e configuração ficam no celular. O serviço de rotas recebe origem e destino; o servidor de mapas recebe a área consultada. A busca usa Photon/Komoot e recebe o texto pesquisado; lugares próximos também enviam sua posição. Links compartilhados podem consultar o Google.\n\nAndroid Auto não está incluído."
        ).setPositiveButton("Fechar",null).setNeutralButton("Licenças") { _,_->
            val notice=assets.open("NOTICE").bufferedReader().use { it.readText() }
            val license=assets.open("LICENSE").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("Licenças de código").setMessage(notice+"\n\n"+license).setPositiveButton("Fechar",null).show()
        }.show()
    }
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()

    private fun searchPlaces(query:String) {
        if(query.trim().length<2) { searchStatus.text="Digite o nome de um lugar ou endereço."; return }
        val generation=++searchGeneration
        searchJob?.cancel(); searchResults.removeAllViews(); searchStatus.text="Buscando lugares…"
        searchJob=scope.launch {
            try {
                val results=PlaceSearch(this@MainActivity).search(query)
                if(generation!=searchGeneration) return@launch
                showPlaces(results,false)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(generation==searchGeneration) searchStatus.text="Não consegui buscar. Confira a internet e tente novamente." }
        }
    }
    private fun showPlaces(results:List<Place>,nearby:Boolean) {
        searchResults.removeAllViews()
        searchStatus.text=if(results.isEmpty()) "Nenhum resultado. Tente outro nome, cidade ou categoria." else
            "${results.size} lugares · toque para escolher" + if(nearby) "\nDistâncias em linha reta; a rota pode ser maior." else ""
        results.forEach { place ->
            val text=place.name+"\n"+place.address+(place.meters?.let { "\n${RideService.distance(it)} em linha reta" } ?: "")
            searchResults.addView(button(text) { choose(Destination(place.name,place.lat,place.lng)); searchStatus.text="Destino escolhido: ${place.name}" }.apply {
                layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) }; minHeight=dp(70); textSize=14f
                setPadding(dp(10),dp(10),dp(10),dp(10)); gravity=Gravity.START or Gravity.CENTER_VERTICAL
            })
        }
    }
    private fun nearbyDialog() {
        val categories=PlaceSearch.categories.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("O que você procura perto de você?").setItems(categories) { _,index ->
            locate { origin ->
                val generation=++searchGeneration; searchJob?.cancel(); searchResults.removeAllViews()
                searchStatus.text="Buscando ${categories[index].lowercase()} em até 10 km…"
                searchJob=scope.launch {
                    try {
                        val places=PlaceSearch(this@MainActivity).nearby(categories[index],origin)
                        if(generation!=searchGeneration) return@launch
                        showPlaces(places,true)
                        if(places.isNotEmpty()) phoneMap.fit(places.map { GeoPoint(it.lat,it.lng) }+origin)
                    } catch(e:CancellationException) { throw e }
                    catch(_:Exception) { if(generation==searchGeneration) searchStatus.text="Não consegui buscar lugares próximos. Confira a internet e tente novamente." }
                }
            }
        }.setNegativeButton("Cancelar",null).show()
    }
    private fun locate(action:(GeoPoint)->Unit) {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            pendingAction={ locate(action) }
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),43); return
        }
        val manager=getSystemService(android.location.LocationManager::class.java)
        if(!manager.isLocationEnabled) { searchStatus.text="Ative a localização do celular para encontrar sua posição."; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return }
        if(currentPosition!=null && SystemClock.elapsedRealtime()-positionTime<60_000) { action(currentPosition!!); return }
        searchStatus.text="Localizando você…"
        scope.launch {
            val tracker=LocationTracker(this@MainActivity)
            try {
                tracker.start()
                val fix=withTimeoutOrNull(20_000) { tracker.location.filterNotNull().first {
                    it.hasAccuracy() && it.accuracy<1000 && (SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos) in 0..120_000_000_000L
                } }
                if(fix==null) { searchStatus.text="Ainda sem posição recente. Tente ao ar livre; a busca por endereço continua disponível."; return@launch }
                val point=GeoPoint(fix.latitude,fix.longitude); currentPosition=point; positionTime=SystemClock.elapsedRealtime()
                phoneMap.position=point; phoneMap.invalidate(); searchStatus.text="Sua posição foi localizada."; action(point)
            } finally { tracker.stop() }
        }
    }
    private fun previewRoute() {
        val d=selected ?: run { toast("Escolha um destino na busca ou no mapa."); return }
        locate { from ->
            if(selected!=d) return@locate
            previewRouteJob?.cancel(); selectedText.text="Calculando rota até ${d.name}…"
            previewRouteJob=scope.launch {
                val route=Router.route(from,GeoPoint(d.lat,d.lng))
                if(selected!=d) return@launch
                if(route==null) { selectedText.text="Não consegui calcular. Confira a internet e tente novamente."; return@launch }
                phoneMap.route=route.geometry; phoneMap.fit(route.geometry)
                selectedText.text="${d.name}\n${RideService.distance(route.totalMeters)} · cerca de ${(route.totalSeconds/60).toInt().coerceAtLeast(1)} min\nRota rodoviária, sem trânsito em tempo real."
            }
        }
    }
}
