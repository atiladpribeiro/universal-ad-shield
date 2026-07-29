# Universal Ad Shield 2.2.0

Módulo LSPosed único para proteger anúncios em tela cheia: cobre o criativo,
silencia o áudio, tenta acelerar apenas pelo player real e fecha somente depois
de uma evidência válida de conclusão ou recompensa.

Pacote oficial único:

`io.github.atiladpribeiro.universaladshield`

Não existe pacote `v2` nem edição com referência a IA. A versão 2.2.0 mantém as
funções de anúncios das versões anteriores e remove do Shield as alterações da
interface do Kwai, que agora pertencem ao módulo separado **Kwai Enhancer**.

## Recursos

- Detecção genérica por Activity, pilha de SDK e WebView fullscreen, sem lista
  fechada de aplicativos.
- Tarja preta fullscreen com rodapé técnico em português e fonte de 12 sp.
- Bloqueio de toque e de links externos enquanto a sessão de anúncio está ativa.
- Silenciamento de AudioTrack, MediaPlayer, SoundPool, HTML5, ExoPlayer/Media3
  e players nativos detectados.
- Aceleração conservadora e configurável, com confirmação da velocidade aceita
  pelo player e fallback seguro.
- Fechamento automático por controle real do anúncio; não usa `Activity.finish()`
  para simular conclusão.
- Anúncios recompensados aguardam callback/estado real de recompensa.
- Recuperação de tarja órfã quando a Activity deixa de ser anúncio ou encerra.
- Auxílio opcional para playables, sem abrir loja, navegador ou links externos.
- Painel por aplicativo para ativar proteção, overlay, mute, aceleração,
  velocidade máxima, bloqueio externo, auto-close e auxílio de playable.
- Android 7.0 (API 24) até Android 15/16, usando somente APIs compatíveis.

## Instalação

1. Instale o APK.
2. Ative **Universal Ad Shield** no LSPosed.
3. Selecione somente os aplicativos desejados no escopo.
4. Force a parada dos aplicativos afetados ou reinicie o aparelho.

O escopo validado neste tablet foi:

- `com.kwai.video`
- `br.com.mobicare.clarofree`

## Kwai

O Shield atua somente nos anúncios do Kwai. A abertura em Jogos, a redução dos
menus e as correções da área Kwai Golds ficam no repositório **Kwai Enhancer**.
Assim, a proteção genérica não carrega regras de navegação de um aplicativo
específico.

## Limites reais

- O módulo não falsifica recompensa nem resposta do servidor.
- Sem anúncio entregue pelo SDK, é possível comprovar carregamento dos hooks,
  ausência de crash, limpeza de overlay e isolamento das telas normais, mas não
  afirmar que uma recompensa remota inexistente foi concluída.
- DNS, microG, Play Services e backend de cada rede continuam responsáveis por
  entregar o anúncio. Consulte [VALIDACAO.md](VALIDACAO.md) para os resultados e
  bloqueios externos observados no tablet de teste.

## Compilação

```bash
gradle --no-daemon --max-workers=1 clean testDebugUnitTest lintDebug assembleDebug
```

O uso de um único worker é intencional para reduzir CPU e memória durante o
build.
