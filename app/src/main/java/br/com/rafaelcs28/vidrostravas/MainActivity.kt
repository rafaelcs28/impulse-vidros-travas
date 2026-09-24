package br.com.rafaelcs28.vidrostravas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Tela unica: pede a autorizacao do Shizuku, liga a captura e mostra o que esta chegando.
 *
 * O aplicativo so LE o carro. Quem instala pode desinstalar quando o diagnostico terminar, e o
 * arquivo com tudo o que foi capturado fica disponivel pelo botao de compartilhar.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var registro: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val PEDIDO_SHIZUKU = 1001

    private var aguardandoEnvio = false
    private var marcoEnvio = 0L

    /** Botao que so existe quando ha versao nova; fica no topo, destacado. */
    private var botaoAtualizar: Button? = null
    private var barra: ProgressBar? = null
    private var andamento: TextView? = null

    /** Painel proprio da autorizacao do Shizuku - fora da linha de status, que e reescrita. */
    private var painelAutorizacao: LinearLayout? = null
    private var tituloAutorizacao: TextView? = null
    private var textoAutorizacao: TextView? = null
    private var botaoAutorizacao: Button? = null
    private var situacaoMostrada = ""
    private var atualizando = false

    private val aoResponder = Shizuku.OnRequestPermissionResultListener { pedido, resultado ->
        if (pedido == PEDIDO_SHIZUKU) {
            val concedida = resultado == android.content.pm.PackageManager.PERMISSION_GRANTED
            CaptureService.avisarAutorizacao(this, concedida)
            // Na thread da tela, sem depender de em qual thread o Shizuku entrega o resultado:
            // mexer em view fora dela derruba o aplicativo.
            runOnUiThread {
                if (concedida) iniciar()
                situacaoMostrada = ""
                revisarAutorizacao()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#0b0e12"))
        }

        raiz.addView(TextView(this).apply {
            // A versao fica no titulo, visivel sem procurar: quem esta ajudando de longe precisa
            // conseguir dizer em qual esta, e depois de atualizar precisa conseguir confirmar que
            // trocou. Sem isso, "atualizou?" vira uma conversa de tentativa e erro.
            text = "Impulse Vidros e Travas  " + BuildConfig.VERSION_NAME
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        raiz.addView(TextView(this).apply {
            text = "Captura o que o carro informa sobre vidros, teto, portas, tranca e retrovisor. " +
                "Nao muda nada no carro."
            textSize = 14f
            setTextColor(Color.parseColor("#93a3b1"))
            setPadding(0, 8, 0, 20)
        })

        // Destacado e no topo: quem esta ajudando nao tem por que cacar link, e captura feita numa
        // versao com defeito e trabalho perdido dos dois lados.
        botaoAtualizar = Button(this).apply {
            text = "Atualizar"
            visibility = android.view.View.GONE
            setBackgroundColor(Color.parseColor("#4ade80"))
            setTextColor(Color.parseColor("#05140a"))
            setOnClickListener { instalarAtualizacao() }
        }
        raiz.addView(botaoAtualizar)

        // Progresso em views proprias, e nao na linha de status: a linha de status e reescrita a
        // cada segundo com o andamento da captura, entao os avisos da atualizacao apareciam e eram
        // apagados antes de dar tempo de ler. De fora parecia que o toque no botao nao surtiu efeito.
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        raiz.addView(barra)

        andamento = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#4ade80"))
            visibility = android.view.View.GONE
            setPadding(0, 4, 0, 8)
        }
        raiz.addView(andamento)

        // A autorizacao ganhou painel proprio pelo mesmo motivo do progresso da atualizacao: a
        // linha de status e reescrita a cada segundo com o andamento da captura. O "Pedindo
        // autorizacao ao Shizuku" que ficava ali sumia em um segundo, e quem abria o app via so
        // "Carro ... - 0 eventos", sem saber que faltava autorizar. Um carro ficou assim por um
        // dia inteiro, abrindo o app sete vezes.
        tituloAutorizacao = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#fbbf24"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textoAutorizacao = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#f5e9c8"))
            setPadding(0, 10, 0, 18)
        }
        botaoAutorizacao = Button(this).apply {
            setBackgroundColor(Color.parseColor("#fbbf24"))
            setTextColor(Color.parseColor("#231a05"))
        }
        painelAutorizacao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.parseColor("#2a2110"))
            visibility = android.view.View.GONE
            addView(tituloAutorizacao)
            addView(textoAutorizacao)
            addView(botaoAutorizacao)
        }
        raiz.addView(painelAutorizacao)

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#4ade80"))
            text = "iniciando..."
        }
        raiz.addView(status)

        val botoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 12)
        }
        botoes.addView(Button(this).apply {
            text = "Enviar captura"
            setOnClickListener { enviarCaptura() }
        })
        botoes.addView(Button(this).apply {
            text = "Limpar"
            setOnClickListener { limpar() }
        })
        botoes.addView(Button(this).apply {
            text = "Parar"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "parado"
            }
        })
        raiz.addView(botoes)

        registro = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#cfe0ec"))
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        raiz.addView(ScrollView(this).apply {
            addView(registro)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        raiz.gravity = Gravity.TOP
        setContentView(raiz)

        // Antes de qualquer coisa, e mesmo que o Shizuku nao coopere: assim uma instalacao que
        // trava na autorizacao aparece daqui como travada, e nao como inexistente.
        CaptureService.avisarAbertura(this)

        // A descoberta vive no processo, a tela nao: sem isto, uma versao ja encontrada por uma
        // abertura anterior ficava sabida e invisivel, e o botao so apareceria se a consulta
        // acontecesse de novo exatamente nesta tela.
        mostrarBotaoDeAtualizacao()
        Atualizador.verificar { runOnUiThread { mostrarBotaoDeAtualizacao() } }

        Shizuku.addRequestPermissionResultListener(aoResponder)
        pedirAutorizacao()
        atualizar()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(aoResponder)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun pedirAutorizacao() {
        try {
            if (!Shizuku.pingBinder()) {
                status.text = "Shizuku nao esta rodando. Abra o Impulse uma vez e volte aqui."
                return
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                iniciar()
            } else {
                status.text = "Pedindo autorizacao ao Shizuku..."
                Shizuku.requestPermission(PEDIDO_SHIZUKU)
            }
        } catch (e: Exception) {
            status.text = "Shizuku indisponivel: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun iniciar() {
        startService(Intent(this, CaptureService::class.java))
        status.text = "capturando"
    }

    private fun atualizar() {
        val base = "Carro " + CaptureService.etiquetaVisivel + "  -  " +
            CaptureService.estado + "  -  " + CaptureService.eventos + " eventos"
        status.text = if (CaptureService.envioAtivo) {
            base + "\nenviando " + emKb(CaptureService.envioFeito) + " de " + emKb(CaptureService.envioAlvo)
        } else {
            base
        }
        registro.text = CaptureService.ultimos.joinToString("\n")
        conferirEnvio()
        revisarAutorizacao()
        handler.postDelayed({ atualizar() }, 1000)
    }

    /**
     * Envia o registro inteiro e avisa quando a ultima linha foi aceita.
     *
     * Nao existe "compartilhar" util aqui: a central nao tem aplicativo de mensagem, e o seletor do
     * Android acabava abrindo qualquer coisa instalada, o que nao leva o arquivo a lugar nenhum.
     * Entao o botao usa o caminho que ja funciona, e a confirmacao so aparece quando o envio
     * terminou de verdade.
     */
    private fun enviarCaptura() {
        if (CaptureService.envioAtivo) {
            status.text = "ja esta enviando, aguarde"
            return
        }
        if (CaptureService.estado == "parado") {
            status.text = "a captura nao esta rodando"
            return
        }
        CaptureService.pedirReenvioCompleto()
        aguardandoEnvio = true
        marcoEnvio = System.currentTimeMillis()
    }

    /**
     * So avisa quando o envio terminou de verdade.
     *
     * O alvo e fixo, travado no toque, e por isso a conta fecha. Acompanhar o fluxo ao vivo nunca
     * fecharia: o carro nao para de publicar, entao "faltam N" so cresceria. A confirmacao precisa
     * valer alguma coisa, porque e por ela que quem esta ajudando decide que pode ir embora.
     */
    private fun conferirEnvio() {
        if (!aguardandoEnvio) return
        if (CaptureService.envioAtivo) return
        if (CaptureService.envioConcluidoEm <= marcoEnvio) {
            if (System.currentTimeMillis() - marcoEnvio > 20000) {
                aguardandoEnvio = false
                avisar("Nao consegui enviar", "O envio nao chegou a comecar. Tente de novo em alguns segundos.")
            }
            return
        }
        aguardandoEnvio = false
        val total = CaptureService.envioAlvo
        val falha = CaptureService.envioFalha
        when {
            total == 0 -> avisar("Nada para enviar", "Ainda nao ha captura registrada neste carro.")
            falha.isEmpty() -> avisar(
                "Captura enviada",
                "O registro deste carro, " + emKb(total) + ", foi enviado por completo. " +
                    "Pode fechar o aplicativo."
            )
            else -> avisar("Nao consegui enviar", falha + "\n\nToque em Enviar captura de novo.")
        }
    }

    /**
     * Recomeca o registro do zero, com confirmacao.
     *
     * Apagar captura e irreversivel, e pode ser justamente a volta que interessava. Por isso
     * pergunta antes, sempre, mesmo sendo um utilitario descartavel.
     */
    private fun limpar() {
        if (CaptureService.envioAtivo) {
            status.text = "esta enviando, espere terminar"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Recomecar do zero?")
            .setMessage(
                "Isto apaga tudo o que ja foi capturado neste carro e comeca um registro novo. " +
                    "Nao da para desfazer."
            )
            .setPositiveButton("Apagar e recomecar") { _, _ ->
                CaptureService.pedirLimpeza()
                status.text = "recomecando..."
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Em que pe esta a autorizacao do Shizuku. Revisada a cada segundo, para o painel sumir sozinho
     * assim que a pessoa autorizar - inclusive quando autoriza pelo app do Shizuku, fora daqui.
     */
    private fun situacaoDoShizuku(): String = try {
        when {
            !Shizuku.pingBinder() -> "nao_rodando"
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> "autorizado"
            // Recusou com "nao perguntar de novo": a janela nao aparece mais, e tocar em Autorizar
            // nao faria nada visivel. O caminho passa a ser o app do Shizuku.
            Shizuku.shouldShowRequestPermissionRationale() -> "recusado"
            else -> "falta"
        }
    } catch (e: Exception) {
        "nao_rodando"
    }

    private fun revisarAutorizacao() {
        val situacao = situacaoDoShizuku()
        if (situacao == situacaoMostrada) return
        situacaoMostrada = situacao
        val painel = painelAutorizacao ?: return
        when (situacao) {
            "autorizado" -> {
                painel.visibility = android.view.View.GONE
                iniciar()
            }
            "nao_rodando" -> mostrarPainel(
                "O Shizuku ainda não está rodando",
                "Ele sobe junto com o Impulse. Toque em Abrir o Impulse, espere alguns segundos e " +
                    "volte para este app.",
                "Abrir o Impulse"
            ) { abrirApp("br.com.redesurftank.havalshisuku", "Impulse") }
            "recusado" -> mostrarPainel(
                "A autorização foi recusada",
                "O Shizuku não vai mais perguntar sozinho. Toque em Abrir o Shizuku, procure " +
                    "Impulse Vidros e Travas na lista de aplicativos e ative a permissão. Depois " +
                    "volte para este app.",
                "Abrir o Shizuku"
            ) { abrirApp("moe.shizuku.privileged.api", "Shizuku") }
            else -> mostrarPainel(
                "Falta autorizar o Shizuku",
                "Sem essa autorização o app não consegue ler o carro e não captura nada. Toque em " +
                    "Autorizar: vai abrir uma janela do Shizuku. Nela, escolha a opção de permitir.",
                "Autorizar"
            ) {
                try {
                    Shizuku.requestPermission(PEDIDO_SHIZUKU)
                } catch (e: Exception) {
                    avisar("Não consegui pedir a autorização", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun mostrarPainel(titulo: String, texto: String, rotulo: String, acao: () -> Unit) {
        tituloAutorizacao?.text = titulo
        textoAutorizacao?.text = texto
        botaoAutorizacao?.text = rotulo
        botaoAutorizacao?.setOnClickListener { acao() }
        painelAutorizacao?.visibility = android.view.View.VISIBLE
    }

    private fun abrirApp(pacote: String, nome: String) {
        val intent = packageManager.getLaunchIntentForPackage(pacote)
        if (intent == null) {
            avisar("Não encontrei o " + nome, "O " + nome + " não parece estar instalado nesta central.")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            avisar("Não consegui abrir o " + nome, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun mostrarBotaoDeAtualizacao() {
        val versao = Atualizador.versaoDisponivel ?: return
        botaoAtualizar?.apply {
            text = "Atualizar para a " + versao
            visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Baixa e instala, sem sair da tela.
     *
     * A instalacao preserva os dados, entao a captura em andamento sobrevive - ninguem perde o
     * teste por atualizar no meio dele.
     */
    private fun instalarAtualizacao() {
        if (atualizando) return
        atualizando = true
        botaoAtualizar?.isEnabled = false
        botaoAtualizar?.text = "atualizando..."
        barra?.apply {
            isIndeterminate = true
            progress = 0
            visibility = android.view.View.VISIBLE
        }
        andamento?.apply {
            text = "preparando..."
            visibility = android.view.View.VISIBLE
        }
        Thread {
            val erro = Atualizador.baixarEInstalar(applicationContext) { passo, pct ->
                runOnUiThread {
                    andamento?.text = passo
                    if (pct == Atualizador.INDEFINIDO) {
                        barra?.isIndeterminate = true
                    } else {
                        barra?.isIndeterminate = false
                        barra?.progress = pct
                    }
                }
            }
            runOnUiThread {
                atualizando = false
                botaoAtualizar?.isEnabled = true
                barra?.visibility = android.view.View.GONE
                andamento?.visibility = android.view.View.GONE
                if (erro == null) {
                    botaoAtualizar?.visibility = android.view.View.GONE
                    avisar(
                        "Atualizado",
                        "A nova versao foi instalada. Feche e abra o aplicativo para usa-la. " +
                            "O que ja foi capturado continua aqui."
                    )
                } else {
                    // Devolve o rotulo com a versao: deixar "atualizando..." num botao parado diria
                    // que ainda esta acontecendo alguma coisa.
                    mostrarBotaoDeAtualizacao()
                    avisar("Nao consegui atualizar", erro)
                }
            }
        }.start()
    }

    private fun emKb(bytes: Int): String {
        val kb = bytes / 1024
        return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else kb.toString() + " KB"
    }

    private fun avisar(titulo: String, texto: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(texto)
                .setPositiveButton("Fechar", null)
                .show()
        } catch (e: Exception) {
            status.text = titulo + ": " + texto
        }
    }
}
