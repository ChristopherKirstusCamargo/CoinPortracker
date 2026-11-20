# Relatório de testes — v3.1.0 — 13/11/2026 (horário de Brasília)

## JVM (Robolectric) — `./gradlew testDebugUnitTest`
**139 testes descobertos, 106 executados, 33 pulados por opção, 0 falhas.** Os pulados são capturas (`-Pshots=true`) e testes ao vivo (`-Plive=true`).
Novos na v3.1: `RealPnlTest` (P&L real), `SrPipelineTest` (7: blocos sem emenda, imagem menor que o bloco, prazo, redução Lanczos sem serrilhado, IA→reserva em fotos e GIFs, orçamento de GIF), `FmtBigTest` (10: bi/tri/qua, negativos, quantidades, % enormes, tamanho máximo).
Capturas: 28 telas com `-Pshots=true -PshotsDir=previews/v31` → `previews/v31/` (inclui 30–37: bilhões/trilhões e fonte do sistema em 130%).

## Emulador Android (API 34 x86_64, Google APIs)
- KVM: o dispositivo existe e `-accel-check` diz que dá para usar, mas o kernel do servidor dá `kvm_spurious_fault` ao criar o processador virtual (o emulador fica parado). Só foi possível rodar com emulação por software (`-accel off`): o boot levou cerca de 14 min, e instalar um APK de 69 MB levou cerca de 4,6 min.
- `MigrationInstrumentedTest.migrate1To3` (Room v1→v2→v3 conferido com os esquemas exportados): **PASSOU** no emulador.
- `SrEngineInstrumentedTest` (o modelo LiteRT empacotado carrega e reproduz a saída de referência do TFLite em Python): começou às 07:32, ficou ~15 min no primeiro modelo sem terminar (~9 min de CPU, emulação por software) e foi interrompido. **Não validado em Android.** O teste continua no repositório para rodar num aparelho real ou num emulador com aceleração de hardware. Log: `docs/emulator_instrumentation_log.txt`.
- Atualização 3.0.0 → 3.1.0 no emulador: `adb install` da 3.0.0 (versionCode 3) → `adb install -r` da 3.1.0 por cima: **Success**, versionCode 4 / 3.1.0, e o processo do app ficou no ar depois de abrir (pid presente; nenhum FATAL no logcat capturado). As capturas de tela não saíram (o sistema emulado mostrava "Process system isn't responding"), então **as telas, o worker de ampliação e a passagem por todas as telas não foram verificados no Android.** O emulador foi desligado a pedido.
- `dumpsys gfxinfo`: 438 quadros, 100% com travamento, p50 600 ms. É artefato da CPU e GPU emuladas por software; não serve como medida de fluidez.

## Build
`assembleRelease` (R8) OK. `apksigner verify`: v2 válido; certificado SHA-256 `567740b0…b3d85c`, o mesmo da v2/v3 (instala por cima). versionCode 4, versionName 3.1.0.

## v3.2.0 — 13/11/2026 (BRT)
- `./gradlew testDebugUnitTest`: **177 testes, 0 falhas, 47 pulados**. Os pulados são as capturas (`-Pshots=true`) e as leituras ao vivo (`-Plive=true`), rodadas à parte.
- Novos testes:
  - `HistoryEngineTest`: +5 (desempenho de mercado/TWR, aporte nunca é ganho, degrau em lacuna, início sem poeira).
  - `RealPnlTest`: +2 (7.924 × 7.942 → +18,83; lucro máximo/devolvido/perda).
  - `ReelMathTest`: 3 (rolos com atualização a cada 1 s).
  - `ChainsV32Test`: 10 (detecção com checksum em 13 formatos, Sui×Aptos ambíguo, parsers Stellar/Tron/XRP/Litecoin, Polygon).
  - `SrPipelineTest`: +5 (deduplicação de quadros, estimativa de orçamento, entrada reduzida, progresso por bloco, tempos JVM).
- Ao vivo (`ChainsV32Test.liveBalancesOnNewNetworks -Plive=true`): todas as 13 redes leram saldo com endereços públicos reais. Log em `docs/chains_live_v32.txt`.
- Prévias: `previews/v32/` (Início P&L 1D/1S/1M/1A/Tudo, Lucro e prejuízo com lucro e com perda, Preço do portfólio, Adicionar carteira Stellar/Tron/Sui×Aptos/EVM, estados do upscale). Números grandes continuam em `previews/v31` (30–37).
- Não testado: aparelho real e emulador (descartado a pedido). NNAPI e os tempos de IA no celular não foram verificados.

## v4.0.0 — 13/11/2026 (BRT)
- `./gradlew testDebugUnitTest`: **247 testes, 188 executados, 59 pulados, 0 falhas.** Os pulados são as capturas (`-Pshots=true`) e as leituras ao vivo (`-Plive=true`), rodadas à parte.
- Novos testes Dev: `DevValidatorTest` (11: URL, timeouts, chain id, contrato, recusa de chave/seed/mnemonic, redação), `DevSourcesTest` (5: ordem, desligar, fallback, failover), `DevConfigRoundTripTest` (3: export/import, chaves desconhecidas, rejeição de inválido).
- Testes de Receber/vídeo (já na árvore): `PaymentUriTest`, `ReceiveAssetsTest`, `VideoFormatTest`.
- Prévias: `previews/v40/` (início Dev, endpoints Ethereum, manual, diagnóstico, redes e tokens) e `previews/v33/` (Receber e recorte).
- `assembleRelease` (R8) OK. `apksigner verify`: v2 válido nos dois APKs; certificado SHA-256 `567740b090706df5666afbc63c02b1ed484d9bf152d3338c35c2ec8197b3d85c`, o mesmo da v2/v3 (instala por cima). versionCode 6, versionName 4.0.0.
- Tamanho vs 3.2.0: universal 28.015.300 B (26,72 MiB, +1,52 MiB); arm64 14.347.537 B (13,68 MiB, +1,52 MiB). O acréscimo está quase todo no `classes.dex` (Media3 + ZXing + código novo); as bibliotecas nativas não cresceram.
- Não testado: aparelho real e emulador (descartado a pedido).


## v4.1.0 — 13/11/2026 (BRT)
- `./gradlew testDebugUnitTest --offline`: **278 testes, 205 executados, 73 pulados, 0 falhas.** Os pulados são as capturas (`-Pshots=true`) e as leituras ao vivo (`-Plive=true`), no padrão das versões anteriores.
- Novos testes: `TaxEngineTest` (rateio do total pago, corte em 31/12, isenção mensal, staking como rendimento, códigos, alíquotas, CSV, ida e volta das regras, reconciliação da posição atual quando o histórico é curto), `ActivityClassifierTest`, extensão de `AlertEvaluatorTest` (`PORTFOLIO_DAY`) e `RealPnlTest` (rendimento de staking fora do Resultado; principal plano).
- Prévias: `previews/v41/` (Início com ações e anel da meta, Análise com compartilhar na Alocação, Atividade com filtros, Contábil, Alertas, Metas, Segurança e privacidade, baixo consumo, Ajustes, Sobre, Lucro e prejuízo, Ocultos vazio, regras fiscais no Dev, Início no tema claro).
- `assembleRelease` (R8) OK. `apksigner verify`: v2 válido nos dois APKs; certificado SHA-256 `567740b090706df5666afbc63c02b1ed484d9bf152d3338c35c2ec8197b3d85c`, o mesmo da v2/v3/v4.0 (instala por cima). versionCode 7, versionName 4.1.0.
- Tamanhos: universal `dist/CoinPortracker-4.1.0.apk` 28.065.104 B (26,76 MiB, SHA-256 `2052c2f800f116d44a01574e4d85ab28dbc755355e9b65c3e75352b1298e3151`); arm64 `dist/CoinPortracker-4.1.0-arm64.apk` 14.397.341 B (13,73 MiB, SHA-256 `fc7b7e8f21a38683457c25f331caa504367931aef0755bdd9e84e31956da15fb`). Sem upload.
- Não testado: aparelho real e emulador (descartado a pedido).

## v5.0.0 — 13/11/2026 (BRT)
- Integração das frentes v5-A, v5-B e v5-C em `master` (ver `docs/V50_SUMMARY.md`).
- `./gradlew testDebugUnitTest --offline`: **359 testes, 262 executados, 97 pulados, 0 falhas.** Os pulados são as capturas (`ScreenshotTest`, 92, `-Pshots=true`) e as leituras ao vivo (5, `-Plive=true`).
- Novos na v5: `DataMathV5Test` (A); `HttpClientTest`, `UrlGuardTest`, `DevSecurityTest`, `BoundedReadTest`, `TaxRulesValidationTest`, `SecretDetectionTest`, `MigrationTest`, `MarketFilterTest`/`SparkRangeTest` (faixa mínima unificada em 3%), `CoinGeckoParseTest`, `ReceiveSafetyTest`, `RegexBench` (B); `ScreenshotTest.v50*` (C, rodam com `-Pshots=true`); `FmtBigTest` +2 (percentual limitado a ">+99.999%").
- `assembleRelease` (R8) OK, regras keep de media3/zxing mantidas. `apksigner verify`: v2 válido nos dois APKs; certificado SHA-256 `567740b090706df5666afbc63c02b1ed484d9bf152d3338c35c2ec8197b3d85c` (mesmo das versões anteriores, instala por cima). versionCode 8, versionName 5.0.0.
- Tamanhos: universal `dist/CoinPortracker-5.0.0.apk` 28.087.760 B (26,79 MiB, SHA-256 `6b7182dcef20c4863a2ab96e22f46fdb9156e30f69c662bd2f5a6df116fee336`); arm64 `dist/CoinPortracker-5.0.0-arm64.apk` 14.419.997 B (13,75 MiB, SHA-256 `5d03dd6294c0b9bb502168c0a73a992ef71797ab75970cdd3af4a32648dd093f`). Sem upload.
- Não testado: aparelho real e emulador (descartado a pedido).
