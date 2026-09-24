package br.com.rafaelcs28.vidrostravas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Religa a captura quando a central liga.
 *
 * O evento que se quer observar - trancar o carro e ele fechar, ou nao fechar, os vidros - acontece
 * justamente em volta de desligar e ligar. Depender de alguem lembrar de abrir o aplicativo a cada
 * ciclo garante perder o ciclo. Se o Shizuku ainda nao estiver de pe neste momento, o servico
 * insiste sozinho ate estar.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val acao = intent?.action ?: return

        // Acabou de ser substituido por uma versao nova - quase sempre a atualizacao em segundo
        // plano. Instalar mata o processo antigo, e sem isto a captura ficaria parada ate a proxima
        // partida do carro, sem ninguem saber.
        //
        // Sobe SEM checar o Shizuku, de proposito, ao contrario da partida: o processo e novo e o
        // Shizuku ainda nao entregou a conexao, entao a checagem daria "nao esta rodando" e barraria
        // a religada exatamente aqui. Se o app conseguiu se atualizar, e porque tinha a
        // autorizacao; e o proprio servico insiste ate o Shizuku responder.
        if (acao == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                context.startService(Intent(context, CaptureService::class.java))
            } catch (e: Exception) {
                Log.w("CapturaVidros", "religar depois da atualizacao falhou", e)
            }
            return
        }

        if (acao != Intent.ACTION_BOOT_COMPLETED && acao != "android.intent.action.QUICKBOOT_POWERON") return
        try {
            // Sem autorizacao do Shizuku nao ha o que capturar, e subir o servico so para falhar
            // encheria a tela com uma notificacao inutil.
            if (!rikka.shizuku.Shizuku.pingBinder() ||
                rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                CaptureService.avisarAbertura(context)
                return
            }
            context.startService(Intent(context, CaptureService::class.java))
        } catch (e: Exception) {
            Log.w("CapturaVidros", "religar no boot falhou", e)
        }
    }
}
