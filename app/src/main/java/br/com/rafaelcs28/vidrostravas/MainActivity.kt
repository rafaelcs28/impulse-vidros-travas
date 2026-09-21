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

    private val aoResponder = Shizuku.OnRequestPermissionResultListener { pedido, resultado ->
        if (pedido == PEDIDO_SHIZUKU) {
            if (resultado == android.content.pm.PackageManager.PERMISSION_GRANTED) iniciar()
            else status.text = "Autorizacao do Shizuku negada. Abra o Shizuku, autorize este app e volte."
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
            text = "Impulse Vidros e Travas"
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
