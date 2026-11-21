package com.example.opendash.auto

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.example.opendash.dash.DashConfig
import kotlinx.coroutines.*

class AutoActivity:Activity(),TextureView.SurfaceTextureListener {
    private companion object {
        const val PAGE_INSET=20
        const val CONTROL_GAP=8
        const val BUTTON_HEIGHT=56
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var status:TextView
    private lateinit var detail:TextView
    private lateinit var feedback:TextView
    private lateinit var controlsStatus:TextView
    private lateinit var preview:TextureView
    private var homeExample:OfflinePanelPreview?=null
    private var panelExample:OfflinePanelPreview?=null
    private lateinit var destination:EditText
    private lateinit var serverSettings:Button
    private lateinit var gpsBubbleSwitch:Switch
    private var routeJob:Job?=null
    private var surface:Surface?=null
    private var observer:Job?=null
    private var updateCheck:Job?=null
    private lateinit var updateNotice:Button
    private var pendingBike=false
    private var pendingNewBike=false
    private var touching=false
    private var simulatePreview=false
    private var lastTouch=0 to 0
    private var visible=false
    private var selectedPage=0
    private val pageParents=mutableMapOf<Int,Int>()
    private var pendingExport:String?=null
    private var pendingInputLog:String?=null
    private var controlEditorListener:SharedPreferences.OnSharedPreferenceChangeListener?=null
    private var editorPreferences:SharedPreferences?=null
    private var selectPage:(Int)->Unit={}
    private lateinit var controlsPage:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport=savedInstanceState?.getString("pending-export")
        BikeInputCapture.initialize(this)
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        var root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(20),dp(18),dp(20)); setBackgroundColor(Color.rgb(16,25,20)) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(18),dp(16)+insets.systemWindowInsetTop,dp(18),dp(16)+insets.systemWindowInsetBottom); insets
        }
        fun text(value:String,size:Float=15f)=TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(0,dp(8),0,dp(8)) }
        fun button(label:String,action:()->Unit) { root.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() }; layoutParams=LinearLayout.LayoutParams(-1,dp(52)).apply { bottomMargin=dp(8) } }) }
        fun pair(a:String,b:String,actionA:()->Unit,actionB:()->Unit,firstWeight:Float=1f):LinearLayout {
            val row=LinearLayout(this).apply {
                addView(Button(this@AutoActivity).apply { text=a; setOnClickListener { actionA() } },LinearLayout.LayoutParams(0,dp(56),firstWeight).apply { marginEnd=dp(4) })
                addView(Button(this@AutoActivity).apply { text=b; setOnClickListener { actionB() } },LinearLayout.LayoutParams(0,dp(56),1f).apply { marginStart=dp(4) })
            }
            root.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            return row
        }
        root.addView(text("Rota Auto",21f))
        root.addView(text("por Matheus Castellar",13f))
        root.addView(text("Seu caminho, no painel da moto.",15f))
        status=text("Prepare o Android Auto para começar",17f); root.addView(status)
        updateNotice=Button(this).apply {
            visibility=View.GONE; isAllCaps=false
            setOnClickListener { selectPage(26) }
        }
        root.addView(updateNotice)
        preview=TextureView(this).apply { surfaceTextureListener=this@AutoActivity; layoutParams=LinearLayout.LayoutParams(-1,dp(190)); isOpaque=true }
        preview.setOnTouchListener { v,event ->
            if(simulatePreview) return@setOnTouchListener true
            v.parent.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP && event.actionMasked!=MotionEvent.ACTION_CANCEL)
            val point=AaGeometry.touch(event.x,event.y,v.width,v.height)
            if(event.actionMasked==MotionEvent.ACTION_DOWN) touching=point!=null
            if(touching && event.actionMasked in listOf(0,1,2,3)) {
                if(point!=null) lastTouch=point
                val action=if(point==null) MotionEvent.ACTION_CANCEL else event.actionMasked
                AutoBridgeService.touch(action,lastTouch.first,lastTouch.second)
                if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP) touching=false
            }
            if(event.actionMasked==MotionEvent.ACTION_UP) v.performClick(); true
        }; root.addView(preview)
        feedback=text("",14f).apply { visibility=View.GONE }; root.addView(feedback)
        val help=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        help.addView(text("1. Abra as configurações do Android Auto no seu celular. Toque 10 vezes em \"Versão\" para liberar o menu de desenvolvedor do Android Auto.\n\n2. No menu (⋮) da tela principal do Android Auto, escolha Iniciar servidor da unidade principal.\n\n3. Vá no aplicativo Rota Auto, ligue o Android Auto e conecte na moto (ou reconecte caso tenha se conectado antes).\n\nCaso ao bloquear a tela do celular o painel perca a ligação, basta ativar a Depuração USB nas opções de desenvolvedor do Android (cada celular é em um lugar diferente. Basta pesquisar no Google como ativar o modo de desenvolvedor no modelo do seu celular).",14f))
        help.addView(Button(this).apply { text="Abrir configurações do Android Auto"; isAllCaps=false; setOnClickListener { openSettings() } })
        button("Tutorial") { help.visibility=if(help.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        root.addView(help)
        val startRow=pair("Ligar Android Auto","Parar",{ start(false) },{ AutoBridgeService.command(this,AutoBridgeService.STOP) },2f)
        startRow.addView(Button(this).apply { text="⚙"; tag="server-gear"; contentDescription="Configurações do Android Auto"; setOnClickListener { openSettings() } },1,LinearLayout.LayoutParams(dp(BUTTON_HEIGHT),dp(BUTTON_HEIGHT)))
        serverSettings=Button(this).apply {
            text="Abrir configuração do servidor"; isAllCaps=false; visibility=View.GONE
            layoutParams=LinearLayout.LayoutParams(-1,dp(52))
            setOnClickListener { openSettings() }
        }
        root.addView(serverSettings)
        pair("Reconectar Moto","Iniciar nova conexão",{ start(true) },{ start(true,newBike=true) })
        val home=root
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        destination=EditText(this).apply {
            id=com.example.opendash.R.id.route_destination
            hint="Destino ou link do Google Maps"; contentDescription="Destino"
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            setSingleLine(true); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO) { openRoute(); true } else false }
        }
        root.addView(destination,LinearLayout.LayoutParams(-1,dp(56)))
        pair("Traçar rota","Abrir Google Maps",{ openRoute() },{
            try { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.google.com/maps")).setPackage(MapsRoute.PACKAGE)) }
            catch(_:ActivityNotFoundException) { showFeedback("Instale ou ative o Google Maps.") }
        })
        root.addView(text("Rotas de moto. Você também pode definir a rota no Google Maps.",13f))
        val previewControls=pair("Voltar no Android Auto","Falar com o assistente",{ AutoBridgeService.key(4) },{
            if(!PhoneAudio.input(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                showFeedback("Permita o microfone ao iniciar o Android Auto para usar o assistente.")
            } else AutoBridgeService.key(84)
        })
        root.removeView(previewControls)
        val routes=root
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        controlsStatus=text("Joystick aguardando comandos",14f); controls.addView(controlsStatus)
        val preferences=getSharedPreferences("auto-controls-options",MODE_PRIVATE)
        root.addView(Switch(this).apply {
            text="Moto parada"; setTextColor(Color.WHITE)
            isChecked=preferences.getBoolean(AaSensors.PARKED,false)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean(AaSensors.PARKED,value).apply() }
        })
        root.addView(text("Libera a personalização do Android Auto. Desative antes de sair. Não altera o GPS.",13f))
        gpsBubbleSwitch=Switch(this).apply {
            text="Ocultar bola de direções da Royal"; setTextColor(Color.WHITE); isChecked=preferences.getBoolean("clean-projection",true)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean("clean-projection",value).apply() }
        }
        root.addView(gpsBubbleSwitch)
        root.addView(text("Ocultar pode bloquear os comandos do mapa. Com esta opção desligada, a bolinha fica visível mesmo sem rota, neutra até receber uma orientação.",13f))
        root.addView(text("Mapa: dia e noite",20f))
        root.addView(RadioGroup(this).apply {
            orientation=RadioGroup.HORIZONTAL
            val selected=preferences.getString(MapDayNight.KEY,"AUTO")
            for((index,label) in listOf("Automático","Dia","Noite").withIndex()) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); tag=MapDayNight.modes[index]; text=L(label); setTextColor(Color.WHITE)
                isChecked=tag==selected
            },RadioGroup.LayoutParams(0,dp(48),1f))
            setOnCheckedChangeListener { group,id ->
                group.findViewById<RadioButton>(id)?.let { preferences.edit().putString(MapDayNight.KEY,it.tag as String).apply() }
            }
        })
        root.addView(text("Automático: dia das 6h às 18h; noite nos demais horários. Usa o horário do celular e muda só o mapa do Android Auto.",13f))
        root.addView(text("Áudio",20f))
        val fallbackInput=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=if(PhoneAudio.input(this@AutoActivity)) View.GONE else View.VISIBLE }
        val fallbackOutput=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=if(PhoneAudio.output(this@AutoActivity)) View.GONE else View.VISIBLE }
        root.addView(Switch(this).apply {
            text="Som direto no celular"; setTextColor(Color.WHITE)
            isChecked=PhoneAudio.output(this@AutoActivity)
            setOnCheckedChangeListener { _,value ->
                preferences.edit().putBoolean(PhoneAudio.OUTPUT,value).apply()
                fallbackOutput.visibility=if(value) View.GONE else View.VISIBLE
                showFeedback("Pare e inicie a projeção para aplicar o áudio.")
            }
        })
        root.addView(Switch(this).apply {
            text="Microfone gerenciado pelo Android Auto"; setTextColor(Color.WHITE)
            isChecked=PhoneAudio.input(this@AutoActivity)
            setOnCheckedChangeListener { _,value ->
                preferences.edit().putBoolean(PhoneAudio.INPUT,value).apply()
                fallbackInput.visibility=if(value) View.GONE else View.VISIBLE
                showFeedback("Pare e inicie a projeção para aplicar o microfone.")
            }
        })
        root.addView(text("Som direto: GPS, assistente e música usam a saída do telefone. Microfone gerenciado: o Android Auto escolhe a captura; desligado, o Rota Auto fornece o microfone. Mudanças exigem reiniciar a projeção.",13f))
        fallbackInput.addView(Switch(this).apply {
            text="Microfone do intercomunicador quando conectado"; setTextColor(Color.WHITE)
            isChecked=preferences.getBoolean("intercom-mic",true)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean("intercom-mic",value).apply() }
        })
        root.addView(fallbackInput)
        root.addView(fallbackOutput)
        root.addView(text("Durante uma ligação, os comandos de volume ajustam a chamada. Fora dela, ajustam a mídia.",13f))
        val voiceLabel=text("",15f)
        fallbackOutput.addView(voiceLabel)
        val voiceGain=SeekBar(this).apply {
            contentDescription="Ganho da voz"
            max=VoiceGain.MAX-VoiceGain.MIN
            progress=preferences.getInt(VoiceGain.PREFERENCE,VoiceGain.DEFAULT).coerceIn(VoiceGain.MIN,VoiceGain.MAX)-VoiceGain.MIN
            fun label() {
                val value=progress+VoiceGain.MIN
                voiceLabel.text="Ganho da voz: $value%"
                contentDescription="Ganho do assistente e GPS: $value por cento"
            }
            label()
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                    if(fromUser) preferences.edit().putInt(VoiceGain.PREFERENCE,value+VoiceGain.MIN).apply()
                    label()
                }
            })
        }
        fallbackOutput.addView(voiceGain)
        fallbackOutput.addView(text("O ganho só funciona com a voz passando pela ponte. Não altera a música nem o microfone.",13f))
        fallbackOutput.addView(Button(this).apply { text="Voz no nível original · 100%"; isAllCaps=false; setOnClickListener {
            preferences.edit().putInt(VoiceGain.PREFERENCE,VoiceGain.MIN).apply()
            voiceGain.progress=0
        } })
        root.addView(text("Imagem no painel",20f))
        root.addView(Switch(this).apply {
            text="Alternar barra pelo botão"; setTextColor(Color.WHITE); isChecked=preferences.getBoolean(TemporaryBar.TOGGLE,false)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean(TemporaryBar.TOGGLE,value).apply() }
        })
        root.addView(text("Desligado: barra temporária. Ligado: um toque mostra e outro esconde.",13f))
        val barDuration=text("Tempo da barra: "+preferences.getInt(TemporaryBar.SECONDS,5)+" s",15f); root.addView(barDuration)
        root.addView(SeekBar(this).apply {
            contentDescription="Tempo da barra"; max=29; progress=preferences.getInt(TemporaryBar.SECONDS,5).coerceIn(1,30)-1
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                    barDuration.text="Tempo da barra: "+(value+1)+" s"
                    if(fromUser) preferences.edit().putInt(TemporaryBar.SECONDS,value+1).apply()
                }
            })
        })
        root.addView(RadioGroup(this).apply {
            contentDescription="Modo de imagem do painel"
            val selected=PanelLayout.resolve(preferences.getString(PanelLayout.PREFERENCE,null),preferences.getBoolean("hide-aa-bar",false))
            for(mode in PanelLayout.entries) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); text=mode.label; tag=mode.name; textSize=14f; setTextColor(Color.WHITE)
                isChecked=mode==selected; minHeight=dp(48)
                setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(PanelLayout.PREFERENCE,mode.name).apply() }
            })
        })
        root.addView(text("Muda na hora na moto. B preserva proporções; C pode cortar os cantos; D usa uma curva aproximada. A prévia normal mantém o toque.",13f))
        root.addView(text("Formato da distorção · modo D",16f))
        root.addView(RadioGroup(this).apply {
            contentDescription="Formato da distorção"
            val selected=CurveStyle.resolve(preferences.getString(CurveStyle.PREFERENCE,null))
            for(style in CurveStyle.entries) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); text=style.label; tag="curve.${style.name}"; setTextColor(Color.WHITE); isChecked=selected==style
                setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(CurveStyle.PREFERENCE,style.name).apply() }
            })
        })
        val distortionLabel=text("Distorção: ${preferences.getInt(PanelLayout.DISTORTION,PanelLayout.DEFAULT_DISTORTION)}%",15f)
        root.addView(distortionLabel)
        root.addView(SeekBar(this).apply {
            contentDescription="Distorção do painel"; max=100
            progress=preferences.getInt(PanelLayout.DISTORTION,PanelLayout.DEFAULT_DISTORTION).coerceIn(0,100)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                    distortionLabel.text="Distorção: $value%"
                    if(fromUser) preferences.edit().putInt(PanelLayout.DISTORTION,value).apply()
                }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
        root.addView(text("Modo D: 0% proporcional · 100% ocupa a curva.",13f))
        fun imageSlider(name:String,key:String,minimum:Int,maximum:Int,default:Int,unit:String):SeekBar {
            val value=preferences.getInt(key,default).coerceIn(minimum,maximum)
            val label=text("$name: $value$unit",15f); root.addView(label)
            return SeekBar(this).apply {
                contentDescription=name; max=maximum-minimum; progress=value-minimum
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar,p:Int,fromUser:Boolean) {
                        val next=p+minimum; label.text="$name: $next$unit"
                        if(fromUser) preferences.edit().putInt(key,next).apply()
                    }
                    override fun onStartTrackingTouch(bar:SeekBar) {}
                    override fun onStopTrackingTouch(bar:SeekBar) {}
                })
            }.also { root.addView(it) }
        }
        fun positionSlider(name:String,key:String,horizontal:Boolean,bounds:()->AaGeometry.Rect) {
            val limit=if(horizontal) 526 else 300
            val bar=imageSlider(name,key,-limit,limit,0," px")
            val row=LinearLayout(this)
            val labels=if(horizontal) listOf("Esquerda","Centro","Direita") else listOf("Topo","Centro","Baixo")
            labels.forEachIndexed { index,label -> row.addView(Button(this).apply {
                text=label; contentDescription="$name: $label"
                setOnClickListener {
                    val r=bounds()
                    val offset=PanelAlignment.offset(if(horizontal) r.left else r.top,if(horizontal) r.width else r.height,if(horizontal) 526f else 300f,index).coerceIn(-limit,limit)
                    preferences.edit().putInt(key,offset).apply(); bar.progress=offset+limit
                }
            },LinearLayout.LayoutParams(0,dp(48),1f)) }
            root.addView(row)
        }
        fun imageBounds()=PanelGeometry.bounds(PanelGeometry.mesh(PanelLayout.CURVED,
            preferences.getInt(PanelLayout.DISTORTION,PanelLayout.DEFAULT_DISTORTION),0,
            preferences.getInt(PanelLayout.SIZE_X,100),preferences.getInt(PanelLayout.SIZE_Y,100),
            CurveStyle.resolve(preferences.getString(CurveStyle.PREFERENCE,null))))
        imageSlider("Largura X",PanelLayout.SIZE_X,50,150,100,"%")
        imageSlider("Altura Y",PanelLayout.SIZE_Y,50,150,100,"%")
        positionSlider("Posição X",PanelLayout.POSITION_X,true,::imageBounds)
        positionSlider("Posição Y",PanelLayout.POSITION_Y,false,::imageBounds)
        root.addView(text("Logo · modos B e D",16f))
        root.addView(RadioGroup(this).apply {
            contentDescription="Logo no painel"
            val selected=PanelLogo.resolve(preferences.getString(PanelLogo.PREFERENCE,null))
            for(logo in PanelLogo.entries) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); text=logo.label; tag="logo.${logo.name}"; setTextColor(Color.WHITE)
                isChecked=logo==selected
                setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(PanelLogo.PREFERENCE,logo.name).apply() }
            })
        })
        fun logoBounds():AaGeometry.Rect {
            val bitmap=PanelLogo.resolve(preferences.getString(PanelLogo.PREFERENCE,null)).bitmap(resources,OverlayBackground.resolve(preferences.getString(PanelLogo.BACKGROUND,null)))
            val aspect=bitmap?.let { it.width.toFloat()/it.height } ?: 3f
            bitmap?.recycle()
            return PanelGeometry.bounds(PanelGeometry.logoMesh(aspect,preferences.getInt(PanelLogo.SIZE,100)))
        }
        imageSlider("Tamanho do logo",PanelLogo.SIZE,25,200,100,"%")
        positionSlider("Logo X",PanelLogo.POSITION_X,true,::logoBounds)
        positionSlider("Logo Y",PanelLogo.POSITION_Y,false,::logoBounds)
        fun backgroundOptions(title:String,key:String) {
            root.addView(text(title,16f))
            root.addView(RadioGroup(this).apply {
                contentDescription=title
                val chosen=OverlayBackground.resolve(preferences.getString(key,null))
                for(style in OverlayBackground.entries) addView(RadioButton(this@AutoActivity).apply {
                    id=View.generateViewId(); text=style.label; tag=key+"."+style.name; setTextColor(Color.WHITE); isChecked=chosen==style
                    setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(key,style.name).apply() }
                })
            })
        }
        backgroundOptions("Fundo da logo",PanelLogo.BACKGROUND)
        root.addView(text("Widget de informações",20f))
        root.addView(Switch(this).apply {
            text="Mostrar widget"; setTextColor(Color.WHITE); isChecked=preferences.getBoolean(PanelWidget.ENABLED,false)
            setOnCheckedChangeListener { _,checked -> preferences.edit().putBoolean(PanelWidget.ENABLED,checked).apply() }
        })
        root.addView(text("Data, hora, bateria e rede móvel do celular. Independente do Wi-Fi da moto e da logo.",13f))
        root.addView(RadioGroup(this).apply {
            contentDescription="Formato do widget"
            val chosen=WidgetLayout.resolve(preferences.getString(PanelWidget.LAYOUT,null))
            for(layout in WidgetLayout.entries) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); text=layout.label; tag="widget-layout."+layout.name; setTextColor(Color.WHITE); isChecked=chosen==layout
                setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(PanelWidget.LAYOUT,layout.name).apply() }
            })
        })
        imageSlider("Tamanho do widget",PanelWidget.SIZE,25,200,100,"%")
        imageSlider("Altura do widget",PanelWidget.HEIGHT,75,200,100,"%")
        fun widgetBounds()=PanelWidget.bounds(preferences.getInt(PanelWidget.SIZE,100),layout=WidgetLayout.resolve(preferences.getString(PanelWidget.LAYOUT,null)),heightPercent=preferences.getInt(PanelWidget.HEIGHT,100))
        positionSlider("Widget X",PanelWidget.X,true,::widgetBounds)
        positionSlider("Widget Y",PanelWidget.Y,false,::widgetBounds)
        backgroundOptions("Fundo do widget",PanelWidget.BACKGROUND)
        root.addView(Switch(this).apply {
            text="Widget à frente da logo"; setTextColor(Color.WHITE); isChecked=preferences.getBoolean(PanelWidget.FRONT,true)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean(PanelWidget.FRONT,value).apply() }
        })
        root.addView(text("Desligado: a logo fica à frente do widget.",13f))
        root.addView(text("Fundo desfocado · modos B e D",16f))
        root.addView(RadioGroup(this).apply {
            contentDescription="Área do desfoque"
            val selected=BlurArea.resolve(preferences.getString(BlurArea.PREFERENCE,null),preferences.getBoolean("panel-blur",true))
            for(area in BlurArea.entries) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); text=area.label; tag="blur.${area.name}"; setTextColor(Color.WHITE); isChecked=area==selected
                setOnCheckedChangeListener { _,checked -> if(checked) preferences.edit().putString(BlurArea.PREFERENCE,area.name).apply() }
            })
        })
        root.addView(text("Fluidez do painel",16f))
        var refreshVideoControls:()->Unit={}
        val fpsGroup=RadioGroup(this).apply {
            for(fps in listOf(12,8,4)) addView(RadioButton(this@AutoActivity).apply {
                id=View.generateViewId(); tag=fps; text=when(fps) { 12 -> "Fluidez + desempenho · recomendado · 12 fps / 200 kb/s"; 8 -> "8 fps · intermediário"; else -> "4 fps · compatibilidade" }
                setTextColor(Color.WHITE); isChecked=preferences.getInt("video-fps",VideoProfile.DEFAULT_FPS)==fps
            })
            setOnCheckedChangeListener { group,id ->
                VideoProfile.selectFps(preferences,group.findViewById<RadioButton>(id).tag as Int)
                refreshVideoControls()
            }
        }; root.addView(fpsGroup)
        val manualRate=Switch(this).apply { text="Ajustar kb/s manualmente"; setTextColor(Color.WHITE); isChecked=VideoProfile.manual(preferences) }
        root.addView(manualRate)
        val rateSlider=imageSlider("Taxa de vídeo",VideoProfile.BITRATE,100,2000,VideoProfile.forFps(preferences.getInt("video-fps",VideoProfile.DEFAULT_FPS)).bitrate/1000," kb/s")
        val rateLabel=root.getChildAt(root.indexOfChild(rateSlider)-1)
        val rateHelp=text("",13f); root.addView(rateHelp)
        refreshVideoControls={
            val manual=VideoProfile.manual(preferences)
            rateSlider.progress=VideoProfile.fromPreferences(preferences).bitrate/1000-100
            rateSlider.isEnabled=manual; rateSlider.alpha=if(manual) 1f else .35f; rateLabel.alpha=rateSlider.alpha
            rateHelp.text=L(if(manual) "Taxa variável: o valor é uma média desejada. Trocar o FPS carrega a média sugerida; depois você pode ajustá-la." else "Taxa variável conforme a imagem. Média sugerida: 4 fps = 200 kb/s; 8 fps = 400 kb/s; 12 fps = 200 kb/s. Não mede a qualidade do Wi-Fi.")
        }
        manualRate.setOnCheckedChangeListener { _,manual -> preferences.edit().putBoolean(VideoProfile.MANUAL,manual).apply(); refreshVideoControls() }
        root.addView(Button(this).apply { text="Restaurar vídeo de fábrica"; setOnClickListener {
            if(!savePreviousSettings("Restaurar vídeo de fábrica")) return@setOnClickListener
            VideoProfile.restore(preferences)
            manualRate.isChecked=false
            (0 until fpsGroup.childCount).map { fpsGroup.getChildAt(it) as RadioButton }.single { it.tag==VideoProfile.DEFAULT_FPS }.isChecked=true
            rateSlider.progress=VideoProfile.forFps(VideoProfile.DEFAULT_FPS).bitrate/1000-100
            refreshVideoControls()
        } },LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        refreshVideoControls()
        val mainSettings=root
        val traditional=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root=traditional
        root.addView(Switch(this).apply {
            text="Voltar ao Perfil 1 após 15 segundos sem comandos"; setTextColor(Color.WHITE)
            isChecked=preferences.getBoolean(ProfileIdleReturn.KEY,false)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean(ProfileIdleReturn.KEY,value).apply() }
        })
        controls.visibility=View.VISIBLE
        buildControlEditor(controls,preferences)
        root.addView(controls)
        root.addView(Switch(this).apply {
            text="Mostrar comandos ao trocar perfil"; setTextColor(Color.WHITE)
            isChecked=preferences.getBoolean(ProfilePopup.ENABLED,false)
            setOnCheckedChangeListener { _,value -> preferences.edit().putBoolean(ProfilePopup.ENABLED,value).apply() }
        })
        root.addView(text("Comandos de dentro. 2× indica dois toques.",13f))
        val popupDurationLabel=text("",14f)
        fun popupDurationText(steps:Int) {
            val number=java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag(UiLanguage.selected(this))).apply { maximumFractionDigits=1 }
            popupDurationLabel.text=L("Tempo dos comandos")+": "+number.format(steps/2.0)+" s"
        }
        popupDurationText((ProfilePopup.duration(preferences)/500L).toInt())
        root.addView(popupDurationLabel)
        root.addView(SeekBar(this).apply {
            max=19; progress=(ProfilePopup.duration(preferences)/500L).toInt()-1
            contentDescription=L("Tempo dos comandos")
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                    popupDurationText(value+1)
                    if(fromUser) preferences.edit().putInt(ProfilePopup.DURATION,value+1).apply()
                }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
        button("Prévia dos comandos") {
            val bitmap=ProfilePopup.bitmap(ProfilePopup.read(this))
            val picture=ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; contentDescription=L("Prévia dos comandos") }
            AlertDialog.Builder(this).setTitle(L("Prévia dos comandos")).setView(picture)
                .setPositiveButton(L("Fechar"),null).create().apply {
                    setOnDismissListener { picture.setImageDrawable(null); bitmap.recycle() }; show()
                }
        }
        root=mainSettings
        controlsPage=buildControlsPage(traditional,preferences)
        button("Controles da moto") { selectPage(4) }
        button("Tela preta (sem bloquear)") {
            if(AutoBridgeService.state.value.active) startActivity(Intent(this,BlackScreenActivity::class.java))
            else showFeedback("Inicie a conexão antes de escurecer a tela.")
        }
        root.addView(text("Opção de fundo preto e brilho mínimo, mantendo o celular desbloqueado. Toque duas vezes para voltar. Para bloquear normalmente, use o botão físico.",13f))
        detail=text("",13f).apply { visibility=View.GONE }
        button("Detalhes da conexão") { detail.visibility=if(detail.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        root.addView(detail)
        button("Exportar configurações") {
            AlertDialog.Builder(this).setTitle(L("Exportar configurações"))
                .setItems(arrayOf(L("Todas as configurações e perfis"),L("Somente o perfil tradicional atual"))) { _,which ->
                    pendingExport=SettingsExport.create(this,which==1)
                    val name=if(which==1) "RotaAuto-perfil-${BikeControlLayout.active(preferences)}.json" else "RotaAuto-configuracoes.json"
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE,name),710)
                    } catch(_:ActivityNotFoundException) { pendingExport=null; showFeedback("Não foi possível abrir o seletor de arquivos.") }
                }.setNegativeButton(L("Cancelar"),null).show()
        }
        button("Importar configurações") {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/plain","application/octet-stream")),711) }
            catch(_:ActivityNotFoundException) { showFeedback("Não foi possível abrir o seletor de arquivos.") }
        }
        button("Voltar à configuração anterior") { restorePreviousSettings() }
        root.addView(text("Guardamos uma cópia antes de importar configurações ou restaurar vídeo e perfis. Ela fica somente neste celular.",13f))
        button("Créditos") {
            AlertDialog.Builder(this).setTitle(L("Rota Auto"))
                .setMessage(L("Por Matheus Castellar.\n\nBaseado em Open Headunit, OpenDash, NorthStar e better-dash. Referências: WayFindr e Dash Bridge.\n\nCódigo aberto · AGPL-3.0.\nSem vínculo com Google ou Royal Enfield."))
                .setPositiveButton(L("Fechar"),null)
                .setNeutralButton(L("Licenças")) { _,_ ->
                    val content=TextView(this).apply {
                        text=assets.open("NOTICE").bufferedReader().use { it.readText() }+"\n\n"+assets.open("LICENSE").bufferedReader().use { it.readText() }
                        textSize=13f; setPadding(dp(20),dp(12),dp(20),dp(12)); setTextIsSelectable(true)
                    }
                    AlertDialog.Builder(this).setTitle(L("Licenças")).setView(ScrollView(this).apply { addView(content) }).setPositiveButton(L("Fechar"),null).show()
                }.show()
        }
        button("Atualizações") { selectPage(26) }
        button("Ajustes de bateria do aplicativo") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        }
        buildScreens(home,routes,root,previewControls,savedInstanceState?.getInt("page",0) ?: if(intent.getBooleanExtra("open-updates",false)) 26 else 0)
        intent.getStringExtra("settings-imported")?.let { showFeedback(it); intent.removeExtra("settings-imported") }
        root.isFocusableInTouchMode=true; root.requestFocus()
        if(savedInstanceState==null) receiveDestination(intent)
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent); setIntent(intent); receiveDestination(intent)
        if(intent.getBooleanExtra("open-updates",false)) selectPage(26)
    }
    private fun receiveDestination(incoming:Intent) {
        routeJob?.cancel()
        val value=when(incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        } ?: return
        val target=MapsRoute.sharedText(value)
        if(target==null) { showFeedback("Use um endereço ou link do Google Maps."); return }
        selectPage(1)
        destination.setText(target)
        destination.post { destination.requestRectangleOnScreen(android.graphics.Rect(0,0,destination.width,destination.height),true) }
        showFeedback("Destino recebido. Toque em Traçar rota.")
    }
    private fun openRoute() {
        val value=destination.text.toString().trim()
        val shared=MapsRoute.sharedText(value)
        if(shared==null) { destination.error=if(value.isBlank()) "Digite o destino" else "Use um endereço ou link do Google Maps"; return }
        routeJob?.cancel()
        MapsRoute.intent(shared)?.let { launchRoute(it); return }
        routeJob=scope.launch {
            showFeedback("Preparando rota de moto…")
            val target=withContext(Dispatchers.IO) { runCatching { MapsRoute.resolve(shared) }.getOrNull() }
            if(destination.text.toString().trim()!=value) return@launch
            if(target==null) { showFeedback("Não consegui identificar o destino desse link. Use o endereço ou defina a rota no Google Maps."); return@launch }
            if(!visible) { showFeedback("Destino pronto. Toque em Traçar rota."); return@launch }
            launchRoute(target)
        }
    }
    private fun launchRoute(target:Intent) {
        try {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(destination.windowToken,0)
            startActivity(target)
            showFeedback("Rota de moto enviada ao Google Maps.")
        } catch(_:ActivityNotFoundException) { showFeedback("Instale ou ative o Google Maps para traçar a rota.") }
    }
    override fun onStart() {
        super.onStart(); visible=true
        checkAppUpdate()
        AutoBridgeService.simulatePreview(simulatePreview)
        surface?.let { AutoBridgeService.preview(it,preview.width,preview.height) }
        observer=scope.launch { AutoBridgeService.state.collect { s ->
        updateExampleVisibility(s)
        status.text=s.aa+"\n"+s.bike+if(s.problems.isNotBlank()) "\n"+s.problems else ""
        detail.text="AA: ${s.received} imagens · saída: ${s.sent} · painel: ${s.ack} respostas\n${s.codec}\n${s.screen}\n${s.problems}"
        controlsStatus.text=s.controls
        serverSettings.visibility=if(s.error.contains("servidor do Android Auto",ignoreCase=true)) View.VISIBLE else View.GONE
    } } }
    override fun onStop() { visible=false; AutoBridgeService.detachPreview(surface); observer?.cancel(); super.onStop() }
    private fun checkAppUpdate() {
        val p=GitHubUpdates.preferences(this)
        if(!p.getBoolean(GitHubUpdates.AUTO,true)) { updateNotice.visibility=View.GONE; return }
        val cached=p.getString("available-version",null)
        if(cached!=null && GitHubUpdates.newer(cached,com.example.opendash.BuildConfig.VERSION_NAME)) {
            updateNotice.text=L("Atualização disponível")+": "+cached; updateNotice.visibility=View.VISIBLE
        } else updateNotice.visibility=View.GONE
        if(updateCheck?.isActive==true) return
        val elapsed=System.currentTimeMillis()-p.getLong("last-check",0)
        if(elapsed in 0 until GitHubUpdates.INTERVAL) return
        // Throttle failures too; opening settings repeatedly must not flood the API.
        p.edit().putLong("last-check",System.currentTimeMillis()).apply()
        updateCheck=scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { GitHubUpdates.latest(this@AutoActivity) } }
            if(result.isSuccess) {
                val found=result.getOrNull()
                p.edit().putString("available-version",found?.version).apply()
                updateNotice.visibility=if(found==null) View.GONE else View.VISIBLE
                if(found!=null) updateNotice.text=L("Atualização disponível")+": "+found.version
            }
        }
    }
    override fun onDestroy() {
        controlEditorListener?.let { editorPreferences?.unregisterOnSharedPreferenceChangeListener(it) }
        val old=surface; surface=null
        AutoBridgeService.detachPreview(old) { old?.release() }
        scope.cancel(); super.onDestroy()
    }
    private fun start(bike:Boolean,newBike:Boolean=false) {
        feedback.visibility=View.GONE
        pendingBike=bike
        pendingNewBike=newBike
        val needed=mutableListOf<String>()
        if(bike) {
            if(!getSystemService(android.location.LocationManager::class.java).isLocationEnabled) { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return }
            needed+=Manifest.permission.ACCESS_FINE_LOCATION; needed+=Manifest.permission.ACCESS_COARSE_LOCATION
            if(Build.VERSION.SDK_INT>=33) needed+=Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if(Build.VERSION.SDK_INT>=33) needed+=Manifest.permission.POST_NOTIFICATIONS
        if(!PhoneAudio.input(this)) needed+=Manifest.permission.RECORD_AUDIO
        if(Build.VERSION.SDK_INT>=31 && (!PhoneAudio.input(this) || !PhoneAudio.output(this))) needed+=Manifest.permission.BLUETOOTH_CONNECT
        if(needed.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) { requestPermissions(needed.toTypedArray(),51); return }
        AutoBridgeService.command(this,pendingCommand())
    }
    private fun pendingCommand()=if(pendingNewBike) AutoBridgeService.NEW_BIKE else if(pendingBike) AutoBridgeService.BIKE else AutoBridgeService.START
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==51) {
            if(pendingBike && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED))) { showFeedback("Para escolher a moto, permita localização precisa e dispositivos próximos."); return }
            AutoBridgeService.command(this,pendingCommand())
            if(!PhoneAudio.input(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                showFeedback("Conexão iniciada sem microfone. Permita o acesso e reinicie a ponte para falar com o assistente.")
        }
    }
    private fun openSettings() {
        val launch=Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage("com.google.android.projection.gearhead")
        try { startActivity(launch) } catch(_:Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun buildControlsPage(traditional:LinearLayout,prefs:SharedPreferences):LinearLayout {
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(TextView(this@AutoActivity).apply { text="Comandos da moto"; textSize=22f; setTextColor(Color.WHITE); setPadding(0,dp(12),0,dp(12)) })
            addView(Switch(this@AutoActivity).apply {
                text="Usar joystick da moto"; setTextColor(Color.WHITE); isChecked=prefs.getBoolean("enabled",true)
                setOnCheckedChangeListener { _,value -> prefs.edit().putBoolean("enabled",value).apply() }
            })
            val explanation=TextView(this@AutoActivity).apply { textSize=13f; setTextColor(Color.parseColor("#A9BEB0")); setPadding(0,dp(8),0,dp(12)) }
            val menu=MenuControlsEditor(this@AutoActivity,prefs,{ callback -> chooseCarApplication(callback) },::showFeedback) { view -> spaceControls(view); styleButtons(view) }
            fun updateMode() {
                val enabled=prefs.getBoolean(MenuControls.ENABLED,false)
                traditional.visibility=if(enabled) View.GONE else View.VISIBLE
                menu.visibility=if(enabled) View.VISIBLE else View.GONE
                explanation.text=L(if(enabled) "Menu fechado: comandos únicos. Menu aberto: três perfis. Sem dois toques." else "Três perfis tradicionais com um ou dois toques. Suas configurações são preservadas.")
            }
            addView(RadioGroup(this@AutoActivity).apply {
                orientation=RadioGroup.HORIZONTAL
                for((value,label) in listOf(false to "Tradicional",true to "Modo menu")) addView(RadioButton(this@AutoActivity).apply {
                    id=View.generateViewId(); tag=value; text=L(label); setTextColor(Color.WHITE)
                    isChecked=prefs.getBoolean(MenuControls.ENABLED,false)==value
                },RadioGroup.LayoutParams(0,dp(52),1f))
                setOnCheckedChangeListener { group,id -> group.findViewById<RadioButton>(id)?.let { prefs.edit().putBoolean(MenuControls.ENABLED,it.tag as Boolean).apply(); updateMode() } }
            })
            addView(explanation); addView(traditional); addView(menu); updateMode()
        }
    }
    private fun buildControlEditor(container:LinearLayout,prefs:SharedPreferences) {
        BikeControlLayout.migrateSplitInputs(prefs)
        val editor=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun rebuild() {
            editor.removeAllViews()
            val intercom=BikeControlLayout.active(prefs)
            val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; contentDescription="Grade de controles da moto" }
            val details=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            editor.addView(grid); editor.addView(details)
            var tileRow=LinearLayout(this)
            val tiles=mutableListOf<Button>()
            for((index,button) in TripperControls.mediaActions.withIndex()) {
                val groupIndex=if(button.outside) index else index-TripperControls.mapActions.size
                if(groupIndex==0) grid.addView(TextView(this).apply {
                    text=if(button.outside) "Fora · mapa" else "Dentro · controles de mídia"
                    textSize=14f; setTextColor(Color.parseColor("#BBD5A0")); setPadding(0,dp(8),0,dp(8))
                })
                if(index==0) grid.addView(TextView(this).apply {
                    text="Os comandos de fora podem parar com a bolinha do mapa oculta."
                    textSize=12f; setTextColor(Color.parseColor("#FF7777")); setPadding(0,0,0,dp(8))
                })
                if(groupIndex%3==0) {
                    tileRow=LinearLayout(this).apply { isBaselineAligned=false; weightSum=3f; tag="control-row" }
                    grid.addView(tileRow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                }
                val section=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL; visibility=View.GONE; setPadding(dp(12),dp(12),dp(12),dp(12))
                    background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#18251F")); cornerRadius=dp(16).toFloat() }
                }
                if(button==TripperControls.Action.MAP_ENTER) section.addView(TextView(this).apply {
                    text="O centro de fora também abre os controles do próprio painel. Essa função nativa continua acontecendo."
                    setTextColor(Color.parseColor("#D7BC7D")); textSize=12f; setPadding(0,0,0,dp(10))
                })
                val header=Button(this).apply { tag="control-tile"; isAllCaps=false; maxLines=3; ellipsize=android.text.TextUtils.TruncateAt.END }
                tiles.add(header)
                fun read(double:Boolean)=BikeControlLayout.resolve(intercom,button,double,prefs.getString(BikeControlLayout.key(intercom,button,double),null))
                fun title() {
                    val command=read(false)
                    val actionLabel=if(command==BikeCommand.OPEN_OTHER) "Abrir "+(CarApplications.label(this@AutoActivity,prefs.getString(BikeControlLayout.appKey(intercom,button,false),null)) ?: "Outro") else command.label
                    val label="${button.label} · $actionLabel"
                    header.text=if(button==TripperControls.Action.MAP_ENTER) android.text.SpannableString("$label\nCompartilhado").apply {
                        setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FF7878")),label.length+1,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(.85f),label.length+1,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else label
                    header.contentDescription="${button.description}: ${read(false).label}"
                }
                title()
                header.setOnClickListener {
                    val open=section.visibility!=View.VISIBLE
                    for(i in 0 until details.childCount) details.getChildAt(i).visibility=View.GONE
                    tiles.forEach { it.isSelected=false; styleButtons(it) }
                    section.visibility=if(open) View.VISIBLE else View.GONE
                    header.isSelected=open; styleButtons(header)
                }
                tileRow.addView(header,LinearLayout.LayoutParams(0,dp(80),1f).apply { if(groupIndex%3!=0) marginStart=dp(8) })
                for(double in listOf(false,true)) {
                    val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(TextView(this).apply { text=if(double) "2 toques" else "1 toque"; textSize=13f; setTextColor(Color.WHITE); gravity=Gravity.CENTER_VERTICAL },LinearLayout.LayoutParams(dp(66),dp(52)))
                    val choices=BikeCommand.entries
                    val appPicker=Button(this).apply {
                        isAllCaps=false
                        contentDescription="Aplicativo: ${button.description}, ${if(double) "dois toques" else "um toque"}"
                    }
                    val appRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                    val appTest=Button(this).apply {
                        text=L("Testar"); isAllCaps=false
                        contentDescription="Testar aplicativo: ${button.description}, ${if(double) "dois toques" else "um toque"}"
                        setOnClickListener {
                            if(!AutoBridgeService.state.value.active) showFeedback("Inicie o Android Auto para testar o aplicativo.")
                            else {
                                val app=prefs.getString(BikeControlLayout.appKey(intercom,button,double),null)
                                if(app==null) showFeedback("Escolha o aplicativo em Controles da moto.")
                                else AutoBridgeService.testApplication(app)
                            }
                        }
                    }
                    fun updateAppPicker() {
                        appRow.visibility=if(read(double)==BikeCommand.OPEN_OTHER) View.VISIBLE else View.GONE
                        val packageName=prefs.getString(BikeControlLayout.appKey(intercom,button,double),null)
                        val appLabel=CarApplications.label(this@AutoActivity,packageName)
                        appPicker.text=L("Aplicativo:")+" "+(appLabel ?: L(if(packageName==null) "Escolher aplicativo" else "Não instalado · escolher outro"))
                    }
                    updateAppPicker()
                    appPicker.setOnClickListener {
                        chooseCarApplication { app ->
                            prefs.edit().putString(BikeControlLayout.appKey(intercom,button,double),app.packageName).apply()
                            updateAppPicker(); title()
                        }
                    }
                    val selector=Spinner(this).apply {
                        contentDescription="${button.description}: ${if(double) "dois toques" else "um toque"}"
                        adapter=object:ArrayAdapter<String>(this@AutoActivity,android.R.layout.simple_spinner_dropdown_item,choices.map { L(it.label) }) {
                            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View =
                                super.getView(position,convertView,parent).also { (it as TextView).setTextColor(Color.WHITE) }
                        }
                        setSelection(choices.indexOf(read(double)),false)
                        onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent:AdapterView<*>?) {}
                            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                                val chosen=choices[position]
                                if(chosen!=read(double)) {
                                    prefs.edit().putString(BikeControlLayout.key(intercom,button,double),chosen.name).apply()
                                    title(); updateAppPicker()
                                }
                            }
                        }
                    }
                    row.addView(selector,LinearLayout.LayoutParams(0,dp(52),1f)); section.addView(row)
                    appRow.addView(appPicker,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd=dp(8) })
                    appRow.addView(appTest,LinearLayout.LayoutParams(dp(82),dp(52)))
                    section.addView(appRow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                }
                val actions=LinearLayout(this)
                actions.addView(Button(this).apply { text="Testar ${button.label}"; isAllCaps=false; setOnClickListener { AutoBridgeService.testControl(button) } },LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(4) })
                section.addView(actions); details.addView(section)
                if(index==TripperControls.mapActions.lastIndex || index==TripperControls.mediaActions.lastIndex) {
                    while(tileRow.childCount<3) tileRow.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply { marginStart=dp(CONTROL_GAP) })
                }
            }
            editor.addView(TextView(this).apply {
                text="Maps e Spotify abrem o último app de navegação ou música usado no Android Auto."
                textSize=12f; setTextColor(Color.parseColor("#9DB0A3")); setPadding(0,dp(8),0,dp(8))
            })
            spaceControls(editor); styleButtons(editor); UiLanguage.apply(editor)
        }
        val profiles=LinearLayout(this).apply { contentDescription="Perfis de controles"; isBaselineAligned=false }
        fun highlightProfiles() {
            for(i in 0 until profiles.childCount) (profiles.getChildAt(i) as Button).let { it.text=ProfileNames.label(this,false,i+1); it.isSelected=i+1==BikeControlLayout.active(prefs); styleButtons(it) }
        }
        for(profile in 1..3) profiles.addView(Button(this).apply {
            text=ProfileNames.label(this@AutoActivity,false,profile); setTag(com.example.opendash.R.id.profile_name_literal,"profile-name"); isAllCaps=false; tag="menu-profile"; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
            setOnClickListener { prefs.edit().putInt(BikeControlLayout.PROFILE,profile).apply() }
        },LinearLayout.LayoutParams(0,dp(48),1f).apply { if(profile>1) marginStart=dp(8) })
        highlightProfiles(); container.addView(profiles,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        container.addView(Button(this).apply { text="Renomear perfil"; setOnClickListener { ProfileNames.edit(this@AutoActivity,false,BikeControlLayout.active(prefs)) } },LinearLayout.LayoutParams(-1,dp(48)))
        container.addView(TextView(this).apply {
            text="Dois toques: solte e pressione de novo, em até 3 s. Com ação dupla, o toque simples espera esse prazo."; setTextColor(Color.parseColor("#9DB0A3")); textSize=12f; setPadding(0,0,0,dp(12))
        })
        rebuild(); container.addView(editor)
        editorPreferences=prefs
        controlEditorListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key==BikeControlLayout.PROFILE || key?.startsWith("profile-name.traditional.")==true) { highlightProfiles(); rebuild() }
            if(key=="clean-projection") gpsBubbleSwitch.isChecked=prefs.getBoolean("clean-projection",true)
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        val footer=LinearLayout(this)
        footer.addView(Button(this).apply { text="Restaurar este perfil"; isAllCaps=false; setOnClickListener {
            if(!savePreviousSettings("Restaurar este perfil")) return@setOnClickListener
            val mode=BikeControlLayout.active(prefs)
            prefs.edit().apply { for(button in TripperControls.mediaActions) for(double in listOf(false,true)) {
                remove(BikeControlLayout.key(mode,button,double)); remove(BikeControlLayout.appKey(mode,button,double))
            } }.apply()
            rebuild()
        } },LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd=dp(4) })
        container.addView(footer,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(16) })
    }
    override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int) { surface=Surface(t); if(visible) AutoBridgeService.preview(surface,w,h) }
    override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int) { if(visible) AutoBridgeService.preview(surface,w,h) }
    override fun onSurfaceTextureUpdated(t:SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean {
        val old=surface; surface=null
        // Release the consumer only AFTER the EGL producer has detached on its own thread.
        AutoBridgeService.detachPreview(old) { old?.release(); t.release() }
        return false
    }
    private fun buildScreens(home:LinearLayout,routes:LinearLayout,settings:LinearLayout,previewControls:LinearLayout,page:Int) {
        val frame=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { v,insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets
            }
        }
        home.setOnApplyWindowInsetsListener(null)
        val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0D1511")); setPadding(dp(PAGE_INSET),dp(CONTROL_GAP),dp(PAGE_INSET),dp(CONTROL_GAP)) }
        val brandText=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL }
        repeat(2) { index ->
            val v=home.getChildAt(0) as TextView; home.removeView(v)
            v.textSize=if(index==0) 23f else 10.5f
            v.includeFontPadding=false
            if(index==0) { v.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); v.setAutoSizeTextTypeUniformWithConfiguration(18,23,1,android.util.TypedValue.COMPLEX_UNIT_SP) }
            else v.text="100% Gratuito\ne sem fins lucrativos."
            v.setTextColor(Color.parseColor(if(index==0) "#F3F8F5" else "#BBD5A0"))
            v.setPadding(0,0,0,0)
            if(index==0) brandText.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL; isBaselineAligned=false
                addView(v,LinearLayout.LayoutParams(-2,-2))
                addView(TextView(this@AutoActivity).apply { text=com.example.opendash.BuildConfig.VERSION_NAME; textSize=10f; setTextColor(Color.parseColor("#9DB0A3")); includeFontPadding=false },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(5) })
            },LinearLayout.LayoutParams(-1,-2))
            else brandText.addView(v,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        }
        brandText.addView(TextView(this).apply {
            text="Feito por Matheus Castellar"; textSize=9.5f; includeFontPadding=false
            setTextColor(Color.parseColor("#8DA99A"))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        val titleRow=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(ImageView(this@AutoActivity).apply { setImageResource(com.example.opendash.R.drawable.ic_route); contentDescription="Logo Rota Auto" },LinearLayout.LayoutParams(dp(32),dp(42)).apply { marginEnd=dp(8) })
            addView(brandText,LinearLayout.LayoutParams(0,-2,1f))
        }
        fun social(icon:Int,label:String,description:String,url:String) {
            titleRow.addView(FrameLayout(this@AutoActivity).apply {
                contentDescription=description
                tooltipText=contentDescription; isFocusable=true
                val highlight=android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,highlight,true)
                setBackgroundResource(highlight.resourceId)
                addView(ImageView(this@AutoActivity).apply { setImageResource(icon); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(5) })
                addView(TextView(this@AutoActivity).apply { text=label; textSize=10f; gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL; maxLines=2; includeFontPadding=false; setTextColor(Color.parseColor("#9DB0A3")); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,dp(28),Gravity.TOP).apply { topMargin=dp(34) })
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))) }
                    catch(_:ActivityNotFoundException) { showFeedback("Instale um navegador para abrir este link.") }
                }
            },LinearLayout.LayoutParams(dp(54),dp(64)).apply { marginStart=dp(CONTROL_GAP) })
        }
        social(com.example.opendash.R.drawable.ic_whatsapp,"Nosso grupo","Participar do grupo de testers no WhatsApp","https://chat.whatsapp.com/C5hLBC3MRAWK0Avu2F1eXS")
        social(com.example.opendash.R.drawable.ic_youtube,"YouTube","Abrir canal Castellar450 no YouTube","https://www.youtube.com/@Castellar450")
        header.addView(titleRow)
        home.removeViewAt(0) // The connection card already explains the main screen.
        home.removeView(feedback); header.addView(feedback)
        frame.addView(header)
        // Keep setup beside connection actions, collapsed until requested.
        val helpButton=(0 until home.childCount).map { home.getChildAt(it) }.filterIsInstance<Button>()
            .first { it.text=="Tutorial" }
        val help=home.getChildAt(home.indexOfChild(helpButton)+1)
        home.removeView(helpButton); home.removeView(help)
        val blackScreen=(0 until settings.childCount).map { settings.getChildAt(it) }.filterIsInstance<Button>().single { it.text=="Tela preta (sem bloquear)" }
        val blackHelp=settings.getChildAt(settings.indexOfChild(blackScreen)+1)
        settings.removeView(blackScreen); settings.removeView(blackHelp)
        blackScreen.text="Tela preta"
        blackScreen.contentDescription="Tela preta sem bloquear o celular"
        home.addView(LinearLayout(this).apply {
            addView(blackScreen,LinearLayout.LayoutParams(0,dp(BUTTON_HEIGHT),1f))
            addView(helpButton,LinearLayout.LayoutParams(0,dp(BUTTON_HEIGHT),1f))
        })
        home.addView(help)
        home.removeView(preview)
        val previewLabel=TextView(this).apply { text="PRÉVIA NO CELULAR"; textSize=11f; letterSpacing=.12f; setTextColor(Color.parseColor("#8DA99A")); setPadding(0,dp(CONTROL_GAP),0,dp(CONTROL_GAP)) }
        home.addView(previewLabel)
        val homePreview=FrameLayout(this)
        homePreview.addView(preview,FrameLayout.LayoutParams(-1,-1))
        homeExample=OfflinePanelPreview(this,false).also { homePreview.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        home.addView(homePreview,LinearLayout.LayoutParams(-1,dp(190)).apply { bottomMargin=dp(CONTROL_GAP) })
        home.addView(previewControls)
        status.textSize=14f; status.setLineSpacing(dp(3).toFloat(),1f); status.setPadding(dp(16),dp(16),dp(16),dp(16))
        status.background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#18251F")); setStroke(dp(1),Color.parseColor("#304338")); cornerRadius=dp(16).toFloat() }
        (status.layoutParams as LinearLayout.LayoutParams).bottomMargin=dp(16)
        // Keep every existing control and listener; only move its view into a focused page.
        val existing=(0 until settings.childCount).map { settings.getChildAt(it) }
        fun index(label:String)=existing.indexOfFirst { (it as? TextView)?.text==label }.also { check(it>=0) { "Missing settings section: $label" } }
        fun part(start:String,end:String)=existing.subList(index(start),index(end)).toList()
        fun original(label:String)=existing.filterIsInstance<Button>().single { it.text==label }
        fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun heading(title:String,subtitle:String="")=column().apply {
            addView(TextView(this@AutoActivity).apply { text=title; textSize=22f; setTextColor(Color.parseColor("#F3F8F5")); typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
            if(subtitle.isNotBlank()) addView(TextView(this@AutoActivity).apply { text=subtitle; textSize=12f; setTextColor(Color.parseColor("#9DB0A3")); setPadding(0,dp(5),0,dp(14)) })
        }
        fun body(title:String,subtitle:String,children:List<View>)=column().apply {
            addView(heading(title,subtitle))
            children.forEach { child -> (child.parent as? ViewGroup)?.removeView(child); addView(child) }
        }
        fun link(title:String,subtitle:String,action:()->Unit):View=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            tag="settings-link"; contentDescription=L(title)+". "+L(subtitle)
            isClickable=true; isFocusable=true; minimumHeight=dp(68)
            setPadding(dp(15),dp(13),dp(15),dp(13))
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF),
                android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#101C15")); cornerRadius=dp(12).toFloat(); setStroke(dp(1),Color.parseColor("#283A2F")) },null)
            addView(column().apply {
                addView(TextView(this@AutoActivity).apply { text=title; textSize=15f; setTextColor(Color.parseColor("#ECF4EE")); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                addView(TextView(this@AutoActivity).apply { text=subtitle; textSize=12f; setTextColor(Color.parseColor("#A3B7A9")); setPadding(0,dp(4),0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(TextView(this@AutoActivity).apply { text="›"; textSize=23f; setTextColor(Color.parseColor("#9DB0A3")); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(22),-2))
            setOnClickListener { action() }
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(CONTROL_GAP) }
        }
        val mapSettings=existing.take(index("Áudio"))
        val audioSettings=part("Áudio","Imagem no painel").drop(1)
        val imageSettings=part("Imagem no painel","Logo · modos B e D")
        val geometryIndex=imageSettings.indexOfFirst { it.contentDescription=="Modo de imagem do painel" }
        check(geometryIndex>0)
        val barSettings=imageSettings.subList(1,geometryIndex)
        val geometrySettings=imageSettings.drop(geometryIndex)+part("Fundo desfocado · modos B e D","Fluidez do painel")
        val logoSettings=part("Logo · modos B e D","Widget de informações").drop(1)
        val widgetSettings=part("Widget de informações","Fundo desfocado · modos B e D").drop(1)
        val videoSettings=part("Fluidez do painel","Controles da moto").drop(1)
        val backupSettings=part("Exportar configurações","Créditos")
        val credits=original("Créditos")
        val updatePanel=UpdatePanel(this,scope)
        val battery=original("Ajustes de bateria do aplicativo")
        val detailsButton=original("Detalhes da conexão")
        settings.removeAllViews()
        val panel=column().apply {
            addView(heading("Painel","Veja o resultado enquanto personaliza."))
            addView(link("Imagem e enquadramento","Formato, tamanho, posição e desfoque") { selectPage(10) })
            addView(link("Logo da moto","Marca, tamanho e posição") { selectPage(11) })
            addView(link("Informações do celular","Hora, bateria e rede móvel") { selectPage(12) })
            addView(link("Navegação no painel","Bolinha, mapa dia/noite e moto parada") { selectPage(13) })
            addView(link("Barra do Android Auto","Exibição temporária e duração") { selectPage(14) })
        }
        val geometry=body("Imagem e enquadramento","Ajustes aplicados na hora no painel.",geometrySettings)
        val logo=body("Logo da moto","Opcional e independente do widget.",logoSettings)
        val widget=body("Informações do celular","A rede exibida é a móvel, não o Wi-Fi da moto.",widgetSettings)
        val navigation=body("Navegação no painel","Informações nativas do painel e aparência do mapa.",mapSettings)
        val bar=body("Barra do Android Auto","Controle a exibição temporária.",barSettings)
        bar.addView(Button(this).apply { text="Mostrar barra"; setOnClickListener {
            if(panelExample?.visibility==View.VISIBLE) panelExample?.showBar() else AutoBridgeService.showBar()
        } })
        val video=body("Vídeo","Predefinições para o uso diário.",videoSettings)
        val updates=body("Atualizações","Buscar novas versões no GitHub",listOf(updatePanel.view))
        val audio=body("Som e microfone","Preferências de áudio reunidas.",audioSettings)
        val backups=body("Salvar e recuperar ajustes","Seus perfis e sua personalização juntos.",backupSettings)
        val advanced=body("Avançado","Detalhes técnicos e ajustes do Android.",listOf(detailsButton,detail,battery))
        advanced.addView(Button(this).apply { text="Desenvolvedor"; visibility=View.GONE; setOnClickListener { inputCaptureDialog() } })
        val connection=column().apply {
            addView(heading("Conexão com a moto","A reconexão continua enquanto o Android Auto estiver ativo."))
            addView(Button(this@AutoActivity).apply { text="Reconectar Moto"; setOnClickListener { start(true) } })
            addView(Button(this@AutoActivity).apply { text="Iniciar nova conexão"; setOnClickListener { start(true,newBike=true) } })
            addView(link("Opções avançadas","Detalhes da conexão e ajustes de bateria") { selectPage(24) })
        }
        val about=column().apply {
            addView(heading("Sobre o Rota Auto","100% gratuito e sem fins lucrativos."))
            addView(TextView(this@AutoActivity).apply { text="Feito por Matheus Castellar"; textSize=14f; setTextColor(Color.parseColor("#A3B7A9")); setPadding(0,0,0,dp(16)) })
            addView(credits)
        }
        settings.addView(heading("Configurações","O essencial primeiro. Detalhes em Avançado."))
        settings.addView(link("Vídeo","Fluidez e taxa de transmissão") { selectPage(20) })
        settings.addView(link("Som e microfone","Saída de áudio, captura e ganho") { selectPage(21) })
        settings.addView(link("Conexão com a moto","Rede salva e reconexão") { selectPage(22) })
        settings.addView(link("Salvar e recuperar ajustes","Exportar, importar e desfazer alterações") { selectPage(23) })
        settings.addView(link("Atualizações","Buscar novas versões no GitHub") { selectPage(26) })
        settings.addView(link("Avançado","Detalhes técnicos e ajustes do Android") { selectPage(24) })
        settings.addView(link("Sobre o Rota Auto","Créditos e comunidade") { selectPage(25) })
        routes.addView(heading("Para onde vamos?","Você também pode definir a rota no Google Maps."),0)
        destination.background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#18251F")); setStroke(dp(1),Color.parseColor("#3B5747")); cornerRadius=dp(14).toFloat() }
        destination.setPadding(dp(16),0,dp(16),0); destination.textSize=15f
        (destination.layoutParams as LinearLayout.LayoutParams).bottomMargin=dp(12)
        val pages=linkedMapOf(0 to home,1 to routes,2 to settings,3 to panel,4 to controlsPage,
            10 to geometry,11 to logo,12 to widget,13 to navigation,14 to bar,20 to video,21 to audio,22 to connection,23 to backups,24 to advanced,25 to about,26 to updates)
        pageParents.clear()
        for(id in 10..14) pageParents[id]=3
        for(id in 20..26) pageParents[id]=2
        val holder=FrameLayout(this)
        val settingsPreview=FrameLayout(this).apply { contentDescription="Simulação do painel sem toque"; setBackgroundColor(Color.parseColor("#0D3024")) }
        panelExample=OfflinePanelPreview(this,true).also { settingsPreview.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val panelPages=setOf(3,10,11,12,13,14)
        val previewHosts=mutableMapOf<Int,LinearLayout>()
        val scrolls=pages.mapValues { (id,content) ->
            content.setPadding(dp(PAGE_INSET),dp(12),dp(PAGE_INSET),dp(16)); content.setBackgroundColor(Color.TRANSPARENT)
            pageParents[id]?.let { parent ->
                content.addView(LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; isBaselineAligned=false
                    tag="page-back"; isClickable=true; isFocusable=true
                    setPadding(0,0,dp(16),0)
                    contentDescription=L(if(parent==3) "Voltar ao painel" else if(parent==0) "Voltar à conexão" else "Voltar aos ajustes")
                    val arrow=ImageView(this@AutoActivity).apply {
                        setImageDrawable(NavigationIcon("back",Color.parseColor("#A3B7A9")))
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    addView(arrow,LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginEnd=dp(8) })
                    val label=TextView(this@AutoActivity).apply {
                        text=L(if(parent==3) "Painel" else if(parent==0) "Conectar" else "Ajustes")
                        textSize=26f; includeFontPadding=false; setTextColor(Color.parseColor("#A3B7A9"))
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    addView(label,LinearLayout.LayoutParams(-2,-2))
                    addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
                        val ink=Rect(); val value=label.text.toString()
                        label.paint.getTextBounds(value,0,value.length,ink)
                        arrow.translationY=label.top+label.baseline+ink.exactCenterY()-(arrow.top+arrow.height/2f)
                    }
                    setOnClickListener { selectPage(parent) }
                    layoutParams=LinearLayout.LayoutParams(-2,dp(48)).apply { bottomMargin=dp(12) }
                },0)
            }
            spaceControls(content); styleButtons(content)
            val scroll=ScrollView(this).apply { isFillViewport=true; addView(content); tag="page-scroll-$id" }
            val pageView:View=if(id in panelPages) LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                val previewHost=column(); previewHosts[id]=previewHost
                addView(previewHost,LinearLayout.LayoutParams(-1,dp(158)).apply { marginStart=dp(PAGE_INSET); marginEnd=dp(PAGE_INSET); topMargin=dp(8) })
                addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            } else scroll
            pageView.tag="screen-$id"
            holder.addView(pageView,FrameLayout.LayoutParams(-1,-1)); pageView
        }
        frame.addView(holder,LinearLayout.LayoutParams(-1,0,1f))
        val tabs=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(8),dp(6),dp(8)); setBackgroundColor(Color.parseColor("#15221B")) }
        val tabSpecs=listOf(Triple(0,"Conectar","wifi"),Triple(1,"Navegar","navigate"),Triple(3,"Painel","panel"),Triple(4,"Comandos","controls"),Triple(2,"Ajustes","settings"))
        val buttons=tabSpecs.map { (id,label,icon) ->
            Button(this).apply {
                text=label; isAllCaps=false; textSize=10f; includeFontPadding=false; stateListAnimator=null; minWidth=0; minimumWidth=0
                setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                gravity=Gravity.CENTER; setPadding(dp(2),dp(7),dp(2),dp(5)); compoundDrawablePadding=dp(4)
                setCompoundDrawables(null,NavigationIcon(icon,Color.parseColor("#9BB5A6")).apply { setBounds(0,0,dp(20),dp(20)) },null,null)
                setOnClickListener { selectPage(id) }
                tabs.addView(this,LinearLayout.LayoutParams(0,dp(58),1f).apply { marginStart=dp(2); marginEnd=dp(2) })
            }
        }
        selectPage={ requested ->
            selectedPage=requested.takeIf { it in pages } ?: 0
            if(selectedPage==26) updatePanel.open()
            val simulation=selectedPage in panelPages
            if(touching) { AutoBridgeService.touch(MotionEvent.ACTION_CANCEL,lastTouch.first,lastTouch.second); touching=false }
            simulatePreview=simulation; AutoBridgeService.simulatePreview(simulation)
            val parent=if(simulation) settingsPreview else homePreview
            if(preview.parent!==parent) {
                (preview.parent as? ViewGroup)?.removeView(preview)
                parent.addView(preview,0,FrameLayout.LayoutParams(-1,-1))
            }
            if(simulation) {
                val host=previewHosts.getValue(selectedPage)
                if(settingsPreview.parent!==host) { (settingsPreview.parent as? ViewGroup)?.removeView(settingsPreview); host.addView(settingsPreview,LinearLayout.LayoutParams(-1,-1)) }
            }
            scrolls.forEach { (id,v) -> v.visibility=if(id==selectedPage) View.VISIBLE else View.GONE }
            val active=pageParents[selectedPage] ?: selectedPage
            buttons.forEachIndexed { i,b ->
                b.isSelected=tabSpecs[i].first==active
                val color=Color.parseColor(if(b.isSelected) "#B9F665" else "#9BB5A6")
                b.setTextColor(color); b.compoundDrawables[1]?.setTint(color); b.backgroundTintList=null
                b.background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(11).toFloat(); setColor(Color.parseColor(if(b.isSelected) "#294837" else "#15221B")) }
            }
        }
        tabs.addView(UiLanguage.flagButton(this),LinearLayout.LayoutParams(dp(44),dp(48)).apply { marginStart=dp(4) })
        frame.addView(tabs)
        setContentView(frame); selectPage(page); updateExampleVisibility(AutoBridgeService.state.value); UiLanguage.apply(frame)
    }
    private fun updateExampleVisibility(state:AutoState) {
        val show=!state.active || state.received==0L
        listOfNotNull(homeExample,panelExample).forEach { it.visibility=if(show) View.VISIBLE else View.GONE }
    }
    private fun chooseCarApplication(selected:(CarApplications.App)->Unit) {
        scope.launch {
            val apps=withContext(Dispatchers.IO) { runCatching { CarApplications.installed(this@AutoActivity) }.getOrDefault(emptyList()) }
            if(isFinishing || isDestroyed) return@launch
            if(apps.isEmpty()) { showFeedback("Nenhum aplicativo compatível com Android Auto encontrado."); return@launch }
            AlertDialog.Builder(this@AutoActivity).setTitle(L("Escolher aplicativo"))
                .setItems(apps.map { it.label }.toTypedArray()) { _,index -> selected(apps[index]) }
                .setNegativeButton(L("Cancelar"),null).show()
        }
    }
    private fun savePreviousSettings(reason:String):Boolean = try {
        SettingsRecovery.save(this,reason); true
    } catch(_:Exception) {
        showFeedback("Não foi possível guardar a configuração anterior. Nada foi alterado."); false
    }
    private fun restorePreviousSettings() {
        val saved=try { SettingsRecovery.read(this) } catch(_:Exception) {
            showFeedback("Não foi possível ler a configuração anterior."); return
        }
        if(saved==null) { showFeedback("Ainda não há uma configuração anterior salva."); return }
        val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(saved.at))
        AlertDialog.Builder(this).setTitle(L("Voltar à configuração anterior"))
            .setMessage(L("Cópia salva em")+" $date\n"+L("Antes de:")+" "+L(saved.reason)+"\n\n"+
                L("Restaura os ajustes e os três perfis. A conexão será parada. A configuração atual será guardada para você poder voltar a ela."))
            .setNegativeButton(L("Cancelar"),null).setPositiveButton(L("Restaurar")) { _,_ ->
                AutoBridgeService.command(this,AutoBridgeService.STOP)
                runCatching { SettingsRecovery.restore(this,saved) }.fold({
                    intent.putExtra("settings-imported","Configuração anterior restaurada. Você já pode ligar o Android Auto."); recreate()
                },{ showFeedback("Não foi possível restaurar a configuração anterior.") })
            }.show()
    }
    @Deprecated("Android back compatibility")
    override fun onBackPressed() {
        if(selectedPage!=0) selectPage(pageParents[selectedPage] ?: 0) else super.onBackPressed()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putInt("page",selectedPage); outState.putString("pending-export",pendingExport); super.onSaveInstanceState(outState)
    }
    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==712) {
            val payload=pendingInputLog ?: BikeInputCapture.snapshot(); pendingInputLog=null
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null) scope.launch {
                val ok=withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }.isSuccess }
                showFeedback(if(ok) "Log de inputs salvo." else "Não foi possível salvar o arquivo.")
            }
            return
        }
        if(requestCode==711) {
            val uri=data?.data ?: return
            if(resultCode!=RESULT_OK) return
            scope.launch {
                val parsed=withContext(Dispatchers.IO) { runCatching {
                    val raw=checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val bytes=ByteArray(SettingsImport.MAX_BYTES+1); var size=0
                        while(size<bytes.size) { val n=input.read(bytes,size,bytes.size-size); if(n<0) break; if(n>0) size+=n }
                        require(size<=SettingsImport.MAX_BYTES) { "Arquivo grande demais." }
                        String(bytes,0,size,Charsets.UTF_8)
                    }
                    SettingsImport.parse(raw)
                } }
                if(isFinishing || isDestroyed) return@launch
                parsed.fold({ backup ->
                    fun confirm(target:Int) {
                        val message=if(backup.profileOnly) "Substituir os comandos do Perfil $target?" else "Substituir configurações e todos os perfis? A conexão atual será parada para aplicar o backup."
                        AlertDialog.Builder(this@AutoActivity).setTitle(L("Importar configurações")).setMessage(L(message))
                            .setNegativeButton(L("Cancelar"),null).setPositiveButton(L("Importar")) { _,_ ->
                                if(!backup.profileOnly) AutoBridgeService.command(this@AutoActivity,AutoBridgeService.STOP)
                                runCatching { SettingsImport.apply(this@AutoActivity,backup,target) }.fold({
                                    intent.putExtra("settings-imported",if(backup.profileOnly) "Perfil $target importado." else "Configurações importadas. Você já pode ligar o Android Auto.")
                                    recreate()
                                },{ showFeedback("Não foi possível salvar as configurações.") })
                            }.show()
                    }
                    if(backup.profileOnly) AlertDialog.Builder(this@AutoActivity).setTitle(L("Importar para qual perfil?"))
                        .setItems(arrayOf(L("Perfil 1"),L("Perfil 2"),L("Perfil 3"))) { _,index -> confirm(index+1) }.setNegativeButton(L("Cancelar"),null).show()
                    else confirm(backup.active)
                },{ showFeedback("Arquivo inválido ou incompatível. Nenhuma configuração foi alterada.") })
            }
            return
        }
        if(requestCode!=710) return
        val payload=pendingExport; pendingExport=null
        val uri=data?.data
        if(resultCode!=RESULT_OK || payload==null || uri==null) return
        scope.launch {
            val saved=withContext(Dispatchers.IO) { runCatching {
                checkNotNull(contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            }.isSuccess }
            showFeedback(if(saved) "Configurações exportadas." else "Não foi possível salvar o arquivo.")
        }
    }
    private fun inputCaptureDialog() {
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        body.addView(Button(this).apply { text=L("Diagnóstico guiado"); setOnClickListener { startActivity(Intent(this@AutoActivity,DiagnosticActivity::class.java)) } })
        body.addView(TextView(this).apply { text=L("Registra todos os pacotes recebidos pelo app, inclusive desconhecidos. Uma nova captura substitui a anterior."); setTextColor(Color.WHITE); textSize=14f })
        val status=TextView(this).apply { setTextColor(Color.WHITE); textSize=14f; setPadding(0,dp(12),0,dp(12)); minLines=3 }
        body.addView(status)
        body.addView(Switch(this).apply {
            text=L("Bloquear ações durante a captura"); setTextColor(Color.WHITE); isChecked=BikeInputCapture.state.value.blockActions
            setOnCheckedChangeListener { _,value -> BikeInputCapture.setBlockActions(value) }
        })
        val row=LinearLayout(this)
        val start=Button(this).apply { text=L("Iniciar captura"); setOnClickListener {
            BikeInputCapture.start()
            val p=getSharedPreferences("auto-controls-options",MODE_PRIVATE)
            BikeInputCapture.note("Início: "+AutoBridgeService.state.value.bike+"; ocultar bola="+p.getBoolean("clean-projection",true)+"; perfil="+BikeControlLayout.active(p))
        } }
        val stop=Button(this).apply { text=L("Parar captura"); setOnClickListener { BikeInputCapture.stop() } }
        row.addView(start,LinearLayout.LayoutParams(0,dp(BUTTON_HEIGHT),1f)); row.addView(stop,LinearLayout.LayoutParams(0,dp(BUTTON_HEIGHT),1f)); body.addView(row)
        val save=Button(this).apply { text=L("Salvar log"); setOnClickListener {
            pendingInputLog=BikeInputCapture.snapshot()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"RotaAuto-inputs.txt"),712)
        } }
        body.addView(save,LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        val connection=TextView(this).apply { setTextColor(Color.LTGRAY); textSize=13f }; body.addView(connection)
        body.addView(TextView(this).apply { text=L("Teste das bolinhas · 0 a 255"); setTextColor(Color.WHITE); textSize=17f; setPadding(0,dp(16),0,dp(8)) })
        body.addView(TextView(this).apply { text=L("Com a moto parada e conectada: inicie a captura, escolha um número e aperte os botões do guidão. Invisível no catálogo não garante comandos funcionando no seu painel."); setTextColor(Color.LTGRAY); textSize=13f })
        fun glyphLabel(code:Int)="%03d · 0x%02X · %s".format(java.util.Locale.ROOT,code,code,L(ManeuverProbe.category(code)))
        val glyph=Button(this).apply { text=L("Escolher símbolo"); setOnClickListener {
            AlertDialog.Builder(this@AutoActivity).setTitle(L("Escolher símbolo"))
                .setSingleChoiceItems(Array(256) { glyphLabel(it) },BikeInputCapture.state.value.glyph ?: -1) { d,index -> BikeInputCapture.selectGlyph(index); d.dismiss() }
                .setNegativeButton(L("Fechar"),null).show()
        } }; body.addView(glyph,LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        fun probeButton(label:String,action:()->Unit)=Button(this).apply { text=L(label); setOnClickListener { action() } }
        fun probeRow(vararg buttons:Button) { body.addView(LinearLayout(this).apply { buttons.forEach { addView(it,LinearLayout.LayoutParams(0,dp(BUTTON_HEIGHT),1f)) } }) }
        val previous=probeButton("Anterior") { BikeInputCapture.selectGlyph(((BikeInputCapture.state.value.glyph ?: 0)-1).coerceAtLeast(0)) }
        val next=probeButton("Próximo") { BikeInputCapture.selectGlyph(((BikeInputCapture.state.value.glyph ?: -1)+1).coerceAtMost(255)) }
        probeRow(previous,next)
        val visible=probeButton("Seta · 9") { BikeInputCapture.selectGlyph(9) }
        val hidden=probeButton("Oculto · 90") { BikeInputCapture.selectGlyph(90) }
        val last=probeButton("Oculto · 255") { BikeInputCapture.selectGlyph(255) }
        probeRow(visible,hidden,last)
        val normal=probeButton("Restaurar símbolo normal") { BikeInputCapture.selectGlyph(null) }; body.addView(normal,LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        val context=probeButton("Testando: Fora") {
            BikeInputCapture.context(if(BikeInputCapture.state.value.context=="Fora") "Dentro" else "Fora")
        }; body.addView(context,LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        val observation=probeButton("Anotar resultado") {
            val options=arrayOf("Bolinha oculta","Bolinha visível","Comandos recebidos","Apertei e não chegou comando")
            AlertDialog.Builder(this@AutoActivity).setTitle(L("Anotar resultado")).setItems(options.map { L(it) }.toTypedArray()) { _,i -> BikeInputCapture.observation(options[i]) }.show()
        }; body.addView(observation,LinearLayout.LayoutParams(-1,dp(BUTTON_HEIGHT)))
        body.addView(TextView(this).apply { text=L("Teste temporário: Parar captura restaura o símbolo normal e libera os atalhos. Fechar a janela mantém o teste. O bloqueio não impede ações internas da moto. Log limitado a 2.000 registros e 2 milhões de caracteres, sem cortar pacotes retidos."); setTextColor(Color.LTGRAY); textSize=12f; setPadding(0,dp(8),0,0) })
        spaceControls(body); styleButtons(body)
        val dialog=AlertDialog.Builder(this).setTitle(L("Desenvolvedor · inputs da moto")).setView(ScrollView(this).apply { addView(body) }).setNegativeButton(L("Fechar"),null).create()
        val job=scope.launch { BikeInputCapture.state.collect { s ->
            start.isEnabled=!s.active; stop.isEnabled=s.active; save.isEnabled=s.available
            listOf(start,stop,save).forEach { it.alpha=if(it.isEnabled) 1f else .4f }
            listOf(glyph,previous,next,visible,hidden,last,normal,context,observation).forEach { it.isEnabled=s.active; it.alpha=if(s.active) 1f else .4f }
            glyph.text=s.glyph?.let { glyphLabel(it) } ?: L("Escolher símbolo")
            context.text=L(if(s.context=="Fora") "Testando: Fora" else "Testando: Dentro")
            status.text=(if(s.count<0) L("Log anterior disponível") else L(if(s.active) "Capturando" else "Captura parada")+" · "+s.count+" "+L("pacotes"))+"\n"+L("Botões recebidos neste teste")+": "+s.buttons+"\n"+s.last+
                if(s.diskError) "\n"+L("Não foi possível atualizar o log no celular. Use Salvar log.") else ""
        } }
        val connectionJob=scope.launch { AutoBridgeService.state.collect { connection.text=it.bike } }
        dialog.setOnDismissListener { job.cancel(); connectionJob.cancel() }; dialog.show()
    }
    private fun L(value:String)=UiLanguage.text(this,value)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun spaceControls(view:View) {
        if(view is LinearLayout) {
            view.isBaselineAligned=false
            val children=(0 until view.childCount).map { view.getChildAt(it) }
            val buttons=children.filter { it is Button && it !is CompoundButton }
            val buttonRow=view.orientation==LinearLayout.HORIZONTAL && buttons.isNotEmpty() && (buttons.size==children.size || view.tag=="control-row")
            buttons.forEachIndexed { index,child ->
                val p=child.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
                p.height=dp(if(child.tag=="control-tile") 80 else BUTTON_HEIGHT)
                p.topMargin=0; p.bottomMargin=0; p.marginStart=0; p.marginEnd=0
                if(view.orientation==LinearLayout.VERTICAL) {
                    p.width=LinearLayout.LayoutParams.MATCH_PARENT
                    p.bottomMargin=dp(CONTROL_GAP)
                } else if(buttonRow && index>0) {
                    p.marginStart=dp(CONTROL_GAP)
                }
                child.layoutParams=p
            }
            if(buttonRow && (view.parent as? LinearLayout)?.orientation==LinearLayout.VERTICAL) {
                (view.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
                    p.topMargin=0; p.bottomMargin=dp(CONTROL_GAP); view.layoutParams=p
                }
            }
        }
        if(view is ViewGroup) for(i in 0 until view.childCount) spaceControls(view.getChildAt(i))
    }
    private fun styleButtons(view:View) {
        if(view is Button && view !is CompoundButton) {
            val primary=view.text in listOf("Ligar Android Auto","Traçar rota")
            val stop=view.text=="Parar"
            val fill=Color.parseColor(if(primary) "#B9F665" else if(stop) "#332326" else if(view.isSelected) "#293C24" else "#1B2A22")
            val shape=android.graphics.drawable.GradientDrawable().apply {
                cornerRadius=dp(14).toFloat(); setColor(fill)
                if(!primary) setStroke(dp(1),Color.parseColor(if(stop) "#684247" else if(view.isSelected) "#B9F665" else "#344A3C"))
            }
            view.backgroundTintList=null
            view.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF),shape,null)
            view.setTextColor(Color.parseColor(if(primary) "#16220D" else if(stop) "#F2B5BA" else if(view.isSelected) "#CBF594" else "#E5F0E9"))
            val tile=view.tag=="control-tile"
            view.isAllCaps=false; view.textSize=if(view.tag=="server-gear") 22f else if(tile || view.tag=="menu-profile") 12f else 14f; view.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            view.includeFontPadding=false; view.gravity=Gravity.CENTER
            view.setPadding(dp(16),dp(CONTROL_GAP),dp(16),dp(CONTROL_GAP)); view.minHeight=dp(BUTTON_HEIGHT)
            if(view.tag=="menu-profile") view.setPadding(dp(4),dp(CONTROL_GAP),dp(4),dp(CONTROL_GAP))
            view.stateListAnimator=null
        }
        if(view is CompoundButton) {
            val tint=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.parseColor("#B9F665"),Color.parseColor("#718579")))
            if(view is Switch) { view.thumbTintList=tint; view.minHeight=dp(48); view.switchPadding=dp(12) }
            else view.buttonTintList=tint
        }
        if(view is SeekBar) { view.progressTintList=android.content.res.ColorStateList.valueOf(Color.parseColor("#B9F665")); view.thumbTintList=view.progressTintList; view.setPadding(dp(6),dp(10),dp(6),dp(10)); view.minimumHeight=dp(44) }
        if(view is ViewGroup) for(i in 0 until view.childCount) styleButtons(view.getChildAt(i))
    }
    private fun showFeedback(s:String) { feedback.text=s; feedback.visibility=View.VISIBLE }
}


