# CoinPortracker v4.0 — progresso

### 10:20 BRT — início (uso semanal 61,1%)
- Base: features de Receber + vídeo (v3.3) já no master (f6070dc), sem APK 3.3. Meta: versionCode 6 / 4.0.0, mesma chave, sem emulador, sem upload.
- Tela **Configurações avançadas (Dev)** em Ajustes: fontes por rede (RPC/indexador, ordem, testar, failover), fontes de preço, redes/tokens EVM personalizados, timeouts, limpar cache, logs (redigidos), diagnóstico, exportar/importar config e backup cifrado, desfazer/restaurar padrão, manual offline em pt-BR (?).
- Sem execução de código, sem chave privada, continua watch-only.
- Próximo: modelo + validação + testes JVM, depois repositório, ganchos nos leitores e a tela.

### 10:32 BRT — v4.0.0 fechada (uso semanal 63,5%)
Feito:
- Núcleo Dev (DevConfig em `filesDir/dev-config.json`, validação, fontes com overlay e failover, health, logs redigidos, probe, tokens custom em redes EVM conhecidas).
- Ganchos nos scanners, preços, HttpClient, JsonRpc, DAOs (limpar cache) e AppContainer.
- Tela em Ajustes → Configurações avançadas (Dev): fontes por rede e de preço, redes/tokens, timeouts, atalho para atualização do saldo, cache, diagnóstico, exportar/importar, desfazer/restaurar, “?” com manual offline em pt-BR.
- Testes: 247 descobertos, 188 executados, 59 pulados, 0 falhas. Destes, 19 novos de validação/failover/round-trip.
- Prévias em `previews/v40/` (5) e `previews/v33/` (7, já commitadas antes).
- APKs assinados (mesma chave) em `dist/CoinPortracker-4.0.0.apk` e `dist/CoinPortracker-4.0.0-arm64.apk`. Sem upload. Sem APK 3.3.0.

Adiado:
- Derivação de endereço a partir de xpub no Receber (o validador já rejeita xpub).
- Saldo no portfólio de uma cadeia EVM cadastrada pelo usuário (`TokenHolding.network` ainda é o enum das redes embutidas). Tokens em redes já conhecidas funcionam.
- Upscale de IA em vídeo (de propósito: não se aplica).
- Teste em aparelho/emulador (KVM indisponível; pedido para não emular).
