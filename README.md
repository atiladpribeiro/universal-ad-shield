# Universal Ad Shield 2.1.0

Modulo LSPosed unico para cobrir, silenciar, acelerar quando seguro e fechar
anuncios fullscreen somente depois de evidencia real de conclusao ou recompensa
do SDK.

O pacote oficial e unico e:

`io.github.atiladpribeiro.universaladshield`

Nao existe uma edicao paralela ou pacote `v2`. A versao 2.1.0 consolida todas
as funcoes anteriores, o painel por aplicativo e as correcoes especificas do
Kwai e do Prezao.

## Principais recursos

- Tarja preta fullscreen com rodape maior e status tecnico em portugues.
- Bloqueio de toques e links externos durante anuncios fullscreen.
- Mute de AudioTrack, MediaPlayer, SoundPool, HTML5 video, ExoPlayer/Media3 e
  player nativo do Kwai quando ha sessao de anuncio ativa.
- Aceleracao conservadora e configuravel, com fallback para velocidades aceitas
  pelo player real.
- Fechamento automatico apenas por controle real exposto pelo SDK.
- Em anuncio recompensado, fim de video nao conta como recompensa: o modulo
  aguarda callback ou estado real de recompensa antes de acionar fechamento.
- Recuperacao de tarja preta orfa quando a Activity deixa de ser anuncio.
- Painel por app para protecao, overlay, mute, aceleracao, bloqueio de links,
  auto-close e auxiliares.
- Compatibilidade desde Android 7.0 (API 24) ate Android 15/16, respeitando as
  APIs realmente disponiveis em cada versao.

## Kwai

Para `com.kwai.video`, os padroes extras ficam ligados:

- abrir e manter a aba `Jogo/Jogos`, inclusive no `TinyLaunchActivity` que nao
  expoe IDs Android na barra inferior;
- bloquear o retorno para a aba inicial de videos curtos;
- silenciar somente os videos curtos caso a interface tente exibi-los durante
  uma corrida de inicializacao;
- reparar toque em `KwaiRnActivity` e `OverseaWebActivity`, incluindo Kwai
  Golds;
- aplicar compatibilidade WebView em rotas de Kwai Golds/UG Center.

## Instalar

1. Instale o APK da versao 2.1.0.
2. Ative `Universal Ad Shield` no LSPosed.
3. Marque no escopo somente os apps desejados.
4. Reinicie o aparelho ou force-stop no app alvo depois de mudar o escopo.

Se houver uma instalacao experimental antiga, desative e remova os pacotes
antigos somente depois de ativar o pacote oficial acima. No tablet validado,
essa migracao ja foi concluida e restou um unico modulo.

## Escopo usado no teste

- `com.kwai.video`
- `br.com.mobicare.clarofree`

O escopo do FakeGApps/microG foi mantido em GMS, Play Store, GSF, Kwai e Prezao,
sem remover nenhum aplicativo. Isso corrige o ambiente em que o Prezao
registrava assinatura invalida do Google Play Services.

## Validacao feita

- `assembleDebug`: passou.
- `lintDebug`: passou sem erros.
- APK inspecionado: pacote, versao e classe de entrada LSPosed corretos.
- LSPosed carregando `UniversalAdShield: v2.1.0` no Kwai e no Prezao.
- Abertura fria do Kwai entrou automaticamente em Jogos.
- Toque fisico em Inicio manteve Jogos ativo.
- Conteudo da aba Jogos carregou sem crash.
- Prezao abriu sem tarja preta orfa e sem erro fatal.
- Banco do LSPosed conferido apos a migracao: somente o pacote oficial esta
  instalado, ativo e escopado.

Consulte [VALIDACAO.md](VALIDACAO.md) para o relatorio completo.

## Limites reais

- O modulo nao falsifica recompensa e nao encerra Activity por `finish()`.
  Isso evita perder uma recompensa que o servidor ainda nao confirmou.
- A entrega de anuncios depende do SDK e do backend de cada rede. Sem um anuncio
  entregue no momento do teste, e possivel validar hooks, escopo, abertura,
  ausencia de crash e estado da interface, mas nao inventar uma resposta do
  servidor.
- Falhas externas de TLS ou indisponibilidade do backend do Kwai nao podem ser
  transformadas em conectividade real pelo modulo. A compatibilidade WebView e
  o ambiente microG/FakeGApps foram corrigidos sem remover apps.

## Rollback

Antes da migracao no tablet, os APKs antigos e o banco do LSPosed foram salvos
fora deste repositorio. Para uso normal, instale somente o APK oficial 2.1.0.
