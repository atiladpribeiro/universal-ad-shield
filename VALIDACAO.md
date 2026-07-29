# Validação — Universal Ad Shield 2.2.0

Data: 2026-07-29

Dispositivo: Galaxy Tab A9 SM-X115, Android 16 AOSP GSI, KernelSU Next e
LSPosed Irena.

## Build e inspeção

- `lintDebug` e `assembleDebug`: aprovados; `testDebugUnitTest` terminou como
  `NO-SOURCE` porque o projeto ainda não possui testes JVM isolados.
- Pacote: `io.github.atiladpribeiro.universaladshield`.
- `versionCode 42`, `versionName 2.2.0`.
- Entrada LSPosed conferida no APK:
  `io.github.atiladpribeiro.universaladshield.UniversalAdShield`.
- Build limitado a um worker e sem daemon persistente.

## Kwai (`com.kwai.video`)

- LSPosed registrou `UniversalAdShield: v2.2.0 loaded in com.kwai.video`.
- A tela Jogos, Perfil e a Activity React de Kwai Golds permaneceram fora da
  detecção de anúncio; não houve tarja preta falsa nessas telas.
- A tela Golds rolou após conceder as permissões de localização declaradas pelo
  próprio Kwai, eliminando a violação de `WifiService#getScanResults`.
- O botão React de iniciar o anúncio não respondeu nem no teste sem módulos;
  portanto o backend não abriu um criativo recompensado nesta sessão. Não foi
  registrada recompensa falsa nem fechamento forçado.
- Não houve `FATAL EXCEPTION` nem ANR no fluxo exercitado.

## Prezão Free (`br.com.mobicare.clarofree`)

- LSPosed carregou FakeGApps e Universal Ad Shield 2.2.0 no processo real.
- A abertura fria chegou à tela principal completa, sem tarja preta órfã.
- Jogos abriu a Roleta Prezão Free; um giro real terminou e creditou 5 moedas,
  sem reiniciar o jogo e sem crash.
- O log do aplicativo informou assinatura inválida do Google Play Services e a
  rede Fyber tentou acessar `fev.fyber.com/0.0.0.0:443`; o anúncio não foi
  entregue pelo ambiente de rede/Play Services.
- Os espaços de publicidade continuaram sem criativo e registraram `Ad failed
  to load : 0`; por isso não houve Activity fullscreen a proteger. O Shield não
  criou overlay falso e não houve tarja preta órfã, `FATAL EXCEPTION` ou ANR.

## Regressões verificadas

- As funções de navegação, menu, feed curto e Kwai Golds foram removidas deste
  módulo e preservadas no Kwai Enhancer.
- Mute global fora de anúncio não é aplicado.
- Activities normais do Kwai são exclusões explícitas da detecção genérica.
- Fechamento recompensado continua condicionado a evidência real do SDK.

## Conclusão técnica

O código, APK, carregamento LSPosed, isolamento de telas normais e ausência de
crash foram validados nos dois aplicativos. A conclusão remota de anúncio não
pôde ser reproduzida porque nenhum dos dois SDKs entregou um criativo acionável
no ambiente atual. Este relatório não transforma esse bloqueio externo em uma
garantia falsa de recompensa.
