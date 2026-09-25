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
    private val travaDoArquivo = Any()

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

        /** Teto do atraso reenviado numa abertura, para nao encher a memoria. */
        private const val ATRASO_MAX = 3000

        /** Insistencia na conexao: o Shizuku pode nao estar de pe quando o carro liga. */
        private const val TENTATIVAS_CONEXAO = 40
        private const val ESPERA_CONEXAO_MS = 15000L

        /** De quanto em quanto o vigia confere se a ligacao com o carro ainda responde. */
        private const val ESPERA_VIGIA_MS = 30000L

        /**
         * Ritmo da atualizacao em segundo plano.
         *
         * A primeira checagem espera a partida passar: o comeco de um ciclo e justamente um trecho
         * que se quer capturar inteiro, e instalar reinicia o app. Depois, de tres em tres horas -
         * a consulta e anonima ao GitHub, que tem teto por hora e por rede.
         */
        private const val PRIMEIRA_CHECAGEM_MS = 10L * 60_000L
        private const val INTERVALO_CHECAGEM_MS = 3L * 60L * 60_000L
        private const val ESPERA_OCUPADO_MS = 60_000L
        private const val ESPERA_APOS_FALHA_MS = 60L * 60_000L

        /** Ritmo da sonda de atuacao: e transacao de binder, nao custa quase nada. */
        private const val ESPERA_SONDA_MS = 60000L

        /** Volta do laco da sonda. Curto so para atender na hora o pedido de amostra. */
        private const val PASSO_SONDA_MS = 3000L

        /** De quanto em quanto a sonda registra mesmo sem mudanca, para ancorar a linha do tempo. */
        private const val ESPERA_ANCORA_MS = 900000L

        /**
         * Pedido de amostra no instante que interessa.
         *
         * O defeito que se investiga aparece num momento exato - a pessoa tranca o carro e o vidro
         * nao sobe. Uma sonda de minuto em minuto quase sempre erraria esse instante por algumas
         * dezenas de segundos, e "estava bem um minuto antes" e uma resposta pior do que parece.
         * Quando a tranca ou o desligamento sao anunciados, a sonda e chamada na hora.
         */
        private val momentoDecisivo = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Quando fazer a leitura de recepcao que segue a PARTIDA do carro (0 = nenhuma marcada).
         *
         * Em 25/09, no carro do dono, o volante ja estava morto tres minutos depois de o Impulse
         * subir. A sonda de recepcao so olhava no instante decisivo — tranca e desligamento —, que e
         * o fim do ciclo: chegaria tarde justamente no caso mais comum. A partida e um sinal de
         * graca, que ja chega pelo canal de dados: o Impulse renasce a cada ignicao.
         */
        private val alvoRecepcaoMs = java.util.concurrent.atomic.AtomicLong(0L)

        /** Espera entre a partida e a leitura: tempo de o Impulse subir e se registrar. */
        private const val ATRASO_RECEPCAO_PARTIDA_MS = 75_000L

        /** Chaves cujo anuncio marca a hora de medir a atuacao. */
        private val GATILHOS_DE_SONDA = setOf(
            "car.basic.door_lock_status",
            "car.basic.driving_ready_state"
        )

        /**
         * Ritmo da leitura da configuracao do Impulse.
         *
         * Raro de proposito: cada leitura e um comando no Shizuku, e comando no Shizuku vaza alguns
         * kilobytes contra um heap de 96 MB. A cada dez minutos sao seis por hora, que nao movem o
         * ponteiro; a cada minuto ja seriam sessenta, e a ferramenta passaria a empurrar o carro
         * para o defeito que veio medir.
         */
        private const val ESPERA_CONFIGURACAO_MS = 600000L

        /** Teto por POST: acima disso o ntfy recusa a mensagem. */
        private const val CORPO_MAX = 1800

        /** Ritmo do envio ao vivo. Sobe sozinho quando o servidor recusa, volta quando aceita. */
        private const val INTERVALO_MIN_MS = 2000L
        private const val INTERVALO_MAX_MS = 30000L

        /**
         * Chaves que nao entram nem no arquivo.
         *
         * Duas razoes distintas, na mesma lista. A primeira e volume: medindo as capturas reais,
         * `battery_voltage` sozinha era 80% de todas as mudancas, e o assunto - vidros, teto,
         * portas, tranca, retrovisor - nao chega a 5%. Sao grandezas analogicas que mudam varias
         * vezes por segundo e nao dizem nada sobre o que se investiga; guardadas, transformam um
         * registro de minutos num arquivo de quatro megabytes que nao se consegue enviar.
         *
         * A segunda e privacidade: `vin_code` e o chassi inteiro. Deste aplicativo sai so o final,
         * que serve de etiqueta; o numero completo nao pode nem ser gravado.
         *
         * O retrato de abertura continua registrando as analogicas, porque uma leitura unica nao
         * custa nada e da o ponto de partida. O chassi nao: esse fica de fora em todo lugar.
         */
        private val SEGREDO = setOf("car.basic.vin_code")

        private val RUIDO = SEGREDO + setOf(
            "car.basic.battery_voltage",
            "car.basic.engine_speed",
            "car.basic.vehicle_speed",
            "car.basic.vehicle_speed_since_reset",
            "car.basic.avg_vehicle_speed_since_startup",
            "car.basic.steering_wheel_angle",
            "car.basic.inside_temp",
            // car.basic.outside_temp NAO entra aqui: e condicao obrigatoria da abertura automatica
            // da cortina do teto, entao sem ela nao da para explicar uma cortina que nao abriu. Muda
            // devagar - 51 vezes numa tarde inteira - e nao pesa.
            "car.basic.coolant_temp",
            "car.basic.transmission_oil_temp",
            "car.basic.instant_fuel_consumption",
            "car.basic.avg_fuel_consumption",
            "car.basic.cur_journey_avg_fuel_consume",
            "car.basic.remain_fuel_percentage",
            "car.basic.accumulated_odometer",
            "car.basic.cur_journey_odometer",
            "car.basic.remain_odometer",
            "car.basic.total_odometer",
            "car.ev_info.total_odometer",
            "car.ev_info.electric_mode_remain_odometer",
            "car.ev_info.fuel_mode_remain_odometer",
            "car.ev_info.cur_charge_current",
            "car.ev_info.power_battery_current",
            "car.ev_info.power_battery_voltage",
            "car.ev_info.phev_ahd_voltage",
            "car.ev_info.motor_speed",
            "car.ev_info.rear_motor_speed",
            "car.ev_info.soc_of_battery",
            "car.ev_info.charge_remaining_time",
            "car.ev_info.economic_guide_range",
            "car.ev_info.energy_consume_info",
            "car.ev_info.energy_output_percentage",
            "car.ev_info.energy_recovery_info",
            "car.ev_info.fuel_consume_info",
            "car.ev_info.cycle_energy_consume_info",
            "car.ev_info.cycle_fuel_consume_info",
            "car.ev_info.avg_energy_consume_info_since_reset",
            "car.ev_info.avg_energy_consume_info_since_startup",
            // Estas sairam da medicao do arquivo completo de um carro, nao de palpite: sozinhas
            // valiam outros 11% do volume. Com elas na lista, o registro de uma tarde inteira cai
            // de 4,6 MB para 0,15 MB.
            "car.ev_info.economic_guide_level",
            "car.ev_info.energy_drive_state",
            "car.off_road_setting.tab_effect_display",
            "car.ipk_info.bsd_lca_warning_reqleft",
            "car.ipk_info.bsd_lca_warning_reqright",
            "car.intelligent_driving_info.tja_ica_state"
        )

        /** O que vale acompanhar ao vivo quando o retrato inteiro nao cabe no canal. */
        private val INTERESSE = listOf(
            "window", "sunroof", "skylight", "door", "lock", "mirror_fold",
            "power_state", "driving_ready", "gear",
            // A cortina do teto e um assunto proprio, e nao casa com nenhum dos termos acima:
            // "sunshade_status" nao contem "sunroof". Sem ela, uma cortina que nao abriu fica
            // invisivel ao vivo. outside_temp entra junto por ser condicao da abertura.
            "shade", "outside_temp"
        )

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

        /**
         * Roda um comando pelo Shizuku e devolve a saida.
         *
         * Existe so para a auto-atualizacao: `pm install` precisa de privilegio, e o Shizuku ja esta
         * aqui. Os canos sao fechados um a um, e o processo destruido no finally - o servidor do
         * Shizuku vaza por processo criado, e este aplicativo nao deve piorar o problema que veio
         * ajudar a diagnosticar.
         */
        fun rodarComandoShizuku(comando: Array<String>): String {
            val binder = rikka.shizuku.Shizuku.getBinder() ?: return ""
            val servico = moe.shizuku.server.IShizukuService.Stub.asInterface(binder) ?: return ""
            var processo: moe.shizuku.server.IRemoteProcess? = null
            return try {
                processo = servico.newProcess(comando, null, null) ?: return ""
                try { processo.outputStream?.close() } catch (e: Exception) {}
                val saida = StringBuilder()
                processo.inputStream?.let { pfd ->
                    try {
                        java.io.BufferedReader(
                            java.io.InputStreamReader(java.io.FileInputStream(pfd.fileDescriptor))
                        ).use { leitor ->
                            var linha = leitor.readLine()
                            while (linha != null) {
                                saida.append(linha).append('\n')
                                linha = leitor.readLine()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "leitura da saida falhou", e)
                    } finally {
                        try { pfd.close() } catch (e: Exception) {}
                    }
                }
                processo.waitFor()
                saida.toString().trim()
            } catch (e: Exception) {
                Log.w(TAG, "comando pelo Shizuku falhou", e)
                ""
            } finally {
                try { processo?.destroy() } catch (e: Exception) {}
            }
        }

        /** Devolve se o servidor aceitou. Recusa nao pode virar perda silenciosa. */
        fun enviar(corpo: String): Boolean {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL("https://ntfy.sh/" + CANAL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                val codigo = conn.responseCode
                if (codigo !in 200..299) Log.w(TAG, "canal respondeu " + codigo)
                codigo in 200..299
            } catch (e: Exception) {
                // Sem rede o registro local continua completo; o vivo e um extra, nao a fonte.
                Log.w(TAG, "publicacao falhou", e)
                false
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
                        // "Recusar e nao perguntar de novo": o Shizuku nao mostra mais a janela, e so
                        // da para autorizar pelo proprio app dele. Precisa aparecer distinto daqui,
                        // porque o que se pede a pessoa e outra coisa.
                        rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale() -> "recusado"
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

        /**
         * Conta o que a pessoa respondeu na janela do Shizuku.
         *
         * Sem isto, daqui so se via "esperando autorizacao" repetido a cada abertura - e nao dava
         * para saber se a janela nem apareceu, se foi fechada, ou se a pessoa recusou.
         */
        fun avisarAutorizacao(ctx: Context, concedida: Boolean) {
            Thread {
                try {
                    val agora = System.currentTimeMillis()
                    val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(agora))
                    enviar(
                        "{\"carro\":\"" + etiquetaDe(ctx) + "\",\"t\":\"" + hora + "\",\"ms\":" + agora +
                            ",\"tipo\":\"autorizacao\",\"resultado\":\"" +
                            (if (concedida) "concedida" else "recusada") + "\"}"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "aviso de autorizacao falhou", e)
                }
            }.start()
        }

        @Volatile
        var estado: String = "parado"
            private set

        /**
         * Estado do envio sob demanda, que e o unico com inicio e fim definidos.
         *
         * O fluxo ao vivo nunca acaba - o carro nao para de publicar - entao ele nao serve para
         * dizer "enviado". O botao fixa um alvo: as linhas que o arquivo tinha no momento do toque.
         * Assim a tela conta para baixo e a confirmacao significa alguma coisa.
         */
        @Volatile
        var envioAtivo: Boolean = false
            private set

        @Volatile
        var envioAlvo: Int = 0
            private set

        @Volatile
        var envioFeito: Int = 0
            private set

        @Volatile
        var envioRecusas: Int = 0
            private set

        @Volatile
        var envioConcluidoEm: Long = 0L
            private set

        /** Motivo da falha, para a tela poder dizer o que houve em vez de um "nao deu". */
        @Volatile
        var envioFalha: String = ""
            private set

        /**
         * Grava a captura num repositorio privado no GitHub.
         *
         * E o caminho preferido porque o canal publico tem cota diaria por rede, e um dia de testes
         * a esgota - foi o que aconteceu. Aqui o registro fica guardado, privado, com o nome do
         * carro e a hora, em vez de depender de uma janela de retencao de horas.
         *
         * A credencial vem do build e e de baixo privilegio de proposito: escreve num unico
         * repositorio que so guarda captura. Como o aplicativo e publico, parta do principio de que
         * ela pode ser extraida; o estrago possivel e escrever arquivo la, e revogar e um clique.
         */
        fun subirParaGitHub(arquivo: File, etiqueta: String, extensao: String = "ndjson"): String? {
            val token = BuildConfig.GITHUB_TOKEN
            if (token.isEmpty()) return "sem credencial no aplicativo"
            var conn: HttpURLConnection? = null
            return try {
                val quando = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
                val caminho = "capturas/" + etiqueta + "/" + quando + "." + extensao
                val conteudo = android.util.Base64.encodeToString(
                    arquivo.readBytes(), android.util.Base64.NO_WRAP
                )
                val corpo = "{\"message\":\"captura do carro " + etiqueta + "\",\"content\":\"" +
                    conteudo + "\"}"

                conn = URL("https://api.github.com/repos/" + BuildConfig.GITHUB_REPO +
                    "/contents/" + caminho).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.setRequestProperty("Authorization", "Bearer " + token)
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "impulse-vidros-travas")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }

                val codigo = conn.responseCode
                if (codigo in 200..299) {
                    null
                } else {
                    val detalhe = try {
                        conn.errorStream?.bufferedReader()?.readText()?.take(160).orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    "GitHub respondeu " + codigo + (if (detalhe.isNotEmpty()) ": " + detalhe else "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "envio ao GitHub falhou", e)
                (e.message ?: e.javaClass.simpleName)
            } finally {
                conn?.disconnect()
            }
        }

        /**
         * Sobe o arquivo inteiro numa requisicao so, como anexo.
         *
         * Mandar linha a linha pelo canal nao fecha: medido num carro, 29.935 linhas e 3,9 MB
         * depois de uma tarde ligado dariam mais de mil envios com limite de taxa no meio. Como
         * anexo e um pedido unico, e a resposta dele ja diz se deu certo - o que torna a
         * confirmacao na tela honesta em vez de otimista.
         */
        fun subirArquivo(arquivo: File, nome: String, progresso: (Int) -> Unit): String? {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL("https://ntfy.sh/" + CANAL).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.setRequestProperty("Filename", nome)
                conn.setRequestProperty("Title", "Captura " + nome)
                conn.setFixedLengthStreamingMode(arquivo.length())
                conn.outputStream.use { saida ->
                    arquivo.inputStream().use { entrada ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val lidos = entrada.read(buffer)
                            if (lidos <= 0) break
                            saida.write(buffer, 0, lidos)
                            total += lidos
                            progresso(total)
                        }
                    }
                }
                val codigo = conn.responseCode
                if (codigo in 200..299) {
                    null
                } else {
                    val detalhe = try {
                        conn.errorStream?.bufferedReader()?.readText()?.take(160).orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    "servidor respondeu " + codigo + (if (detalhe.isNotEmpty()) ": " + detalhe else "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "envio do arquivo falhou", e)
                (e.message ?: e.javaClass.simpleName)
            } finally {
                conn?.disconnect()
            }
        }

        @Volatile
        private var pedidoDeReenvio = false

        /** A tela pede o reenvio do arquivo inteiro; o servico atende quando puder. */
        fun pedirReenvioCompleto() {
            pedidoDeReenvio = true
        }

        // Fotografia das threads do Impulse, pedida pelo botao "O Impulse travou".
        @Volatile private var pedidoDeFoto = false
        @Volatile var fotoAtiva = false
            private set
        @Volatile var fotoPasso = ""
            private set
        @Volatile var fotoFalha = ""
            private set
        @Volatile var fotoConcluidaEm = 0L
            private set

        fun pedirFotoDoImpulse() {
            pedidoDeFoto = true
        }

        private fun consumirPedidoDeReenvio(): Boolean {
            val havia = pedidoDeReenvio
            pedidoDeReenvio = false
            return havia
        }

        @Volatile
        private var pedidoDeLimpeza = false

        /**
         * Recomeca o registro do zero.
         *
         * O arquivo so cresce, e quem instalou antes do filtro de ruido carrega megabytes de
         * velocidade e tensao de bateria que nao servem para nada aqui - e que ainda por cima
         * inviabilizam o envio. Limpar e mais honesto do que tentar enviar lixo acumulado.
         */
        fun pedirLimpeza() {
            pedidoDeLimpeza = true
        }

        private fun consumirPedidoDeLimpeza(): Boolean {
            val havia = pedidoDeLimpeza
            pedidoDeLimpeza = false
            return havia
        }

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
        // O arquivo vive no disco e o contador vivia so na memoria. Toda partida do carro a tela
        // voltava dizendo "0 eventos" sobre uma captura de dias, e quem esta ajudando de longe
        // concluiria que a noite inteira se perdeu - e poderia apagar de verdade, pelo botao
        // Limpar, o registro que ainda estava inteiro. Recontar aqui custa uma leitura.
        eventos = contarEventos(arquivo)
        etiqueta = definirEtiqueta()
        etiquetaVisivel = etiqueta
        emPrimeiroPlano()
        Thread({ remetente() }, "envio").apply { isDaemon = true }.start()
        Thread({ atenderReenvios() }, "reenvio").apply { isDaemon = true }.start()
        Thread({ vigiarConexao() }, "vigia").apply { isDaemon = true }.start()
        Thread({ sondarImpulse() }, "sonda").apply { isDaemon = true }.start()
        Thread({ atenderFotos() }, "foto").apply { isDaemon = true }.start()
        Thread({ atualizarSozinho() }, "auto-atualizacao").apply { isDaemon = true }.start()
        // O log do Impulse e do Shizuku, lido daqui: a captura conta o que houve DENTRO do Impulse
        // sem depender da versao dele nem de mexer nela. Ver ColetorDeLog.
        ColetorDeLog.iniciar(this, { arquivo.length() }) { tipo, dados ->
            anotar(tipo, dados, aoVivo = false, naTela = false)
        }
        Thread({ conectarComInsistencia() }, "conexao").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Quantas linhas ja ha no arquivo. Lido em fluxo: o registro de um dia nao cabe de uma vez. */
    private fun contarEventos(alvo: File): Int = try {
        if (!alvo.exists()) 0 else alvo.inputStream().buffered().use { entrada ->
            var total = 0
            val balde = ByteArray(65536)
            while (true) {
                val n = entrada.read(balde)
                if (n < 0) break
                for (i in 0 until n) if (balde[i] == '\n'.code.toByte()) total++
            }
            total
        }
    } catch (e: Exception) {
        0
    }

    /**
     * Entrega o arquivo inteiro quando a tela pede, com alvo fixo e fim reconhecivel.
     *
     * No carro nao ha para onde "compartilhar": a central nao tem aplicativo de mensagem e o
     * seletor do Android acabava abrindo a loja. Entao o botao manda pelo caminho que ja funciona.
     * O alvo e travado no momento do toque, e nao acompanha o que o carro continua publicando: sem
     * isso a conta so cresceria e a confirmacao nunca chegaria.
     */
    private fun atenderReenvios() {
        while (enviando) {
            try {
                if (consumirPedidoDeLimpeza()) {
                    // Guarda ANTES de apagar, sempre.
                    //
                    // Isto nao e zelo: ja custou um dia inteiro de investigacao. No carro 944020, em
                    // 23/09, o botao Limpar foi tocado tres vezes (15:06, 15:08, 15:13) e cada toque
                    // levou junto tudo o que viera antes - inclusive a unica janela em que o defeito
                    // tinha acontecido. Sobraram seis ciclos de tranca, todos funcionando, e nenhum
                    // registro da falha. Quem aperta Limpar esta querendo comecar um teste limpo, e
                    // nao abrir mao da prova; o aplicativo e que nao pode tratar as duas coisas como
                    // a mesma.
                    var guardado: String? = null
                    var guardados = 0L
                    if (arquivo.exists() && arquivo.length() > 0) {
                        guardados = arquivo.length()
                        // Subpasta dentro do proprio carro: fica obvio, ao listar, o que foi salvo
                        // de uma limpeza, sem separar o carro em dois lugares.
                        guardado = subirParaGitHub(arquivo, etiqueta + "/antes-de-limpar")
                    }
                    arquivo.delete()
                    eventos = 0
                    ultimos = emptyList()
                    envioAlvo = 0
                    envioFeito = 0
                    envioFalha = ""
                    // Depois do delete, de proposito: assim a nota de que o anterior foi guardado
                    // abre o arquivo NOVO, em vez de ser apagada junto com o antigo.
                    anotar(
                        "recomeco",
                        mapOf(
                            "motivo" to "limpeza pedida na tela",
                            "anterior" to (if (guardados == 0L) "vazio"
                                else if (guardado == null) "guardado " + guardados + " bytes"
                                else "NAO guardado: " + guardado)
                        )
                    )
                    control?.let { servico ->
                        // Sem um retrato novo, o arquivo limpo comecaria sem ponto de partida e as
                        // mudancas seguintes ficariam sem contra o que serem lidas.
                        val chaves = CarConstants.values().map { it.value }.distinct().toTypedArray()
                        identificacao()
                        retrato(servico, chaves)
                    }
                }
                if (!consumirPedidoDeReenvio()) {
                    Thread.sleep(500)
                    continue
                }
                envioFeito = 0
                envioRecusas = 0
                envioFalha = ""
                envioConcluidoEm = 0L
                // A caixa-preta do log vai para o arquivo ANTES de travar o tamanho do envio: quem
                // toca em Enviar acabou de ver o defeito, e os ultimos minutos do Impulse sao o que
                // mais interessa. Travado antes, o envio mandaria tudo menos isso.
                try {
                    ColetorDeLog.despejarAgora("envio pedido na tela")
                } catch (e: Throwable) {
                    Log.w(TAG, "despejo antes do envio falhou", e)
                }
                envioAlvo = if (arquivo.exists()) arquivo.length().toInt() else 0
                envioAtivo = true
                if (envioAlvo == 0) {
                    envioAtivo = false
                    envioConcluidoEm = System.currentTimeMillis()
                    continue
                }

                // Uma requisicao so, com o arquivo como anexo. Medido no carro: 29.935 linhas e
                // 3,9 MB depois de uma tarde ligado. Em pedaços de dois quilobytes isso daria mil e
                // quinhentos envios com limite de taxa no meio - mais de uma hora, na melhor das
                // hipoteses, e a pessoa olhando um numero que nao acaba.
                // GitHub primeiro: la o registro fica guardado e privado. O canal publico so entra
                // se nao houver credencial, e a cota diaria dele ja mostrou que nao da conta.
                val nome = "captura-" + etiqueta + ".ndjson"
                var erro = subirParaGitHub(arquivo, etiqueta)
                if (erro != null && BuildConfig.GITHUB_TOKEN.isEmpty()) {
                    erro = subirArquivo(arquivo, nome) { enviados -> envioFeito = enviados }
                }
                if (erro == null) {
                    envioFeito = envioAlvo
                } else {
                    envioFalha = erro
                    envioRecusas++
                }
                envioAtivo = false
                envioConcluidoEm = System.currentTimeMillis()
            } catch (e: InterruptedException) {
                envioAtivo = false
                return
            } catch (e: Exception) {
                Log.w(TAG, "envio sob demanda falhou", e)
                envioAtivo = false
                try { Thread.sleep(2000) } catch (i: InterruptedException) { return }
            }
        }
    }

    /**
     * Percebe quando a ligacao com o carro morre, e refaz.
     *
     * Uma captura chegou com 2,5 segundos de conteudo e trinta minutos de silencio, enquanto a tela
     * dizia "capturando". A ligacao passa pelo Shizuku, e o Shizuku cai quando o Impulse e
     * reinstalado - que e exatamente o que a pessoa faz para trocar de versao, ou seja, no meio do
     * teste. Sem este vigia, o silencio de um canal morto fica igual ao silencio de um carro parado,
     * e so se descobre horas depois, olhando o arquivo.
     */
    private fun vigiarConexao() {
        var falhasSeguidas = 0
        while (enviando) {
            try {
                Thread.sleep(ESPERA_VIGIA_MS)
                val servico = control ?: continue

                // Vivo = o binder responde. Nada mais.
                //
                // Antes isto exigia tambem que uma leitura de chave voltasse preenchida, e foi erro:
                // `fetchData` devolver vazio e resposta legitima, nao morte. O resultado foi um
                // alarme falso exatamente 30s depois de cada abertura, quatro vezes num registro so,
                // com reconexao desnecessaria. Quando o binder morre de verdade, a chamada LANCA, e
                // o catch abaixo pega.
                val vivo = try {
                    servico.asBinder().pingBinder()
                } catch (e: Exception) {
                    false
                }
                if (vivo) {
                    falhasSeguidas = 0
                    continue
                }

                // Duas falhas seguidas antes de agir: um tropeco isolado nao vale uma reconexao,
                // que custa re-registrar o ouvinte e um retrato novo.
                falhasSeguidas++
                if (falhasSeguidas < 2) continue

                falhasSeguidas = 0
                anotar("conexao_caiu", mapOf("estado" to estado))
                estado = "reconectando"
                control = null
                listener = null
                conectarComInsistencia()
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "vigia falhou", e)
            }
        }
    }

    /**
     * Atualiza sozinho, sem ninguem precisar tocar no botao verde.
     *
     * Existe porque o botao so aparece quando a tela e aberta, e a captura roda em segundo plano:
     * em 24/09, com a 1.27 publicada, nenhum testador tinha atualizado - ninguem tinha aberto o app.
     *
     * Nunca instala no meio de um envio ou da fotografia do botao laranja: instalar mata o processo,
     * e seria perder justamente o que alguem pediu para mandar. Quem religa a captura depois e o
     * aviso de app substituido, no BootReceiver.
     *
     * O risco que isto cria e real: uma versao com defeito chega a todos sozinha, e se ela quebrar
     * ao abrir, nem a atualizacao seguinte roda. Por isso toda versao passa primeiro pelo carro do
     * dono antes de ser publicada.
     */
    private fun atualizarSozinho() {
        try {
            Thread.sleep(PRIMEIRA_CHECAGEM_MS)
        } catch (e: InterruptedException) {
            return
        }
        while (enviando) {
            var espera = INTERVALO_CHECAGEM_MS
            try {
                val tag = Atualizador.consultarUltimaVersao()
                val atual = BuildConfig.VERSION_NAME
                if (tag != null && Atualizador.maisNova(tag, atual)) {
                    val ocupado = envioAtivo || fotoAtiva || pedidoDeReenvio || pedidoDeFoto ||
                        Atualizador.instalando
                    if (ocupado) {
                        espera = ESPERA_OCUPADO_MS
                    } else {
                        val para = tag.removePrefix("v")
                        anotar("atualizacao_automatica", mapOf("de" to atual, "para" to para, "etapa" to "instalando"))
                        val erro = Atualizador.baixarEInstalar(applicationContext) { _, _ -> }
                        // Dando certo, o processo morre logo depois do `pm install`. Chegar aqui
                        // com sucesso so significa que a morte ainda nao chegou.
                        if (erro == null) {
                            anotar("atualizacao_automatica", mapOf("de" to atual, "para" to para, "etapa" to "instalada, reiniciando"))
                        } else {
                            anotar("atualizacao_automatica", mapOf("de" to atual, "para" to para, "etapa" to "falhou", "erro" to erro.take(200)))
                            espera = ESPERA_APOS_FALHA_MS
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "atualizacao automatica falhou", e)
                espera = ESPERA_APOS_FALHA_MS
            }
            try {
                Thread.sleep(espera)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * Fotografa as threads do Impulse no instante em que alguem diz que ele travou.
     *
     * Existe por causa do carro 931924, em 24/09: depois de 15 minutos dirigindo pararam a projecao
     * do cluster, os botoes do volante, o ar pelo Impulse e ate os botoes fisicos - e a captura
     * mostrou o Shizuku VIVO o tempo todo, com o nosso processo atuando normalmente. O defeito
     * estava dentro do Impulse, e o padrao (varias funcoes sem relacao parando juntas, o vidro, que
     * roda por outro caminho, funcionando) e o de uma thread presa. A fotografia mostra cada thread e
     * a linha exata em que ela espera; deixa de ser palpite.
     *
     * O Android tira a fotografia sem matar o processo (sinal 3, o mesmo mecanismo do "nao
     * responde"), e ela cai em /data/anr/trace_NN. Tudo acontece num comando so pelo Shizuku - achar
     * o Impulse, pedir, esperar o arquivo novo terminar de ser escrito e devolver o conteudo - porque
     * cada comando e um processo novo no Shizuku, e processo novo e o que vaza memoria nele.
     *
     * Precisa ser tocado ANTES de limpar o cache: limpar reinicia o Impulse e leva a prova junto.
     */
    private fun atenderFotos() {
        while (enviando) {
            try {
                if (!pedidoDeFoto) {
                    Thread.sleep(500)
                    continue
                }
                pedidoDeFoto = false
                fotoFalha = ""
                fotoAtiva = true
                fotoPasso = "pedindo a fotografia das threads do Impulse..."
                fotoFalha = try {
                    fotografarImpulse() ?: ""
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    "falhou: " + (e.message ?: e.javaClass.simpleName)
                }
                // Enquanto o defeito esta acontecendo, pergunte tambem quem ainda esta registrado
                // para receber do carro. A fotografia responde "nenhuma thread esta presa"; esta
                // sonda e que responde "e chega alguma coisa para elas?". Depois da foto de
                // proposito: a foto e a que perde valor se o Impulse reiniciar no meio.
                fotoPasso = "conferindo quem ainda recebe do carro..."
                try {
                    val recepcao = SondaImpulse.recepcao("Impulse travado, marcado pelo botao")
                    if (recepcao.isNotEmpty()) anotar("recepcao", recepcao)
                } catch (e: Throwable) {
                    Log.w(TAG, "sonda de recepcao falhou no botao", e)
                }

                // A captura vai SEMPRE, com ou sem fotografia. Quem tocou esta no meio do defeito;
                // se a fotografia falhar, ficar so com uma mensagem de erro seria perder tambem o
                // que o botao Enviar ja entregava. Despejo sincrono antes de pedir o envio, senao o
                // envio travaria o tamanho do arquivo antes de os ultimos minutos do log chegarem.
                fotoPasso = "enviando a captura junto..."
                try {
                    ColetorDeLog.despejarAgora("Impulse travado, marcado pelo botao")
                } catch (e: Throwable) {
                    Log.w(TAG, "despejo do botao falhou", e)
                }
                pedirReenvioCompleto()
                fotoAtiva = false
                fotoConcluidaEm = System.currentTimeMillis()
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                fotoFalha = "falhou: " + (e.message ?: e.javaClass.simpleName)
                fotoAtiva = false
                fotoConcluidaEm = System.currentTimeMillis()
            }
        }
    }

    /** Devolve null quando deu certo, ou o motivo da falha em palavras de gente. */
    private fun fotografarImpulse(): String? {
        // '§' no lugar de cifrao, trocado no fim: o script e shell, e escrever ${'$'} a cada
        // variavel o deixaria ilegivel.
        val script = """
            P=§(pidof br.com.redesurftank.havalshisuku | cut -d' ' -f1)
            if [ -z "§P" ]; then echo "ERRO sem_impulse"; exit 0; fi
            A=§(ls -t /data/anr 2>/dev/null | head -1)
            kill -3 §P 2>/dev/null || { echo "ERRO sem_permissao"; exit 0; }
            i=0; N=""
            while [ §i -lt 15 ]; do
              sleep 1
              N=§(ls -t /data/anr 2>/dev/null | head -1)
              if [ -n "§N" ] && [ "§N" != "§A" ]; then break; fi
              N=""; i=§((i+1))
            done
            if [ -z "§N" ]; then echo "ERRO sem_arquivo"; exit 0; fi
            s1=-1; j=0
            while [ §j -lt 8 ]; do
              s2=§(stat -c %s /data/anr/§N 2>/dev/null)
              if [ "§s2" = "§s1" ]; then break; fi
              s1=§s2; sleep 1; j=§((j+1))
            done
            echo "OK pid=§P arquivo=§N"
            cat /data/anr/§N
        """.trimIndent().replace('§', '$')

        val saida = rodarComandoShizuku(arrayOf("sh", "-c", script))
        val primeira = saida.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!primeira.startsWith("OK")) {
            val motivo = when {
                saida.isBlank() -> "o Shizuku nao respondeu"
                "sem_impulse" in primeira -> "o Impulse nao esta rodando"
                "sem_permissao" in primeira -> "o Shizuku desta central nao tem permissao para isso"
                "sem_arquivo" in primeira -> "o Android nao gravou a fotografia a tempo"
                else -> primeira.take(120)
            }
            anotar("threads_falhou", mapOf("motivo" to motivo))
            return motivo
        }

        fotoPasso = "guardando e enviando a fotografia..."
        val cabecalho = primeira
        // O conteudo e do Impulse de outra pessoa: o mesmo cuidado do log - chassi inteiro e
        // tokens nao saem daqui.
        val texto = ColetorDeLog.limpar(saida.substringAfter('\n', ""))
        val threads = texto.lineSequence().count { it.startsWith("\"") }
        val quando = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        val local = File(filesDir, "threads-" + quando + ".txt")
        local.writeText(texto)

        val enviado = subirParaGitHub(local, etiqueta + "/threads", "txt")
        anotar(
            "threads_capturadas",
            mapOf(
                "origem" to cabecalho,
                "threads" to threads.toString(),
                "bytes" to local.length().toString(),
                "enviado" to (enviado ?: "ok")
            )
        )

        return if (enviado == null) null else "a fotografia foi tirada, mas nao consegui enviar: " + enviado
    }

    /**
     * Acompanha se ATUAR no carro ainda funcionaria, e em que configuracao do Impulse.
     *
     * A captura ate aqui respondia "o que o carro informou". Falta a outra metade: no relato de
     * campo o carro informa tudo certinho - a tranca chega, a velocidade chega - e mesmo assim o
     * vidro nao sobe. Sem medir a atuacao, esse caso chega como um registro impecavel de um defeito
     * invisivel. Ver SondaImpulse para o porque.
     *
     * Dois ritmos diferentes, por um motivo concreto. O teste dos binders e uma transacao e nao
     * custa nada, entao roda de minuto em minuto e pega o instante da virada. A leitura da
     * configuracao custa um comando no Shizuku, e comando no Shizuku e exatamente o que vaza memoria
     * e o mata: uma ferramenta de diagnostico que rodasse isso a cada minuto provocaria o defeito
     * que veio investigar.
     */
    private fun sondarImpulse() {
        var ateConfiguracao = 0L
        var ateSonda = 0L
        var ateAncora = 0L
        while (enviando) {
            try {
                // Passo curto para conseguir atender o pedido de amostra na hora, sem acordar a
                // sonda de verdade a cada volta.
                Thread.sleep(PASSO_SONDA_MS)
                val agora = System.currentTimeMillis()

                val naHora = momentoDecisivo.getAndSet(false)
                val noRitmo = agora - ateSonda >= ESPERA_SONDA_MS
                if (!naHora && !noRitmo) continue
                ateSonda = agora

                // Ancora: de tempos em tempos registra mesmo sem mudanca, para a linha do tempo ter
                // pontos de apoio. Sem isso, um carro que ja comeca quebrado produz uma linha no
                // inicio e mais nada, e nao da para dizer se continuava assim uma hora depois.
                val ancorar = agora - ateAncora >= ESPERA_ANCORA_MS
                if (ancorar) ateAncora = agora

                val mudou = SondaImpulse.rodada(forcar = naHora || ancorar)
                if (mudou.isNotEmpty()) {
                    val dados = LinkedHashMap(mudou)
                    if (naHora) dados["motivo"] = "trancou/desligou"
                    anotar("atuacao", dados)
                }

                // So no instante decisivo: e o momento em que o vidro deveria subir, entao e a hora
                // de saber se o Impulse ainda esta na lista de quem recebe. Fora dele nao se pergunta
                // — custa um comando no Shizuku, e a propria sonda se segura por cinco minutos.
                if (naHora) {
                    val recepcao = SondaImpulse.recepcao("trancou/desligou")
                    if (recepcao.isNotEmpty()) anotar("recepcao", recepcao)
                }

                if (agora - ateConfiguracao >= ESPERA_CONFIGURACAO_MS) {
                    ateConfiguracao = agora
                    val config = SondaImpulse.configuracao()
                    if (config.isNotEmpty()) anotar("impulse_config", config)
                    // A leitura da configuracao acabou de revelar um `impulse_desde` diferente: o
                    // Impulse renasceu em algum momento desde a ultima volta. Pergunta agora quem
                    // esta registrado, sem esperar o fim do ciclo.
                    if (SondaImpulse.nasceuDeNovo()) {
                        val nova = SondaImpulse.recepcao("Impulse renasceu", ignorarPiso = true)
                        if (nova.isNotEmpty()) anotar("recepcao", nova)
                    }
                }

                // Leitura marcada pela partida. Uma por ignicao: o alvo so e reposto no proximo
                // `driving_ready_state=1`.
                val alvo = alvoRecepcaoMs.get()
                if (alvo != 0L && agora >= alvo && alvoRecepcaoMs.compareAndSet(alvo, 0L)) {
                    val naPartida = SondaImpulse.recepcao("partida + 75 s", ignorarPiso = true)
                    if (naPartida.isNotEmpty()) anotar("recepcao", naPartida)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                // Throwable, e nao Exception: o defeito que se mede aqui se manifesta como
                // OutOfMemoryError vindo do servidor do Shizuku, que NAO e uma Exception. Deixar
                // escapar mataria esta thread em silencio exatamente no instante do defeito, e a
                // captura chegaria sem a unica medida que interessa.
                Log.w(TAG, "sonda falhou", e)
            }
        }
    }

    /**
     * Insiste na conexao em vez de desistir na primeira tentativa.
     *
     * Ligando junto com o carro, o Shizuku costuma ainda nao estar de pe quando este servico sobe.
     * Desistir ali significaria nao capturar justamente o inicio do ciclo que se quer observar.
     */
    private fun conectarComInsistencia() {
        for (tentativa in 1..TENTATIVAS_CONEXAO) {
            conectar()
            if (control != null) return
            estado = "esperando o Shizuku (tentativa " + tentativa + ")"
            try {
                Thread.sleep(ESPERA_CONEXAO_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    override fun onDestroy() {
        enviando = false
        ColetorDeLog.parar()
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
            // A identificacao vai PRIMEIRO, de proposito: registrar o ouvinte solta uma enxurrada de
            // mudancas, e quem entrasse na fila atras dela demoraria minutos para aparecer no canal.
            // De que carro se trata e a primeira pergunta, nao a ultima.
            identificacao()
            registrar(servico, chaves)
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
                // Trancar e desligar sao os instantes em que o vidro deveria subir: pede a medida
                // agora, em vez de esperar a proxima volta da sonda.
                if (key in GATILHOS_DE_SONDA) {
                    momentoDecisivo.set(true)
                    ColetorDeLog.marcarMomento("anuncio de " + key.substringAfterLast('.') + "=" + (value ?: ""))
                    // Carro ficou pronto: o Impulse esta subindo agora. Marca a leitura de recepcao
                    // para daqui a pouco, quando ele ja teve tempo de se registrar no carro.
                    if (key == "car.basic.driving_ready_state" && value == "1") {
                        alvoRecepcaoMs.set(System.currentTimeMillis() + ATRASO_RECEPCAO_PARTIDA_MS)
                    }
                }
                // Grandeza analogica e o chassi inteiro nao entram nem no arquivo.
                if (key in RUIDO) return
                // Do que sobra, o arquivo leva tudo e o canal ao vivo leva o assunto: mandar o
                // resto ao vivo fazia a fila crescer mais rapido do que o servidor aceita.
                val doAssunto = INTERESSE.any { it in key }
                anotar("mudanca", mapOf("chave" to key, "valor" to (value ?: "")), aoVivo = doAssunto)
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
        // Versao DESTE aplicativo. Sem ela, ao ler uma captura antiga so da para deduzir a versao
        // pelo que ela contem ou deixa de conter - foi preciso adivinhar, pela presenca das linhas
        // de sonda, qual versao gerou cada arquivo do carro 944020. Uma palavra resolve.
        dados["captura"] = BuildConfig.VERSION_NAME

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
                    // O chassi inteiro nao e registrado em lugar nenhum; daqui sai so o final dele,
                    // que serve de etiqueta.
                    if (bloco[i] in SEGREDO) continue
                    if (v.isNotEmpty()) mapa[bloco[i]] = v
                }
                if (mapa.isEmpty()) return@forEach
                // O retrato inteiro sao centenas de chaves. No canal ao vivo ele nao cabe: o ntfy
                // limita tamanho e taxa, e um retrato completo consome a cota que as MUDANCAS -
                // o que de fato se esta observando - precisam ter. Inteiro ele vai para o arquivo,
                // que e a fonte; ao vivo vai so o punhado de chaves do assunto.
                anotar("retrato", mapa, aoVivo = false)
                val resumo = mapa.filterKeys { chave -> INTERESSE.any { it in chave } }
                if (resumo.isNotEmpty()) anotar("retrato_resumo", resumo)
            } catch (e: Exception) {
                Log.w(TAG, "fetchDatas falhou num bloco", e)
            }
        }
    }

    private fun anotar(
        tipo: String,
        dados: Map<String, String>,
        aoVivo: Boolean = true,
        naTela: Boolean = true
    ) {
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
        // Linha de log do Impulse nao vai para a tela: chegam dezenas por minuto e empurrariam
        // para fora justamente as mudancas do carro, que e o que a pessoa olha ali.
        if (naTela) {
            val cauda = (ultimos + (hora + "  " + dados.entries.joinToString(" ") { it.key.substringAfterLast('.') + "=" + it.value })).takeLast(12)
            ultimos = cauda
        }
        // Sincronizado: quatro threads gravam aqui - o ouvinte do carro, a sonda, o vigia e o
        // coletor de log, este ultimo com volume alto. Sem trava, duas linhas podem sair
        // intercaladas e o arquivo deixa de ser lido linha a linha.
        synchronized(travaDoArquivo) {
            try {
                arquivo.appendText(linha + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "nao consegui gravar", e)
            }
        }
        if (aoVivo) fila.offer(linha)
    }

    /**
     * Escapa para JSON, TODOS os caracteres de controle - nao so aspas, barra e quebra de linha.
     *
     * O JSON proibe controle cru dentro de texto, e leitor estrito recusa a linha inteira. Tratava
     * so os quatro de sempre, e bastou o coletor de log entrar: o logcat usa tabulacao nas pilhas
     * de excecao, e a primeira captura com o botao "O Impulse travou" veio com oito linhas que um
     * leitor estrito nao abria.
     */
    private fun escapar(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' || c == '\r' -> sb.append(' ')
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Manda em lotes a cada dois segundos. Em lote porque uma mensagem por mudanca afogaria o canal
     * assim que o carro acorda e publica tudo de uma vez.
     */
    private fun remetente() {
        val lote = ArrayList<String>()
        var espera = INTERVALO_MIN_MS
        while (enviando) {
            try {
                val primeira = fila.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                if (primeira != null) lote.add(primeira)
                fila.drainTo(lote, 200)
                if (lote.isEmpty()) continue

                // Um POST por vez, limitado por tamanho: o servidor recusa corpo grande, e uma
                // recusa levava junto tudo o que estava no mesmo lote.
                val corpo = StringBuilder()
                var usadas = 0
                for (linha in lote) {
                    if (corpo.isNotEmpty() && corpo.length + linha.length + 1 > CORPO_MAX) break
                    if (corpo.isNotEmpty()) corpo.append('\n')
                    corpo.append(linha)
                    usadas++
                }
                if (usadas == 0) usadas = 1  // linha sozinha maior que o teto: vai assim mesmo

                if (publicar(corpo.toString())) {
                    repeat(usadas) { if (lote.isNotEmpty()) lote.removeAt(0) }
                    espera = INTERVALO_MIN_MS
                } else {
                    // Recusado (tipicamente limite de taxa). Guarda o lote e volta mais devagar,
                    // em vez de jogar fora justamente o inicio da captura.
                    espera = minOf(espera * 2, INTERVALO_MAX_MS)
                    Log.w(TAG, "canal recusou; nova tentativa em " + espera + "ms")
                }
                Thread.sleep(espera)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "envio falhou", e)
                try { Thread.sleep(espera) } catch (i: InterruptedException) { return }
            }
        }
    }

    private fun publicar(corpo: String): Boolean = enviar(corpo)
}
