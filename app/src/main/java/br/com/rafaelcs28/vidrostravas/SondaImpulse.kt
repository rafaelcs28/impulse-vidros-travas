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

    /**
     * Uma rodada da sonda. Devolve o que mudou, ou vazio quando esta tudo igual ao anterior.
     *
     * Devolver vazio quando nada muda e deliberado: a captura de um dia inteiro precisa caber num
     * envio, e uma linha por minuto dizendo "continua tudo bem" enche o arquivo com o que ja se
     * sabe. O que interessa e o instante da virada.
     */
    fun rodada(): Map<String, String> {
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

        val estado = "shizuku=" + (if (shizukuVivo) "vivo" else "morto") +
            " guardado=" + velho + " novo=" + novo
        if (estado == ultimoEstado) return emptyMap()
        ultimoEstado = estado

        return linkedMapOf(
            "shizuku" to (if (shizukuVivo) "vivo" else "morto"),
            "atuacao_guardada" to velho,
            "atuacao_nova" to novo
        )
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
