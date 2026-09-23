package br.com.rafaelcs28.vidrostravas

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Procura versão nova no mesmo lugar de onde o aplicativo foi baixado, e instala.
 *
 * Existe porque quem está ajudando no diagnóstico não tem por que caçar link: a ferramenta muda
 * várias vezes ao dia enquanto o problema está aberto, e uma captura feita numa versão com defeito
 * é trabalho perdido dos dois lados. A verificação roda uma vez por abertura — o aplicativo sobe
 * junto com a central, então na prática é uma vez por partida.
 *
 * A instalação copia o caminho que o Impulse já usa nesta central: `pm install -r -d` pelo Shizuku,
 * com o arquivo em /data/local/tmp. O `-r` preserva os dados, então a captura em andamento
 * sobrevive à atualização; o `-d` aceita voltar para uma versão anterior, que é útil quando uma
 * correção sai torta e é preciso recuar no meio do teste.
 */
object Atualizador {

    private const val TAG = "AtualizadorVT"
    private const val REPO = "rafaelcs28/impulse-vidros-travas"

    /** Versão disponível, quando houver uma mais nova que a instalada. Nulo enquanto não souber. */
    @Volatile
    var versaoDisponivel: String? = null
        private set

    @Volatile
    private var verificando = false

    @Volatile
    private var ultimaConsultaMs = 0L

    @Volatile
    var estado: String = ""
        private set

    /**
     * Compara versões como "1.13" e "1.9" pelos números, não pelo texto.
     *
     * Texto puro erraria exatamente no caso que vai acontecer: "1.9" é maior que "1.13" em ordem
     * alfabética, e o aplicativo nunca mais ofereceria atualização depois da décima.
     */
    fun maisNova(candidata: String, atual: String): Boolean {
        val a = candidata.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val b = atual.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Consulta a cada abertura da tela, numa thread própria. Falha em silêncio: é um extra.
     *
     * Já foi uma vez por processo, e nesta central isso equivalia a uma vez por instalação: o
     * aplicativo mantém serviço em primeiro plano, então fechar a tela não mata o processo, e quem
     * fechava e abria continuava vendo a resposta da primeira consulta — feita, às vezes, dias
     * antes. A trava curta abaixo serve só para telas recriadas em sequência; abrir o aplicativo de
     * verdade consulta sempre.
     */
    fun verificar(aoDescobrir: () -> Unit) {
        if (verificando) return
        val agora = System.currentTimeMillis()
        if (agora - ultimaConsultaMs < 10_000L) return
        verificando = true
        ultimaConsultaMs = agora
        Thread {
            try {
                val tag = ultimaTagPublicada() ?: return@Thread
                val atual = BuildConfig.VERSION_NAME
                if (maisNova(tag, atual)) {
                    versaoDisponivel = tag.removePrefix("v")
                    Log.w(TAG, "versao nova: $tag (instalada $atual)")
                    aoDescobrir()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "verificacao de atualizacao falhou", e)
            } finally {
                verificando = false
            }
        }.start()
    }

    /**
     * Descobre a última versão publicada, com dois caminhos.
     *
     * A API anônima do GitHub tem teto por hora e por endereço, e quem usa isto está numa rede
     * qualquer, que pode já ter gastado o teto. Quando ela recusa, a página de "releases/latest"
     * continua respondendo, e o endereço para onde ela redireciona já carrega a versão no caminho.
     */
    private fun ultimaTagPublicada(): String? = pelaApi() ?: peloRedirecionamento()

    private fun pelaApi(): String? {
        var conn: HttpURLConnection? = null
        return try {
            // O repositório é público, então a API responde sem credencial. Sem token de propósito:
            // um aplicativo que qualquer um instala não deve carregar credencial nenhuma.
            conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "impulse-vidros-travas")
            if (conn.responseCode !in 200..299) return null
            val corpo = conn.inputStream.bufferedReader().readText()
            Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(corpo)?.groupValues?.get(1)
        } catch (e: Throwable) {
            Log.w(TAG, "nao consegui consultar a ultima versao", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun peloRedirecionamento(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("https://github.com/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "impulse-vidros-travas")
            val destino = conn.getHeaderField("Location") ?: return null
            val tag = destino.substringAfterLast("/tag/", "")
            if (tag.isBlank()) null else tag
        } catch (e: Throwable) {
            Log.w(TAG, "redirecionamento tambem nao respondeu", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Baixa e instala. Devolve null quando deu certo, ou o motivo da falha.
     *
     * Roda FORA da thread principal; quem chama cuida disso.
     */
    fun baixarEInstalar(context: Context, aoAndar: (String, Int) -> Unit): String? {
        try {
            aoAndar("conectando...", INDEFINIDO)
            val destino = File(context.cacheDir, "atualizacao.apk")
            val baixado = baixar(destino, aoAndar) ?: return "não consegui baixar o arquivo"

            // A instalacao nao tem percentual para informar - o `pm` so responde no fim - e ela
            // demora o suficiente para parecer travada. O relogio abaixo existe so para provar que
            // ainda esta viva: sem ele, a barra indefinida e o silencio se parecem demais, e a
            // pessoa toca de novo achando que o primeiro toque nao pegou.
            aoAndar("instalando...", INDEFINIDO)
            val relogio = Thread {
                val inicio = System.currentTimeMillis()
                try {
                    while (true) {
                        Thread.sleep(1000)
                        val s = (System.currentTimeMillis() - inicio) / 1000
                        aoAndar("instalando...  " + s + "s", INDEFINIDO)
                    }
                } catch (e: InterruptedException) {
                    // fim normal: a instalacao terminou
                }
            }
            relogio.isDaemon = true
            relogio.start()
            try {
                return instalar(baixado)
            } finally {
                relogio.interrupt()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "atualizacao falhou", e)
            return e.message ?: e.javaClass.simpleName
        }
    }

    /** Percentual desconhecido: a barra gira em vez de encher. */
    const val INDEFINIDO = -1

    private fun emMb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    private fun baixar(destino: File, aoAndar: (String, Int) -> Unit): File? {
        var conn: HttpURLConnection? = null
        return try {
            // Mesmo endereço fixo do link que as pessoas já usam: o GitHub resolve sempre para a
            // versão mais recente, então não é preciso descobrir a URL do arquivo.
            conn = URL("https://github.com/$REPO/releases/latest/download/impulse-vidros-travas.apk")
                .openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            if (conn.responseCode !in 200..299) return null

            val total = conn.contentLength.toLong()
            var lidos = 0L
            var ultimoAviso = 0L
            destino.outputStream().use { saida ->
                conn.inputStream.use { entrada ->
                    val balde = ByteArray(16384)
                    while (true) {
                        val n = entrada.read(balde)
                        if (n < 0) break
                        saida.write(balde, 0, n)
                        lidos += n
                        // Com folga entre avisos: a central e lenta, e mandar um recado por bloco
                        // faria a tela trabalhar mais do que o download.
                        val agora = System.currentTimeMillis()
                        if (agora - ultimoAviso < 200) continue
                        ultimoAviso = agora
                        if (total > 0) {
                            val pct = ((lidos * 100) / total).toInt()
                            aoAndar("baixando " + pct + "%   " + emMb(lidos) + " de " + emMb(total), pct)
                        } else {
                            // Sem Content-Length nao da para calcular percentual; o tamanho ja
                            // baixado ainda mostra que esta andando.
                            aoAndar("baixando " + emMb(lidos), INDEFINIDO)
                        }
                    }
                }
            }
            aoAndar("baixado", 100)
            if (destino.length() < 100_000) null else destino
        } catch (e: Throwable) {
            Log.e(TAG, "download falhou", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Instala pelo Shizuku, como o Impulse faz nesta central.
     *
     * O arquivo é copiado para /data/local/tmp antes: o `pm` roda com outro contexto e não enxerga
     * o diretório privado do aplicativo.
     */
    private fun instalar(arquivo: File): String? {
        val saida = CaptureService.rodarComandoShizuku(
            arrayOf(
                "sh", "-c",
                "cp '${arquivo.absolutePath}' /data/local/tmp/vt-update.apk && " +
                    "chmod 644 /data/local/tmp/vt-update.apk && " +
                    "pm install -r -d /data/local/tmp/vt-update.apk 2>&1; " +
                    "rm -f /data/local/tmp/vt-update.apk"
            )
        )
        if (saida.contains("Success", ignoreCase = true)) return null

        // A central da montadora bloqueia `pm install`, e o bloqueio só cai com o hook que o
        // Impulse injeta. Dizer "Shizuku indisponível" aqui mandaria a pessoa olhar o lugar errado.
        if (saida.contains("beantechs", ignoreCase = true) ||
            saida.contains("INSTALL_FAILED", ignoreCase = true)
        ) {
            return "a central bloqueou a instalação. Abra o Impulse uma vez e tente de novo.\n\n" + saida
        }
        return saida.ifBlank { "sem resposta do instalador" }
    }
}
