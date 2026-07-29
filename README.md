# Universal Ad Shield 2.0.0

Modulo LSPosed para cobrir, silenciar, acelerar quando seguro e fechar anuncios
fullscreen somente depois de evidencia real de conclusao/recompensa do SDK.

Esta versao tambem inclui um painel nativo de configuracao por aplicativo e
regras especificas opcionais para o Kwai.

## Principais recursos

- Tarja preta fullscreen com rodape maior e status tecnico em portugues.
- Bloqueio de toques e links externos durante anuncios fullscreen.
- Mute de AudioTrack, MediaPlayer, SoundPool, HTML5 video, ExoPlayer/Media3 e
  player nativo do Kwai quando ha sessao de anuncio ativa.
- Aceleracao conservadora e configuravel, com fallback para velocidades aceitas
  pelo player real.
- Fechamento automatico apenas por controle real exposto pelo SDK.
- Em anuncio recompensado, fim de video nao conta como recompensa: o modulo
  aguarda callback/estado de recompensa antes de acionar fechamento.
- Recuperacao de tarja preta orfa: se a Activity deixa de ser anuncio, o overlay
  e removido.
- Painel por app para ativar/desativar protecao, overlay, mute, aceleracao,
  bloqueio de links, auto-close e auxiliares.

## Kwai

Para `com.kwai.video`, os padroes extras ficam ligados:

- abrir e manter a aba `Jogo/Jogos`;
- bloquear a aba inicial de videos curtos;
- silenciar somente videos curtos quando eles aparecerem por corrida de UI;
- reparar toque em telas `KwaiRnActivity`/`OverseaWebActivity`, incluindo Kwai
  Golds;
- aplicar compatibilidade WebView em rotas de Kwai Golds/UG Center.

## Instalar

1. Instale o APK.
2. Ative o modulo no LSPosed.
3. Marque no escopo apenas os apps desejados.
4. Reinicie o aparelho ou force-stop no app alvo depois de mudar escopo.

No tablet de teste, a versao 2 foi instalada como pacote separado
`dev.codex.universaladshield.v2` para preservar o pacote antigo como rollback,
pois o APK antigo estava assinado com outra chave.

## Escopo usado no teste

- `com.kwai.video`
- `br.com.mobicare.clarofree`

Tambem foi ajustado o escopo do FakeGApps/microG para GMS, Play Store, GSF,
Kwai e Prezao, sem remover apps, porque o Prezao estava registrando
`requires Google Play services, but their signature is invalid`.

## Validacao feita

- Build `assembleDebug`: passou.
- Instalacao lado a lado do pacote v2: passou.
- LSPosed carregando `UniversalAdShield: v2.0.0` no Kwai: confirmado.
- Kwai abre automaticamente na aba Jogos: confirmado por log e screenshot.
- Toque em Inicio no Kwai: aba Jogos permanece ativa e Home fica desabilitado.
- Painel de configuracao abre e mostra opcoes do Kwai em portugues.
- Prezao abre com modulo v2 escopado; apos escopar FakeGApps, o erro de
  assinatura invalida do Play Services nao reapareceu no recorte testado.

## Limites conhecidos

- O modulo nao falsifica recompensa e nao encerra Activity por `finish()`.
  Isso e intencional para evitar perda de recompensa.
- Anuncios reais dependem de disponibilidade do SDK/backend no momento do teste.
  Quando o SDK nao entrega anuncio, so e possivel validar abertura, hooks,
  ausencia de crash e logs.
- Algumas redes/WebViews do Kwai podem retornar falha TLS ou backend; a versao
  2 aplica compatibilidade WebView, mas nao remove microG nem altera dados do
  app.

## Rollback

O pacote antigo `dev.codex.universaladshield` foi preservado. Para voltar, abra
o LSPosed, desative `dev.codex.universaladshield.v2`, reative o pacote antigo e
reinicie.
