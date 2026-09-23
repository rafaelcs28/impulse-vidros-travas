package br.com.rafaelcs28.vidrostravas

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/**
 * Coleta o log do Impulse e do Shizuku pelo nosso proprio processo.
 *
 * Existe para que a captura de quem tem o defeito conte o que aconteceu dentro do Impulse, sem
 * depender da versao do Impulse que a pessoa instalou nem de mexer nela. Quem manda a captura
 * manda junto o que o Impulse registrou - e e isso que separa "a verificacao da tranca nao conseguiu
 * ler" de "o comando de fechar lancou" de "o evento nem chegou a ser tratado".
 *
 * Tres cuidados moldam tudo aqui.
 *
 * UM PROCESSO SO. Cada comando pelo Shizuku cria um processo, e o servidor do Shizuku vaza alguns
 * kilobytes por processo criado - e isso que o mata. Ler o log repetindo `logcat -d` a cada poucos
 * segundos faria esta ferramenta provocar o defeito que veio observar. Entao abre-se um `logcat` e
 * deixa-se correndo: o custo e de uma vez, nao por leitura. Quando o fluxo cai (o Shizuku morreu),
 * reabre a partir do ultimo instante visto, e o `-T` devolve o trecho perdido antes de seguir.
 *
 * CAIXA-PRETA. O Impulse fala muito, e a captura precisa continuar cabendo num envio. Erros, o
 * servidor do Shizuku, mortes e partidas de processo e linhas com palavra de diagnostico sao
 * gravados sempre. O resto fica so em memoria, pelos ultimos minutos. Num momento decisivo - tranca,
 * desligamento, queda do Shizuku, o Impulse reiniciando - os minutos anteriores vao para o arquivo e
 * tudo passa a ser gravado por mais um tempo. Pequeno no dia a dia, completo onde importa.
 *
 * PRIVACIDADE. O log e de outra pessoa. O chassi inteiro nunca sai daqui - so o final, como no resto
 * da captura - e credenciais com cara de token sao escondidas antes de gravar.
 */
object ColetorDeLog {

    private const val TAG = "ColetorDeLog"
    private const val PACOTE = "br.com.redesurftank.havalshisuku"

    /** Quanto de historico pegar na primeira abertura: o que houve logo antes da captura comecar. */
    private const val HISTORICO_INICIAL = "1500"

    private const val JANELA_ANTES_MS = 120_000L
    private const val JANELA_DEPOIS_MS = 90_000L
    private const val CAIXA_MAX = 500

    /** Tetos de volume, por minuto. O por tag impede que um assunto tagarela abafe os outros. */
    private const val TETO_POR_MINUTO = 60
    private const val TETO_POR_TAG = 20

    /**
     * A partir deste tamanho do arquivo de captura, so entra o critico.
     *
     * Nao e capricho: o envio le o arquivo INTEIRO para a memoria e o converte de uma vez. Uma
     * captura de uma tarde tinha 0,15 MB; um coletor sem teto poderia leva-la a dezenas de MB num
     * dia, e o aplicativo estouraria a memoria exatamente na hora de enviar - perdendo a captura que
     * interessa por causa da ferramenta que devia enriquece-la. 3 MB viram ~4 MB em memoria no envio.
     */
    private const val LIMITE_ECONOMIA_BYTES = 3L * 1024 * 1024
    private const val DESPEJO_MAX_EM_ECONOMIA = 150

    private const val ESPERA_REABRIR_MS = 15_000L
    private const val ESPERA_REABRIR_MAX_MS = 600_000L

    /** Um fluxo que durou menos que isto nao caiu por morte do Shizuku: nem chegou a funcionar. */
    private const val FLUXO_SAUDAVEL_MS = 60_000L

    /** Palavras que, numa linha do Impulse, justificam gravar sempre - sao o proprio diagnostico. */
    private val PALAVRAS = listOf(
        "shizuku", "lock", "window", "sunroof", "curtain", "shade", "controlservice",
        "fetching", "initializ", "bootstrap", "dead", "died", "restart",
        "permission", "exception", "fatal", "ignoring"
    )
    // Fora de proposito: "vehicle", "binder" e "error" aparecem em linha demais do Impulse e
    // gastariam o teto com ruido. Erro de verdade ja entra pelo nivel (E/F), sem precisar da palavra.

    /** Tags do sistema que contam quando um processo nasce, morre ou e morto. */
    private val TAGS_SISTEMA = setOf(
        "ActivityManager", "ActivityTaskManager", "lowmemorykiller", "libprocessgroup", "Zygote"
    )

    fun interface Destino {
        fun gravar(tipo: String, dados: Map<String, String>)
    }

    private val destino = AtomicReference<Destino?>(null)
    @Volatile private var tamanhoDoArquivo: () -> Long = { 0L }
    @Volatile private var tamanhoConhecido = 0L
    @Volatile private var tamanhoLidoEm = 0L
    @Volatile private var avisouEconomia = false

    @Volatile private var rodando = false
    @Volatile private var uidImpulse = -1
    @Volatile private var nomeUidImpulse = ""
    @Volatile private var semColunaDeUid = false
    @Volatile private var ultimoCarimbo = ""
    @Volatile private var gravarTudoAte = 0L
    @Volatile private var processoAtual: moe.shizuku.server.IRemoteProcess? = null

    private val pidsImpulse = CopyOnWriteArraySet<String>()
    private val caixaPreta = ArrayDeque<Pair<Long, Map<String, String>>>()

    // contabilidade de volume
    private var minutoAtual = 0L
    private var linhasNoMinuto = 0
    private var descartadasNoMinuto = 0
    private val porTagNoMinuto = HashMap<String, Int>()

    // repeticao
    private var ultimaAssinatura = ""
    private var repeticoes = 0
    private var ultimaTagRepetida = ""

    /**
     * Linha no formato threadtime, com ou sem a coluna de uid.
     *
     * Com `-v uid` o Android imprime o uid como `%5d:` - COM dois-pontos - e o pid logo depois em
     * `%5d`. Quando o pid tem cinco digitos os dois grudam (`10052:12345`), e um padrao que exigisse
     * espaco entre eles perderia justamente essas linhas. Sem a coluna, a linha comeca direto no pid.
     */
    private val LINHA = Regex(
        "^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(?:([A-Za-z0-9_]+):\\s*)?(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]*?)\\s*: ?(.*)$"
    )
    private val CHASSI = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
    private val TOKEN = Regex("(Bearer\\s+)[A-Za-z0-9._\\-]+|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}")
    private val DIGITOS = Regex("\\d+")

    fun iniciar(context: Context, tamanho: () -> Long, alvo: Destino) {
        destino.set(alvo)
        tamanhoDoArquivo = tamanho
        if (rodando) return
        rodando = true
        try {
            val uid = context.packageManager.getApplicationInfo(PACOTE, 0).uid
            uidImpulse = uid
            // Usuario 0: o Android nomeia o uid 10052 como u0_a52. O logcat pode mostrar qualquer um.
            nomeUidImpulse = "u0_a" + (uid - 10000)
        } catch (e: Exception) {
            uidImpulse = -1
        }
        Thread({ laco() }, "coletor-log").apply { isDaemon = true }.start()
    }

    fun parar() {
        rodando = false
        try { processoAtual?.destroy() } catch (e: Exception) {}
    }

    /**
     * Um momento que interessa aconteceu: grava os minutos anteriores e tudo que vier a seguir.
     * Pode ser chamado de qualquer thread.
     */
    fun marcarMomento(motivo: String) {
        val agora = System.currentTimeMillis()
        val jaGravando = agora < gravarTudoAte
        gravarTudoAte = agora + JANELA_DEPOIS_MS
        if (jaGravando) return
        val trecho: List<Map<String, String>>
        synchronized(caixaPreta) {
            val todos = caixaPreta.filter { agora - it.first <= JANELA_ANTES_MS }.map { it.second }
            // Em economia, so o fim do trecho: e o mais proximo do momento, e o que mais explica.
            trecho = if (emEconomia()) todos.takeLast(DESPEJO_MAX_EM_ECONOMIA) else todos
            caixaPreta.clear()
        }
        // Em thread propria: quem chama aqui costuma ser o ouvinte do carro, que e a thread que nos
        // entrega os dados. Escrever centenas de linhas nela seguraria a proxima entrega.
        Thread({
            emitir("log_momento", mapOf("motivo" to motivo, "linhas_antes" to trecho.size.toString()))
            for (d in trecho) emitir("log", d)
        }, "coletor-despejo").apply { isDaemon = true }.start()
    }

    /**
     * Recuo progressivo entre reaberturas.
     *
     * Cada reabertura e um processo novo no Shizuku, e processo novo e o que vaza memoria nele. Se o
     * logcat recusasse a abrir e saisse na hora, reabrir a cada 15 s poria esta ferramenta a vazar
     * memoria no Shizuku sem parar - provocando o defeito que veio medir. Entao, a cada fluxo que
     * morre sem ter chegado a funcionar, a espera dobra, ate dez minutos. Um fluxo saudavel zera.
     */
    private fun laco() {
        var primeira = true
        var espera = ESPERA_REABRIR_MS
        while (rodando) {
            try {
                if (semColunaDeUid || uidImpulse < 0) atualizarPids()
                val abriuEm = System.currentTimeMillis()
                val lidas = lerFluxo(primeira)
                primeira = false
                if (!rodando) return
                val durou = System.currentTimeMillis() - abriuEm
                val saudavel = durou >= FLUXO_SAUDAVEL_MS
                emitir("log_fluxo_caiu", mapOf(
                    "linhas_lidas" to lidas.toString(),
                    "durou_s" to (durou / 1000).toString(),
                    "proxima_em_s" to ((if (saudavel) ESPERA_REABRIR_MS else espera) / 1000).toString()))
                if (saudavel) {
                    // Um fluxo que vinha funcionando e caiu: quase sempre e o Shizuku morrendo -
                    // exatamente o que se investiga. So este caso vale como momento decisivo.
                    marcarMomento("fluxo do log caiu depois de " + (durou / 1000) + " s")
                    espera = ESPERA_REABRIR_MS
                    Thread.sleep(ESPERA_REABRIR_MS)
                } else {
                    Thread.sleep(espera)
                    espera = minOf(espera * 2, ESPERA_REABRIR_MAX_MS)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                // Throwable: o defeito que se mede chega como OutOfMemoryError vindo do Shizuku.
                Log.w(TAG, "coletor falhou", e)
                try { Thread.sleep(ESPERA_REABRIR_MS) } catch (i: InterruptedException) { return }
            }
        }
    }

    /** Abre o logcat e le ate o fluxo cair. Devolve quantas linhas foram lidas. */
    private fun lerFluxo(primeira: Boolean): Int {
        val binder = rikka.shizuku.Shizuku.getBinder() ?: return 0
        val servico = moe.shizuku.server.IShizukuService.Stub.asInterface(binder) ?: return 0

        val comando = ArrayList<String>()
        comando += listOf("logcat", "-v", "threadtime")
        if (!semColunaDeUid) comando += listOf("-v", "uid")
        // -T, e nao -t: devolve a partir do ponto pedido e CONTINUA correndo. Na primeira abertura
        // pega um historico; nas seguintes, retoma do ultimo instante visto, cobrindo o buraco.
        comando += listOf("-T", if (primeira || ultimoCarimbo.isEmpty()) HISTORICO_INICIAL else ultimoCarimbo)

        val processo = servico.newProcess(comando.toTypedArray(), null, null) ?: return 0
        processoAtual = processo
        var lidas = 0
        var reconhecidas = 0
        val inicio = System.currentTimeMillis()
        try {
            try { processo.outputStream?.close() } catch (e: Exception) {}
            val pfd = processo.inputStream ?: return 0
            BufferedReader(InputStreamReader(FileInputStream(pfd.fileDescriptor))).use { leitor ->
                while (rodando) {
                    val linha = leitor.readLine() ?: break
                    lidas++
                    if (tratar(linha)) reconhecidas++
                }
            }
            // Sem -v uid neste Android: o logcat recusa e sai na hora. Cai para o filtro por PID.
            // Pelas RECONHECIDAS: ao recusar um formato o logcat pode imprimir a ajuda inteira, que
            // sao dezenas de linhas - contar as lidas deixaria passar a recusa.
            if (!semColunaDeUid && reconhecidas == 0 && System.currentTimeMillis() - inicio < 3000) {
                semColunaDeUid = true
                emitir("log_formato", mapOf("uid" to "nao suportado, filtrando por pid"))
            }
        } finally {
            try { processo.destroy() } catch (e: Exception) {}
            processoAtual = null
        }
        return lidas
    }

    /** Devolve true se a linha foi reconhecida no formato esperado. */
    private fun tratar(bruta: String): Boolean {
        val m = LINHA.find(bruta) ?: return false
        val carimbo = m.groupValues[1]
        val uid = m.groupValues[2]
        val pid = m.groupValues[3]
        val nivel = m.groupValues[5]
        val tag = m.groupValues[6].trim()
        val msg = m.groupValues[7]
        ultimoCarimbo = carimbo

        val doImpulse = ehDoImpulse(uid, pid)
        val tagMinuscula = tag.lowercase()
        val msgMinuscula = msg.lowercase()

        val doShizuku = "shizuku" in tagMinuscula
        val doSistema = tag in TAGS_SISTEMA &&
            (PACOTE in msg || "shizuku" in msgMinuscula)

        if (!doImpulse && !doShizuku && !doSistema) return true

        // O Impulse reiniciou: e o momento em que o estado velho some ou nao some.
        if (doImpulse && pid !in pidsImpulse) {
            val eraConhecido = pidsImpulse.isNotEmpty()
            pidsImpulse.add(pid)
            if (pidsImpulse.size > 4) pidsImpulse.remove(pidsImpulse.first())
            if (eraConhecido) marcarMomento("Impulse com processo novo (pid " + pid + ")")
        }

        if (doSistema && PACOTE in msg && ("died" in msgMinuscula || "kill" in msgMinuscula)) {
            marcarMomento("processo do Impulse morreu")
        }
        if (doShizuku && ("outofmemory" in msgMinuscula || "died" in msgMinuscula)) {
            marcarMomento("servidor do Shizuku em apuros")
        }
        if (doImpulse && nivel == "F") marcarMomento("erro fatal no Impulse")

        // Debug e verbose ficam de fora: volume demais para o que dizem.
        if (doImpulse && (nivel == "D" || nivel == "V")) return true

        val dados = linkedMapOf(
            "hora_log" to carimbo,
            "pid" to pid,
            "nivel" to nivel,
            "tag" to tag,
            "msg" to limpar(msg).take(500)
        )

        val critico = !doImpulse || nivel == "E" || nivel == "F"
        val sempre = critico || PALAVRAS.any { it in msgMinuscula || it in tagMinuscula }
        val agora = System.currentTimeMillis()

        if (emEconomia() && !critico) {
            if (!avisouEconomia) {
                avisouEconomia = true
                emitir("log_economia", mapOf(
                    "motivo" to "arquivo passou de 3 MB; so linhas criticas daqui em diante",
                    "bytes" to tamanhoConhecido.toString()))
            }
            return true
        }

        if (sempre || agora < gravarTudoAte) {
            gravarComTeto(tag, msg, dados)
        } else {
            synchronized(caixaPreta) {
                caixaPreta.addLast(agora to dados)
                while (caixaPreta.size > CAIXA_MAX) caixaPreta.removeFirst()
            }
        }
        return true
    }

    /** Tamanho do arquivo, consultado no maximo a cada 10 s: e lido a cada linha do log. */
    private fun emEconomia(): Boolean {
        val agora = System.currentTimeMillis()
        if (agora - tamanhoLidoEm > 10_000L) {
            tamanhoLidoEm = agora
            tamanhoConhecido = try { tamanhoDoArquivo() } catch (e: Throwable) { 0L }
            if (tamanhoConhecido < LIMITE_ECONOMIA_BYTES) avisouEconomia = false
        }
        return tamanhoConhecido >= LIMITE_ECONOMIA_BYTES
    }

    private fun ehDoImpulse(uid: String, pid: String): Boolean {
        if (!semColunaDeUid && uid.isNotEmpty() && uidImpulse >= 0) {
            return uid == uidImpulse.toString() || uid == nomeUidImpulse
        }
        return pid in pidsImpulse
    }

    /** So no modo sem uid: descobre o processo do Impulse. Um comando, raro. */
    private fun atualizarPids() {
        val saida = try {
            CaptureService.rodarComandoShizuku(arrayOf("pidof", PACOTE))
        } catch (e: Throwable) {
            ""
        }
        saida.split(Regex("\\s+")).filter { it.isNotBlank() && it.all(Char::isDigit) }.forEach {
            pidsImpulse.add(it)
        }
    }

    /**
     * Grava respeitando os tetos, e junta repeticoes seguidas.
     *
     * Repeticao e comum - o mesmo aviso a cada segundo - e gravar cada uma enche o arquivo com uma
     * informacao so. A assinatura ignora numeros, para "tentativa 3" e "tentativa 4" contarem como a
     * mesma linha.
     */
    private fun gravarComTeto(tag: String, msg: String, dados: Map<String, String>) {
        val minuto = System.currentTimeMillis() / 60_000L
        if (minuto != minutoAtual) {
            if (descartadasNoMinuto > 0) {
                emitir("log_descartado", mapOf("linhas" to descartadasNoMinuto.toString(),
                    "motivo" to "teto de volume por minuto"))
            }
            minutoAtual = minuto
            linhasNoMinuto = 0
            descartadasNoMinuto = 0
            porTagNoMinuto.clear()
        }

        val assinatura = tag + "|" + DIGITOS.replace(msg, "#")
        if (assinatura == ultimaAssinatura) {
            repeticoes++
            return
        }
        if (repeticoes > 0) {
            emitir("log_repetido", mapOf("tag" to ultimaTagRepetida, "vezes" to repeticoes.toString()))
        }
        ultimaAssinatura = assinatura
        ultimaTagRepetida = tag
        repeticoes = 0

        val daTag = porTagNoMinuto[tag] ?: 0
        if (linhasNoMinuto >= TETO_POR_MINUTO || daTag >= TETO_POR_TAG) {
            descartadasNoMinuto++
            return
        }
        linhasNoMinuto++
        porTagNoMinuto[tag] = daTag + 1
        emitir("log", dados)
    }

    private fun limpar(s: String): String {
        val semChassi = CHASSI.replace(s) { "[chassi ..." + it.value.takeLast(6) + "]" }
        return TOKEN.replace(semChassi) { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + "[oculto]" else "[token oculto]"
        }
    }

    private fun emitir(tipo: String, dados: Map<String, String>) {
        try {
            destino.get()?.gravar(tipo, dados)
        } catch (e: Throwable) {
            Log.w(TAG, "nao consegui gravar linha de log", e)
        }
    }
}
