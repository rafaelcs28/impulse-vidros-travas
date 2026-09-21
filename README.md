# Impulse Vidros e Travas

Ferramenta de diagnóstico, descartável, para entender por que em alguns carros o fechamento
automático de vidros e teto não acontece.

Ela **só lê**. Não há uma única escrita no carro: o serviço da montadora é usado apenas para ler
valores e para ouvir mudanças.

## O que ela captura

- Um **retrato** de todas as chaves conhecidas no momento em que o aplicativo abre, para termos o
  ponto de partida e não só as mudanças.
- Cada **mudança** publicada pelo carro depois disso, com carimbo de tempo: vidros, teto, portas,
  tranca, retrovisor, ignição, marcha, velocidade e o restante.
- A **identificação da versão do carro**: código de configuração, nível de acabamento, plataforma,
  tipo de motor, projeto e modelo. Junto vai a **versão do Impulse instalada**, que é a primeira
  coisa a conferir quando dois carros se comportam diferente.
- Uma **etiqueta do carro**, que são os seis últimos caracteres do chassi. Ela vai em toda linha,
  porque mais de uma pessoa testa ao mesmo tempo e os registros precisam ser separáveis. Vai só o
  final do chassi, nunca o número inteiro.

Tudo vai para um arquivo no próprio aparelho e, em paralelo, para um canal de acompanhamento ao
vivo, para quem está ajudando no diagnóstico ver acontecendo.

## Como usar

1. Instale o APK da aba **Releases**. O aparelho vai pedir para permitir a instalação de fontes
   desconhecidas.
2. Abra o **Impulse** uma vez, para o Shizuku subir.
3. Abra o **Impulse Vidros e Travas** e autorize quando o Shizuku perguntar.
4. Anote a etiqueta que aparece no topo, algo como `Carro 931315`. É por ela que identificamos o
   seu registro.
5. Deixe o aplicativo aberto e faça o teste: entrar no carro, destrancar, abrir um vidro, sair e
   trancar. Se puder, repita duas ou três vezes.
6. O botão **Compartilhar captura** envia o arquivo completo por onde você preferir.

Quando o diagnóstico terminar, é só desinstalar.

## Por que precisa do Shizuku

O serviço que publica as propriedades do carro não é acessível a um aplicativo comum. O Impulse
chega nele pelo Shizuku, e esta ferramenta faz exatamente o mesmo — não há caminho sem ele.

## Privacidade

O arquivo tem valores de propriedades do carro (estado de portas, vidros, marcha, velocidade,
odômetro), a identificação da versão do veículo e os **seis últimos caracteres do chassi**, usados
só para separar um participante do outro. Não vai o chassi inteiro, não vai localização, não vai
dado de conta ou de telefone.
