package br.com.rafaelcs28.vidrostravas

import android.os.IBinder
import android.util.Log
import com.beantechs.voice.adapter.IBinderPool
import com.beantechs.voice.adapter.IVehicle
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Mede, de fora do Impulse, se ATUAR no carro ainda funcionaria.
 *
 * Nasceu de um relato de campo: o fechamento dos vidros para de funcionar depois que a pessoa liga o
 * cluster personalizado, e volta uma unica vez quando ela limpa o cache. Lendo o Impulse, os dois
 * fatos se encontram. O binder que fecha os vidros e embrulhado em ShizukuBinderWrapper; quando o
 * Shizuku morre, o embrulho aponta para um processo que nao existe mais e toda chamada de atuacao
 * lanca — dentro de um catch silencioso. E o cluster personalizado e justamente o que mata o
 * Shizuku: aquele caminho dispara centenas de comandos por minuto, cada um vaza alguns kilobytes, e
 * o heap de 96 MB do Shizuku estoura em pouco mais de meia hora.
 *
 * O que torna o defeito invisivel e uma assimetria: o registro do ouvinte vai PELO Shizuku, mas os
 * dados voltam do servico da montadora direto para o aplicativo. Com o Shizuku morto o Impulse
 * continua recebendo tudo normalmente — a tranca chega, o log registra — enquanto toda ordem que ele
 * manda se perde. De dentro, nada parece errado.
 *
 * Esta sonda reproduz a situacao no nosso processo, que e o unico jeito de confirmar isso no carro
 * de outra pessoa sem pedir que ela troque a versao do Impulse. A cada rodada:
 *
 *  - o embrulho GUARDADO desde o inicio responde? (e o que o Impulse tem na mao)
 *  - um embrulho PEDIDO AGORA responde? (e o que ele teria se tivesse renovado)
 *
 * A diferenca entre as duas e o diagnostico inteiro. Se a velha falha e a nova funciona, o culpado e
 * o embrulho vencido, e a correcao e renovar. Se as duas falham, o Shizuku simplesmente nao esta de
 * pe, e o assunto e outro.
 *
 * SO LEITURA. `getWindowsStatus` consulta; nada aqui comanda o carro. O AIDL de IVehicle e copiado
 * inteiro de proposito, com os metodos na ordem original: em AIDL o codigo da transacao e a POSICAO
 * do metodo, entao remover os que nao usamos deslocaria os demais e uma consulta viraria um comando.
 */
object SondaImpulse {

    private const val TAG = "SondaImpulse"
    private const val PACOTE = "br.com.redesurftank.havalshisuku"
    private const val SERVICO_POOL = "com.beantechs.voice.adapter.VoiceAdapterService"

    /** Codigo do IVehicle dentro do IBinderPool da montadora, o mesmo que o Impulse pede. */
    private const val CODIGO_VEHICLE = 6

    /** Piso entre duas sondas de recepcao. Ver `recepcao` para o porque de ser raro. */
    private const val ESPERA_RECEPCAO_MS = 300_000L

    /**
     * Opcoes do Impulse que entram na captura — lista FECHADA, e assim tem que continuar.
     *
     * O arquivo de preferencias e de outra pessoa e guarda coisas que nao dizem respeito a este
     * diagnostico. A leitura acontece com um `grep` no proprio carro, por estes nomes: o que nao
     * esta aqui nao chega nem a sair do arquivo. Nomes novos so entram junto com a razao de entrar.
     */
    private val OPCOES = listOf(
        // A variavel que o relato apontou.
        "enableVirtualCluster",
        "virtualClusterTheme",
        "currentClusterTemplate",
        "currentClusterDisplay",
        "activeCustomTheme",
        "enableCustomMenu",
        // O que se espera que funcione, para nao confundir "desligado" com "quebrado".
        "closeWindowOnPowerOff",
        "closeSunroofOnPowerOff",
        "closeWindowOnLock",
        "closeSunroofOnLock",
        "closeWindowsOnSpeed",
        "closeSunroofOnSpeed"
    )

    /**
     * O embrulho pego uma unica vez, e nunca renovado de proposito.
     *
     * E a copia fiel do que o Impulse carrega: ele tambem pega no inicio e segura. Renovar isto
     * apagaria justamente o que se quer medir.
     */
    @Volatile
    private var vehicleGuardado: IVehicle? = null

    @Volatile
    private var jaTentouGuardar = false

    /** Ultimo resultado, para so registrar quando algo muda. */
    @Volatile
    private var ultimoEstado: String = ""

    @Volatile
    private var ultimaConfiguracao: String = ""

    @Volatile
    private var ultimoTesteDeShellMs = 0L

    @Volatile
    private var ultimaRecepcaoMs = 0L

    /**
     * Uma rodada da sonda. Devolve o que mudou, ou vazio quando esta tudo igual ao anterior.
     *
     * Devolver vazio quando nada muda e deliberado: a captura de um dia inteiro precisa caber num
     * envio, e uma linha por minuto dizendo "continua tudo bem" enche o arquivo com o que ja se
     * sabe. O que interessa e o instante da virada — e por isso `forcar`, que marca a hora certa:
     * o momento de trancar o carro, que e quando o vidro deveria subir.
     */
    fun rodada(forcar: Boolean = false): Map<String, String> {
        val shizukuVivo = try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }

        if (!jaTentouGuardar) {
            jaTentouGuardar = true
            vehicleGuardado = pedirVehicle()
        }

        val velho = testar(vehicleGuardado)
        val novo = testar(pedirVehicle())
        val servidor = marcaDoServidor()

        val estado = "shizuku=" + (if (shizukuVivo) "vivo" else "morto") +
            " guardado=" + velho + " novo=" + novo + " servidor=" + servidor
        if (estado == ultimoEstado && !forcar) return emptyMap()
        ultimoEstado = estado

        val saida = linkedMapOf(
            "shizuku" to (if (shizukuVivo) "vivo" else "morto"),
            "atuacao_guardada" to velho,
            "atuacao_nova" to novo,
            "servidor" to servidor
        )
        // So quando ja ha falha: separa "o Shizuku nao serve mais comando" de "o Shizuku nao serve
        // mais transacao". As duas coisas quebram juntas se o heap dele entupiu, e e essa a
        // diferenca entre religar o Shizuku e so renovar o binder. Raro de proposito: este teste
        // gasta um comando, e comando e o que entope.
        if (velho != "ok(4)" || novo != "ok(4)") {
            saida["shell"] = testarShell()
        }
        return saida
    }

    /**
     * Identidade do servidor do Shizuku a que estamos ligados.
     *
     * E o que separa os dois defeitos de sintoma identico. Se esta marca MUDA entre duas rodadas, o
     * servidor reiniciou, e um embrulho pego antes disso ficou para tras — renovar resolve. Se ela
     * NAO muda e mesmo assim as chamadas falham, e o mesmo servidor de sempre recusando tudo, e
     * renovar nao vai adiantar nada: so religar o Shizuku. Sai de graca, sem gastar comando.
     */
    private fun marcaDoServidor(): String = try {
        val b = rikka.shizuku.Shizuku.getBinder()
        if (b == null) "nenhum" else Integer.toHexString(System.identityHashCode(b))
    } catch (e: Throwable) {
        "erro"
    }

    private fun testarShell(): String {
        val agora = System.currentTimeMillis()
        if (agora - ultimoTesteDeShellMs < 300_000L) return "nao_testado"
        ultimoTesteDeShellMs = agora
        return try {
            val r = CaptureService.rodarComandoShizuku(arrayOf("sh", "-c", "echo vivo"))
            if (r.contains("vivo")) "ok" else "vazio"
        } catch (e: Throwable) {
            e.javaClass.simpleName
        }
    }

    /**
     * Configuracao do Impulse e ha quanto tempo o processo dele esta de pe.
     *
     * O tempo de processo e o que denuncia a limpeza de cache: limpar cache forca a parada do
     * aplicativo, entao um processo recem-nascido no meio do dia marca o momento em que a pessoa
     * "consertou" — e e exatamente dali que se conta quanto tempo durou ate parar de novo.
     *
     * Roda raro de proposito. Ler isto custa um comando no Shizuku, e comando no Shizuku e o que
     * vaza memoria e o mata; uma ferramenta de diagnostico nao pode causar o defeito que investiga.
     */
    fun configuracao(): Map<String, String> {
        val nomes = OPCOES.joinToString("|")
        val comando = "P=`pidof " + PACOTE + "`; echo \"pid=\$P\"; " +
            "stat -c inicio=%Y /proc/\$P 2>/dev/null; " +
            "grep -h -E 'name=\"(" + nomes + ")\"' " +
            "/data/user_de/0/" + PACOTE + "/shared_prefs/*.xml 2>/dev/null"

        val saida = try {
            CaptureService.rodarComandoShizuku(arrayOf("sh", "-c", comando))
        } catch (e: Throwable) {
            Log.w(TAG, "nao consegui ler a configuracao do Impulse", e)
            return emptyMap()
        }
        if (saida.isBlank()) return emptyMap()

        val dados = linkedMapOf<String, String>()
        for (linha in saida.lines()) {
            val texto = linha.trim()
            if (texto.isEmpty()) continue
            if (texto.startsWith("pid=")) {
                dados["impulse_pid"] = texto.removePrefix("pid=").trim()
                continue
            }
            if (texto.startsWith("inicio=")) {
                dados["impulse_desde"] = texto.removePrefix("inicio=").trim()
                continue
            }
            // Linhas de preferencia chegam como: name="chave" value="valor"  (ou sem value, nos
            // booleanos, onde o proprio atributo carrega o estado).
            val chave = Regex("name=\"([^\"]+)\"").find(texto)?.groupValues?.get(1) ?: continue
            if (chave !in OPCOES) continue
            // Booleano e numero guardam o valor no atributo (`value="true"`); texto guarda no corpo
            // da linha (`<string name="x">claro</string>`). Ler so o atributo devolvia "?" para
            // justamente as opcoes de tema, que sao as que interessam nesta investigacao.
            val valor = Regex("value=\"([^\"]*)\"").find(texto)?.groupValues?.get(1)
                ?: Regex(">([^<]*)</").find(texto)?.groupValues?.get(1)
                ?: "?"
            // Teto por seguranca: uma preferencia de texto pode guardar algo longo, e uma linha
            // gigante na captura atrapalha o envio sem acrescentar nada ao diagnostico.
            dados[chave] = valor.take(120)
        }
        if (dados.isEmpty()) return emptyMap()

        val assinatura = dados.entries.joinToString(";") { it.key + "=" + it.value }
        if (assinatura == ultimaConfiguracao) return emptyMap()
        ultimaConfiguracao = assinatura
        return dados
    }


    /**
     * Mede se o Impulse ainda RECEBE, que e a outra metade do diagnostico.
     *
     * Ate a 1.29 esta sonda so media ATUACAO: o embrulho guardado consegue chamar o carro? Em
     * 24/09, no carro do dono, ela respondeu "guardado=ok(4) novo=ok(4)" trinta e nove segundos
     * antes de ele marcar o Impulse como travado com os botoes do volante mortos. A fotografia das
     * threads, de um processo com trinta e um minutos de vida, nao mostrou nenhuma thread presa. As
     * teclas estavam sendo anunciadas pelo carro: a nossa captura registrou 164 delas, a ultima
     * rajada vinte segundos antes do botao. Ou seja: o carro fala, nos ouvimos, e o Impulse nao age
     * — e nao e por falta de binder nem por thread travada. Sobra a hipotese de o registro do
     * ouvinte DELE ter ficado orfao.
     *
     * Que essa hipotese tem pe esta escrito no proprio codigo do Impulse. Para o cluster ele ja
     * convive com isso: `refreshClusterCallbackIfStale` re-registra quando para de chegar relatorio,
     * com o comentario "our registration is very likely no longer being dispatched to". Para o
     * ouvinte de TECLAS nao existe nada parecido — `registerKeyEventListener` acontece uma vez,
     * quando o servico conecta, e `unregisterKeyEventListener` so no desligamento limpo. Se o
     * processo morre de outro jeito, o servico da montadora fica com um binder morto na lista, e o
     * comentario do proprio Impulse diz o que acontece depois: "the next process adds a second
     * listener rather than replacing the first".
     *
     * Esta rodada e de DESCOBERTA, nao de veredito. Nao se sabe ainda se os servicos da montadora
     * respondem a dumpsys nem em que formato listam quem esta registrado, e adivinhar o formato de
     * casa seria repetir o erro do coletor de log, que passou por tres defeitos que so o hardware
     * mostrou. Entao aqui se pergunta pouco e se guarda o que vier: nomes dos servicos, tamanho do
     * dump, quantas vezes o pacote do Impulse aparece nele e uma amostra das linhas de ouvinte. Com
     * a primeira captura de um carro de verdade da para escrever a medida certa.
     *
     * Rodar raro e so na hora certa. E um comando no Shizuku, e comando no Shizuku e o que vaza e o
     * mata; alem disso `dumpsys` de servico da montadora e terreno desconhecido. Por isso: no
     * instante decisivo (tranca/desligamento e o botao "o Impulse travou"), nunca em laco, e no
     * maximo uma vez a cada cinco minutos.
     */
    fun recepcao(motivo: String): Map<String, String> {
        val agora = System.currentTimeMillis()
        if (agora - ultimaRecepcaoMs < ESPERA_RECEPCAO_MS) return emptyMap()
        ultimaRecepcaoMs = agora

        // '§' no lugar de cifrao, trocado no fim, como no script da fotografia das threads.
        //
        // `timeout` so entra se existir nesta central: sem ele, um servico da montadora que trave
        // no dump prenderia a thread que chamou — e quem chama e a mesma que atende o botao. O
        // `-t 3` do dumpsys cobre o caso normal; o dumpsys pelado so e tentado quando o primeiro
        // nao devolveu nada, que e o sintoma de a central nao conhecer a opcao.
        val script = """
            T="timeout 8"; command -v timeout >/dev/null 2>&1 || T=""
            P=§(pidof $PACOTE | cut -d' ' -f1)
            echo "pid=§P"
            LG=§(ls -t /sdcard/Android/data/$PACOTE/files/cluster-diagnostics/cluster-events-*.log 2>/dev/null | head -1)
            if [ -n "§LG" ]; then stat -c "log tam=%s mtime=%Y" "§LG" 2>/dev/null; else echo "log ausente"; fi
            S=§(service list 2>/dev/null | grep -iE 'input|cluster|vehicle|beantechs' | awk '{print §2}' | tr -d ':' | head -8)
            echo "servicos §(echo §S | tr '\n' ' ')"
            for n in §S; do
              D=§(§T dumpsys -t 3 §n 2>/dev/null | head -300)
              if [ -z "§D" ]; then D=§(§T dumpsys §n 2>/dev/null | head -300); fi
              if [ -z "§D" ]; then echo "svc §n sem_dump"; continue; fi
              L=§(echo "§D" | wc -l | tr -d ' ')
              M=§(echo "§D" | grep -c -i redesurftank)
              C=§(echo "§D" | grep -c -iE 'listener|callback|client|observer')
              echo "svc §n linhas=§L impulse=§M ouvintes=§C"
              echo "§D" | grep -iE 'listener|callback|client|observer' | head -6 | sed "s|^|amostra §n |"
            done
        """.trimIndent().replace('§', '$')

        val bruto = try {
            CaptureService.rodarComandoShizuku(arrayOf("sh", "-c", script))
        } catch (e: Throwable) {
            Log.w(TAG, "sonda de recepcao falhou", e)
            return mapOf("motivo" to motivo, "erro" to e.javaClass.simpleName)
        }
        if (bruto.isBlank()) return mapOf("motivo" to motivo, "erro" to "sem_resposta")

        // O dump e de processo de terceiro: passa pela mesma limpeza do log e da fotografia antes
        // de virar captura.
        val saida = ColetorDeLog.limpar(bruto)

        val dados = linkedMapOf("motivo" to motivo)
        val resumo = ArrayList<String>()
        val amostra = ArrayList<String>()
        for (linha in saida.lines()) {
            val texto = linha.trim()
            when {
                texto.isEmpty() -> {}
                texto.startsWith("pid=") -> dados["impulse_pid"] = texto.removePrefix("pid=")
                texto.startsWith("log ") -> dados["log_persistente"] = texto.removePrefix("log ")
                texto.startsWith("servicos ") -> dados["servicos"] = texto.removePrefix("servicos ").take(200)
                texto.startsWith("svc ") -> resumo.add(texto.removePrefix("svc "))
                texto.startsWith("amostra ") -> amostra.add(texto.removePrefix("amostra "))
            }
        }
        if (resumo.isNotEmpty()) dados["dumps"] = resumo.joinToString(" | ").take(400)
        // Teto no que e texto livre de outro processo: a amostra existe para ensinar o formato, nao
        // para ser o dump inteiro dentro da captura.
        if (amostra.isNotEmpty()) dados["ouvintes"] = amostra.joinToString(" | ").take(1200)
        return dados
    }

    /**
     * Uma consulta de verdade ao binder, nao um ping.
     *
     * Ping so diz que o canal responde. O que interessa e o que acontece na chamada que o Impulse
     * faz quando precisa fechar um vidro — e e ela que lanca quando o embrulho venceu.
     */
    private fun testar(alvo: IVehicle?): String {
        if (alvo == null) return "sem_binder"
        return try {
            val estados = alvo.getWindowsStatus(0)
            if (estados == null) "nulo" else "ok(" + estados.size + ")"
        } catch (e: Throwable) {
            e.javaClass.simpleName
        }
    }

    private fun pedirVehicle(): IVehicle? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val bruto = sm.getMethod("getService", String::class.java)
                .invoke(null, SERVICO_POOL) as? IBinder ?: return null
            val embrulho = ShizukuBinderWrapper(bruto)
            val pool = IBinderPool.Stub.asInterface(embrulho) ?: return null
            val vehicleBinder = pool.queryBinder(CODIGO_VEHICLE) ?: return null
            IVehicle.Stub.asInterface(ShizukuBinderWrapper(vehicleBinder))
        } catch (e: Throwable) {
            null
        }
    }
}
