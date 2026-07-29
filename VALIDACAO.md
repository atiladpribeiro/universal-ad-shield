# Validacao - Universal Ad Shield 2.1.0

Data: 2026-07-29

Dispositivo:

- Galaxy Tab A9 SM-X115
- Android 16 AOSP GSI
- KernelSU Next
- LSPosed Irena

## Resultado de build

- `assembleDebug`: passou.
- `lintDebug`: passou sem erros apos corrigir a chamada do ContentProvider para
  uma API compativel com o `minSdk 24`.
- APK: `io.github.atiladpribeiro.universaladshield`, `versionCode 41`,
  `versionName 2.1.0`.
- Entrada LSPosed no APK:
  `io.github.atiladpribeiro.universaladshield.UniversalAdShield`.

## Resultado no tablet

- O LSPosed carregou `UniversalAdShield: v2.1.0` em `com.kwai.video` e
  `br.com.mobicare.clarofree`.
- A abertura fria do Kwai entrou automaticamente na aba Jogos.
- Foi necessario suportar o `TinyLaunchActivity`, cuja barra inferior pode nao
  expor IDs Android; o fallback usa as coordenadas reais do segundo item.
- Um toque fisico em Inicio foi testado e a aba Jogos permaneceu ativa.
- O conteudo da aba Jogos carregou e nao houve `FATAL EXCEPTION`.
- O Prezao abriu sem tarja preta orfa e sem erro fatal no recorte capturado.
- FakeGApps permaneceu escopado para GMS, Play Store, GSF, Kwai e Prezao, sem
  remover aplicativos; o aviso de assinatura invalida nao reapareceu.

## Consolidacao

- Os pacotes experimentais antigos foram salvos fora do Git e desinstalados do
  tablet depois da validacao do pacote final.
- A consulta final ao Package Manager retornou somente
  `io.github.atiladpribeiro.universaladshield`.
- A consulta final ao banco do LSPosed retornou somente esse modulo, ativo para
  o usuario 0 e escopado para Kwai e Prezao.

## Observacoes

- O modulo nao usa `Activity.finish()` como fallback de fechamento.
- Anuncios recompensados aguardam callback real de recompensa ou conclusao
  antes de fechar.
- A disponibilidade de um anuncio real depende do SDK/backend. Quando o backend
  nao entrega anuncio durante a janela de teste, nao e correto afirmar que o
  fluxo de recompensa remoto foi exercitado; foram validados os hooks, o escopo,
  a abertura, a ausencia de crash e o estado da interface.
