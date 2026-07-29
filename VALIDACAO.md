# Validacao - Universal Ad Shield 2.0.0

Data: 2026-07-29

Dispositivo:

- Galaxy Tab A9 SM-X115
- Android 16 AOSP GSI
- KernelSU Next
- LSPosed Irena

## Resultado

- `assembleDebug`: passou.
- APK instalado como `dev.codex.universaladshield.v2`: passou.
- LSPosed carregou `UniversalAdShield: v2.0.0` em `com.kwai.video`.
- Kwai abriu automaticamente na aba Jogos:
  - log: `Kwai forced to Games tab, accepted=true`;
  - hierarquia: `KrnReactRootView` com `mBundleId='GameCenter'`.
- Toque na aba Inicio do Kwai nao removeu a aba Jogos; Home ficou desabilitado
  na hierarquia.
- Painel nativo abriu sem crash e exibiu opcoes do Kwai em portugues.
- Prezao abriu com modulo v2 no escopo.
- FakeGApps foi escopado para GMS/Play Store/GSF/Kwai/Prezao sem remover apps;
  o aviso de assinatura invalida do Play Services nao reapareceu no recorte
  apos reboot.

## Observacoes

- O pacote antigo `dev.codex.universaladshield` foi mantido instalado como
  rollback porque a assinatura do APK antigo nao bate com a chave debug atual.
- O modulo nao usa `Activity.finish()` como fallback de fechamento.
- Anuncios recompensados aguardam callback real de recompensa/conclusao antes
  de fechar.
- O teste de anuncio real depende de disponibilidade do SDK/backend; quando nao
  houve anuncio, foram validados hooks, escopo, abertura, ausencia de crash e
  estado de UI.
