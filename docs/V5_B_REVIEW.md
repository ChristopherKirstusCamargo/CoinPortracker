# v5.0 – Workstream B (segurança, rede, robustez, desempenho)

Branch `v5-B` (base 43fbe15). `./gradlew testDebugUnitTest --offline`: 320 testes, 0 falhas (73 pulados = testes ao vivo/prints).

## Correções
| Sev. | Achado | Correção |
|---|---|---|
| Alta | Bloqueio: no cold start/restauração o `AppRoot` era composto antes de as configurações carregarem (hub começa com `biometricLock=false`) → saldos piscavam com o bloqueio ligado | `MainActivity` só compõe depois da 1ª leitura das configurações; estado do bloqueio vem direto do DataStore |
| Alta | Bloqueio: após morte do processo o `locked=false` salvo no Bundle desbloqueava o app | Processo novo sempre volta bloqueado |
| Média | `FLAG_SECURE`/miniatura de recentes só aplicados após carregar configurações | Ligados por padrão no `onCreate` até as configurações dizerem o contrário |
| Alta | Tela Dev aceitava `http://`, localhost, IPs internos, credenciais na URL (SSRF/rede local) | Validador só HTTPS público; `PublicOnlyDns` + interceptor de rede conferem o IP real (literais, redirecionamentos, DNS rebinding) |
| Média | Config Dev sem limites (tamanho/quantidade); links de explorador sem validação | Limite 512 KB, contagens e tamanho de texto; links validados |
| Média | Regras fiscais importadas sem validação; tabela sem código 01/02/03/10 derrubava o informe (`first{}`) | `TaxRules.validate/parseValidated`; `codeFor` com fallback; save inválido não derruba a tela; leitura/gravação fora da main thread |
| Média | `HttpClient`: `HashMap` de estado compartilhado entre provedores em paralelo (race); chamadas não canceláveis; corpo sem limite (OOM); URL inválida lançava exceção; `applyTimeouts` recriava o cliente (perdia pool/TLS) | `ConcurrentHashMap`, chamadas canceláveis (enqueue + cancel), teto 16 MB, URL inválida → `Outcome.Err`, `newBuilder()` reaproveita pool; `Retry-After` em data HTTP |
| Média | Alertas podiam disparar 2× (worker + primeiro plano simultâneos) | `Mutex` em `afterRefresh` |
| Média | Notificação mostrava patrimônio com "Ocultar saldos"/bloqueio ligados; sem versão pública; toque não abria o app | Texto sem valores nesses casos (alertas de preço seguem iguais), `setPublicVersion`, `contentIntent` |
| Média | Backup adulterado podia gravar chave privada/seed (endereço/notas) e apontar o banner para outro arquivo | `BackupCodec.validate` recusa segredos; `importMap` ignora `banner` |
| Média | Detecção de segredo não pegava seed numerada/maiúscula/com vírgulas, keypair Solana em JSON, xprv variantes, seed Stellar, `suiprivkey` | `looksLikeSecret` ampliado (regex pré-compiladas) |
| Média | Arquivos escolhidos lidos inteiros na memória; regras fiscais lidas na main thread; export com modo "w" (lixo no fim do arquivo) e exceção não tratada | `BoundedRead` com tetos; IO em `Dispatchers.IO`; modo `"wt"` |
| Média | Mercado Top-100 incluía RWA tokenizados (Figure HELOC em 9º, ouro, fundos de Tesouro/CLO) | `MarketFilter`: exclui categoria CoinGecko `tokenized-products` (cache 24 h, atualizada após mostrar a tela) + lista embutida; renumera 1..100; stablecoins ficam |
| Baixa | Parsing CoinGecko/Paprika: item sem `id` ou `/global` sem `data` lançava exceção | Itens malformados ignorados; erro vira `Outcome.Err` |
| Baixa | `runCatching` em laços de failover engolia `CancellationException` | `catchingNonCancel` |
| Baixa | QR TON com `+`/`/` quebrava `ton://transfer` | Forma url-safe |
| Baixa | Receber poderia oferecer QR de rede errada com dado inconsistente | Filtra por formato do endereço |
| Baixa | Cópia de endereço sem flag sensível | `SecureClipboard` (`EXTRA_IS_SENSITIVE`) |
| Baixa | `ShareScreen`/`rememberHdSource` faziam I/O de arquivo na composição (pedido de C) | `MediaHd.resolveAsync` em IO + cache |

## Endpoints (curl em 13/11/2026)
- `eth.llamarpc.com` morto (525) → `eth.drpc.org`.
- NEAR: `rpc.mainnet.near.org` lento (~3 s, descontinuado) → `free.rpc.fastnear.com` primeiro.
- Fallbacks novos: Aptos `aptos-rest.publicnode.com/v1`, Cosmos `rest.cosmos.directory/cosmoshub`.
- `api.blockcypher.com` (Doge, 2º) responde 502; mantido como reserva (dogechain OK).
- Blockscout Base/Arbitrum/Polygon: 403 anti-bot a partir de datacenter (já conhecido; RPC cobre saldo).
- OK: publicnode (todas), Solana oficial/publicnode, mempool/blockstream/litecoinspace, Kraken (11 pares), DefiLlama, CoinGecko, Paprika, Frankfurter, Horizon, TronGrid, Koios, toncenter, XRPL, Sui GraphQL, Jupiter, listas de tokens.

## Testes novos
HttpClientTest, UrlGuardTest, DevSecurityTest, BoundedReadTest, TaxRulesValidationTest, SecretDetectionTest, MigrationTest (v1/v2/v3 → atual + identityHash), MarketFilterTest, SparkRangeTest, CoinGeckoParseTest, ReceiveSafetyTest, RegexBench.

## Desempenho (JVM, ns/op antes → depois)
- `looksLikeSecret`: 3316 → 1664; `AddressKind.candidates`: 5250 → 4546; `redact` (linha de log): 11325 → 10373.
- Pool de conexões/TLS preservado ao aplicar timeouts (antes cada emissão da config recriava o cliente).

## Para A/C
- **C**: `Sparkline` (ui/charts/Charts.kt) deve usar `SparkRange.of(values)` + `SparkRange.norm` para o eixo Y (stablecoins viram linha quase reta). O ranking agora tem `rank` 1..100 contínuo.
- **C**: `DevTaxScreen` não mostra erro de importação inválida (agora só não aplica); `ReceiveScreen` tem import `AnnotatedString` sem uso.
- **A**: nada de P&L tocado.

## Adiado
- Cold start: `AppDatabase.encrypted` (Keystore + SQLCipher) roda no `Application.onCreate` na main thread; tornar o banco preguiçoso é refatoração maior.
- Chave do banco: arquivo corrompido/chave do Keystore invalidada causa crash em loop (sem recuperação de dados possível); precisa de UX de "reiniciar dados".
- R8: `-keep class androidx.media3.**` e `com.google.zxing.**` inteiros aumentam o APK; Media3 traz regras próprias — remover exige build release + teste em aparelho (integrador).
- Dependências (build offline, sem atualizar): SQLCipher 4.6.1 usa SQLite 3.46.x (CVE-2025-6965 exige SQL controlado pelo atacante — o app só roda as próprias queries: risco baixo). Avaliar 4.10+ quando houver rede no build.
- Coalescer `syncAll` concorrentes (hoje são serializados por Mutex, não duplicados em paralelo).
- Baseline profile não regenerado (precisa de aparelho/emulador).
