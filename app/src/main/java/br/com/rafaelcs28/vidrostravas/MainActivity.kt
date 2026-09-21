package br.com.rafaelcs28.vidrostravas

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import java.io.File
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
            text = "Compartilhar captura"
            setOnClickListener { compartilhar() }
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
        status.text = "Carro " + CaptureService.etiquetaVisivel + "  -  " +
            CaptureService.estado + "  -  " + CaptureService.eventos + " eventos"
        registro.text = CaptureService.ultimos.joinToString("\n")
        handler.postDelayed({ atualizar() }, 1000)
    }

    private fun compartilhar() {
        val arquivo = File(filesDir, "captura.ndjson")
        if (!arquivo.exists()) {
            status.text = "ainda nao ha nada capturado"
            return
        }
        val copia = File(externalCacheDir ?: cacheDir, "captura.ndjson")
        arquivo.copyTo(copia, overwrite = true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(copia))
            putExtra(Intent.EXTRA_SUBJECT, "Captura Impulse Vidros e Travas")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Enviar captura"))
    }
}
