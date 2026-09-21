package br.com.rafaelcs28.vidrostravas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService
import com.beantechs.intelligentvehiclecontrol.sdk.IListener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Captura, em primeiro plano, tudo o que o carro publica sobre vidros, teto, portas, tranca e
 * retrovisor - e o resto, ja que ler barato e melhor do que descobrir depois que faltou chave.
 *
 * Duas saidas, de proposito:
 *  - um arquivo local (`captura.ndjson`), que e o registro completo e fica com o dono do carro;
 *  - um canal publico de mensagens, para acompanhar ao vivo de longe.
 *
 * Nao escreve NADA no carro. Nao ha uma so chamada de `request` aqui: o servico da montadora e
 * usado apenas para ler e para ouvir mudancas.
 */
class CaptureService : Service() {

    private var control: IIntelligentVehicleControlService? = null
    private var listener: IListener? = null
    private val fila = LinkedBlockingQueue<String>()
    private var enviando = true
    private lateinit var arquivo: File

    /**
     * Etiqueta curta desta instalacao, sorteada uma vez e guardada.
     *
     * Existe porque mais de uma pessoa vai testar ao mesmo tempo e todas publicam no mesmo canal:
     * sem ela, dois carros chegariam misturados e indistinguiveis. Vai em TODA linha, inclusive no
     * arquivo local, para um registro recebido solto continuar identificavel.
     */
    private lateinit var etiqueta: String

    companion object {
        private const val TAG = "CapturaVidros"
        private const val CANAL_NOTIFICACAO = "captura"

        /** Canal de acompanhamento ao vivo. Nome sorteado; quem nao sabe o nome nao ve nada. */
        const val CANAL = "impulse-vt-968d3ea264419444"

        /** Servico da montadora que publica as mudancas de propriedade do carro. */
        private const val SERVICO_CARRO = "com.beantechs.intelligentvehiclecontrol"

        fun propriedadeDoSistema(nome: String): String = try {
            val sp = Class.forName("android.os.SystemProperties")
            (sp.getMethod("get", String::class.java).invoke(null, nome) as? String).orEmpty()
        } catch (e: Exception) {
            ""
        }

        /**
         * Etiqueta desta instalacao: os seis ultimos caracteres do chassi.
         *
         * O chassi e o unico identificador estavel de verdade - sobrevive a reinstalar o aplicativo
         * e distingue carros do mesmo modelo. Vai so o final, que ja separa os participantes sem
         * publicar o numero inteiro num canal aberto. Sem chassi legivel, sorteia uma etiqueta e a
         * guarda.
         */
        fun etiquetaDe(ctx: Context): String {
            val chassi = propriedadeDoSistema("persist.beantechs.vehicle.vin").trim()
            if (chassi.length >= 6) return chassi.takeLast(6).uppercase()
            val prefs = ctx.getSharedPreferences("captura", Context.MODE_PRIVATE)
            prefs.getString("etiqueta", null)?.let { return it }
            val sorteada = java.util.UUID.randomUUID().toString().takeLast(6).uppercase()
            prefs.edit().putString("etiqueta", sorteada).apply()
            return sorteada
        }

        fun enviar(corpo: String) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://ntfy.sh/" + CANAL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {
                // Sem rede o registro local continua completo; o vivo e um extra, nao a fonte.
                Log.w(TAG, "publicacao falhou", e)
            } finally {
                conn?.disconnect()
            }
        }

        /**
         * Avisa que o aplicativo abriu, chamado pela TELA e nao pelo servico.
         *
         * Aqui e o unico lugar que serve: o servico so existe depois que o Shizuku autoriza, e e
         * justamente a instalacao que nao passa dessa etapa que precisamos enxergar. Sem este
         * aviso, quem abriu e travou no Shizuku fica identico, daqui, a quem nunca instalou.
         */
        fun avisarAbertura(ctx: Context) {
            Thread {
                // Uma excecao solta numa thread derruba o processo inteiro no Android, e este
                // aviso nao vale o preco de fechar o aplicativo na cara de quem foi ajudar.
                try {
                    avisar(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "aviso de abertura falhou", e)
                }
            }.start()
        }

        private fun avisar(ctx: Context) {
                val shizuku = try {
                    when {
                        !rikka.shizuku.Shizuku.pingBinder() -> "nao esta rodando"
                        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "autorizado"
                        else -> "esperando autorizacao"
                    }
                } catch (e: Exception) {
                    "indisponivel"
                }
                val versao = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
                } catch (e: Exception) {
                    "?"
                }
                val agora = System.currentTimeMillis()
                val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(agora))
                enviar(
                    "{\"carro\":\"" + etiquetaDe(ctx) + "\",\"t\":\"" + hora + "\",\"ms\":" + agora +
                        ",\"tipo\":\"abriu\",\"app\":\"" + versao + "\",\"shizuku\":\"" + shizuku + "\"}"
                )
        }

        @Volatile
        var estado: String = "parado"
            private set

        @Volatile
        var etiquetaVisivel: String = "?"
            private set

        @Volatile
        var eventos: Int = 0
            private set

        @Volatile
        var ultimos: List<String> = emptyList()
            private set

        fun arquivoDe(service: Service): File = File(service.filesDir, "captura.ndjson")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        arquivo = arquivoDe(this)
        etiqueta = definirEtiqueta()
        etiquetaVisivel = etiqueta
        emPrimeiroPlano()
        Thread({ remetente() }, "envio").apply { isDaemon = true }.start()
        Thread({ conectar() }, "conexao").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        enviando = false
        try {
            listener?.let { control?.unRegisterDataChangedListener(packageName, it) }
        } catch (e: Exception) {
            Log.w(TAG, "falha ao desregistrar", e)
        }
        estado = "parado"
        super.onDestroy()
    }

    private fun emPrimeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL_NOTIFICACAO, "Captura", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, CANAL_NOTIFICACAO)
            .setContentTitle("Capturando dados do carro")
            .setContentText("Vidros, teto, portas e tranca")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun conectar() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val bruto = sm.getMethod("getService", String::class.java).invoke(null, SERVICO_CARRO) as? IBinder
            if (bruto == null) {
                estado = "servico do carro nao encontrado"
                anotar("falha", mapOf("onde" to "getService", "detalhe" to SERVICO_CARRO))
                return
            }
            val binder = ShizukuBinderWrapper(bruto)
            if (!binder.pingBinder()) {
                estado = "servico do carro nao respondeu"
                anotar("falha", mapOf("onde" to "pingBinder", "detalhe" to "sem resposta"))
                return
            }
            val servico = IIntelligentVehicleControlService.Stub.asInterface(binder)
            control = servico

            val chaves = CarConstants.values().map { it.value }.distinct().toTypedArray()
            registrar(servico, chaves)
            identificacao()
            retrato(servico, chaves)
            estado = "capturando (" + chaves.size + " chaves)"
        } catch (e: Exception) {
            Log.e(TAG, "conexao falhou", e)
            estado = "falhou: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun registrar(servico: IIntelligentVehicleControlService, chaves: Array<String>) {
        val ouvinte = object : IListener.Stub() {
            override fun onDataChanged(key: String?, value: String?) {
                if (key == null) return
                anotar("mudanca", mapOf("chave" to key, "valor" to (value ?: "")))
            }
        }
        listener = ouvinte
        servico.registerDataChangedListener(packageName, ouvinte)
        // Em blocos: a lista inteira numa transacao so e desnecessariamente grande, e o servico
        // aceita chamadas sucessivas somando chaves.
        chaves.toList().chunked(120).forEach { bloco ->
            try {
                servico.addListenerKey(packageName, bloco.toTypedArray())
            } catch (e: Exception) {
                Log.w(TAG, "addListenerKey falhou num bloco", e)
            }
        }
    }

    private fun propriedade(nome: String): String = propriedadeDoSistema(nome)

    /**
     * Etiqueta desta instalacao: os seis ultimos caracteres do chassi.
     *
     * O chassi e o unico identificador estavel de verdade - sobrevive a reinstalar o aplicativo e
     * distingue carros do mesmo modelo. Vai so o final, que ja separa os participantes sem publicar
     * o numero inteiro num canal aberto. Sem chassi legivel, sorteia uma etiqueta e a guarda.
     */
    private fun definirEtiqueta(): String = etiquetaDe(this)

    /**
     * Qual carro e este e qual Impulse esta instalado - as duas perguntas que a comparacao entre
     * participantes exige. Do chassi vai so o final, o mesmo da etiqueta.
     */
    private fun identificacao() {
        val props = listOf(
            "persist.bean.configure.code",
            "persist.vendor.gwm.cfg.trim.level",
            "persist.bean.car.mode1",
            "persist.bean.car.mode2",
            "persist.bean.engine.type",
            "ro.bean.project.name",
            "ro.bean.project.id",
            "persist.vendor.gwm.cfg.project.code",
            "ro.leading.car.model"
        )
        val dados = HashMap<String, String>()
        for (p in props) dados[p] = propriedade(p)
        dados["chassi_final"] = etiqueta
        dados["android"] = Build.VERSION.RELEASE
        dados["build"] = Build.DISPLAY

        // Versao do Impulse instalada: e a primeira coisa a conferir quando dois carros se comportam
        // diferente, antes de suspeitar do carro.
        try {
            val info = packageManager.getPackageInfo("br.com.redesurftank.havalshisuku", 0)
            dados["impulse"] = info.versionName ?: "?"
            dados["impulse_code"] = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.longVersionCode else info.versionCode.toLong()).toString()
        } catch (e: Exception) {
            dados["impulse"] = "nao instalado"
        }
        anotar("carro", dados)
    }

    /** Retrato de todas as chaves ao abrir, para termos o ponto de partida e nao so as mudancas. */
    private fun retrato(servico: IIntelligentVehicleControlService, chaves: Array<String>) {
        chaves.toList().chunked(80).forEach { bloco ->
            try {
                val valores = servico.fetchDatas(bloco.toTypedArray()) ?: return@forEach
                val mapa = HashMap<String, String>()
                for (i in bloco.indices) {
                    val v = valores.getOrNull(i) ?: continue
                    if (v.isNotEmpty()) mapa[bloco[i]] = v
                }
                if (mapa.isNotEmpty()) anotar("retrato", mapa)
            } catch (e: Exception) {
                Log.w(TAG, "fetchDatas falhou num bloco", e)
            }
        }
    }

    private fun anotar(tipo: String, dados: Map<String, String>) {
        val agora = System.currentTimeMillis()
        val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(agora))
        val sb = StringBuilder()
        sb.append("{\"carro\":\"").append(etiqueta).append("\"")
        sb.append(",\"t\":\"").append(hora).append("\",\"ms\":").append(agora)
        sb.append(",\"tipo\":\"").append(tipo).append("\"")
        for ((k, v) in dados) {
            sb.append(",\"").append(escapar(k)).append("\":\"").append(escapar(v)).append("\"")
        }
        sb.append("}")
        val linha = sb.toString()
        eventos++
        val cauda = (ultimos + (hora + "  " + dados.entries.joinToString(" ") { it.key.substringAfterLast('.') + "=" + it.value })).takeLast(12)
        ultimos = cauda
        try {
            arquivo.appendText(linha + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "nao consegui gravar", e)
        }
        fila.offer(linha)
    }

    private fun escapar(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /**
     * Manda em lotes a cada dois segundos. Em lote porque uma mensagem por mudanca afogaria o canal
     * assim que o carro acorda e publica tudo de uma vez.
     */
    private fun remetente() {
        val lote = ArrayList<String>()
        while (enviando) {
            try {
                val primeira = fila.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                if (primeira != null) lote.add(primeira)
                fila.drainTo(lote, 60)
                if (lote.isEmpty()) continue
                publicar(lote.joinToString("\n"))
                lote.clear()
            } catch (e: Exception) {
                Log.w(TAG, "envio falhou", e)
                lote.clear()
            }
        }
    }

    private fun publicar(corpo: String) = enviar(corpo)
}
