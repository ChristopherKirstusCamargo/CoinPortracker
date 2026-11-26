# Arquitetura

Um único módulo Gradle (`:app`) organizado por pacotes com dependências em uma só direção:
`ui → data → (domain, transactions, portfolio, analytics, market, blockchain, security, backup) → core`.
Toda a lógica financeira (pacotes `transactions`, `portfolio`, `analytics`, `core`) é Kotlin puro, sem Android, testável na JVM.

| Pacote | Responsabilidade |
|---|---|
| `core` | `Decimals` (BigDecimal, MathContext 34 dígitos, HALF_EVEN), `Fmt` (formatação pt-BR), `Outcome` (resultado explícito) |
| `domain` | Modelos e enums: `LedgerTx`, `TxType`, `CostMethod`, `Chain`, `WalletType`, `Currency` |
| `transactions` | `LedgerEngine` (custo médio ponderado e FIFO, lotes, realizados, rendimentos, taxas, fluxos de capital); import/export CSV/JSON, validação, impressão digital anti-duplicata |
| `portfolio` | `PnlCalculator` (patrimônio, aportes, realizado/não realizado, rendimentos, taxas, resultado total) e `Explanations` (fórmula + operações de origem de cada número) |
| `analytics` | Histórico diário, TWR, XIRR, volatilidade, drawdown, Sharpe, Sortino, VaR histórico, correlação/beta, HHI, cenários, regras de análise crítica |
| `market` | Provedores CoinGecko / CoinPaprika (cripto) e Frankfurter (câmbio USD/BRL) |
| `blockchain` | Validação de endereço público + provedores watch-only: Esplora (BTC), Solana JSON-RPC (SOL, SPL, stake), EVM JSON-RPC (nativo + ERC-20) |
| `network` | `HttpClient`: intervalo mínimo por provedor, backoff exponencial em 429/5xx, respeito a `Retry-After`, contador mensal de requisições |
| `database` | Room + SQLCipher: ativos, carteiras, transações (+ revisões), cotações, histórico de preços, câmbio, snapshots, staking, saldos on-chain, redes/tokens EVM, watchlist, uso de API |
| `security` | `DatabaseKeyManager` (chave do banco protegida pelo Keystore), `BackupCrypto` (PBKDF2 + AES-GCM), `AppLock` (biometria/credencial do aparelho) |
| `backup` / `data` | Repositórios, `PortfolioService` (combina fluxos e calcula o estado), sincronização de carteiras, backup, relatórios, WorkManager |
| `settings` | Preferências em DataStore |
| `ui` | Compose: tema, componentes, gráficos próprios (Canvas), telas |

## Fluxo de dados
1. Operações (`TransactionEntity`) são a única fonte de verdade. Editar cria uma revisão; excluir é lógico e reversível.
2. `PortfolioService` observa operações + ativos + carteiras + configurações e recalcula o `LedgerResult` em `Dispatchers.Default`
   apenas quando o livro muda. Cotações novas só refazem a valorização (`PnlCalculator`), não o livro.
3. A UI coleta um único `StateFlow<PortfolioState>`; números mudam com `RollingText` (transição animada, sem recarregar tela).
4. Preços: `MarketRepository` consulta o provedor principal, usa o alternativo se permitido e grava a origem real e o horário de cada cotação.
   Sem rede, tudo funciona com o último cache e a UI mostra "dados de HH:MM".

## Motor contábil (resumo)
- Compra: aumenta quantidade e custo (preço × qtd + taxa, se a taxa for na moeda de cotação).
- Venda: custo baixado = custo médio × qtd (médio) ou lotes mais antigos (FIFO); realizado = receita líquida − custo baixado.
- Transferência entre carteiras próprias: move quantidade e custo, não realiza resultado; taxa em cripto reduz quantidade.
- Swap: venda do ativo de origem + compra do destino pelo valor de mercado informado.
- Staking/airdrop/rendimento: entrada com custo = valor na data (ou zero sinalizado); contabilizado como rendimento, não como aporte.
- Depósito/saque de moeda fiduciária definem o capital aportado.
- Inconsistências (venda acima do saldo, preço ausente, câmbio ausente) geram `LedgerIssue` exibido ao usuário — nada é "ajustado" silenciosamente.

## Watch-only
Carteira = nome + rede + endereço público. A sincronização grava saldos observados separados do livro; o usuário vê
"observado × livro" e pode **reconciliar** (cria um ajuste manual auditável) ou **vincular** tokens (mint SPL / contrato ERC-20) a um ativo.

## Desempenho
Cálculos fora da main thread; listas com `key`; estado imutável; gráficos em `Canvas`; `preferredDisplayModeId` pede o modo de maior taxa
(120/144 Hz) na resolução atual; R8 com otimização no release; shimmer em vez de spinners; pull-to-refresh com indicador próprio.

## Atualização de saldo e de preço (6.1)
Quantidade e cotação não compartilham o mesmo relógio.

- **Quantidade** só muda com `ChainPortfolioRepository.syncAll` (leitura HTTP dos scanners) ou com um aviso que dispara essa mesma leitura. Falha de rede não avança `lastSyncAt` da carteira nem `SyncStatus.lastBalanceSync`, e uma leitura de tokens incompleta não apaga o saldo anterior (`HoldingMerge`).
- **Cotação** é DefiLlama (lote, no máximo a cada 30 s no ciclo rápido) mais o último negócio da Kraken (HTTP e, no host oficial, WebSocket v2). O patrimônio é quantidade guardada × cotação; uma cotação nova não relê as carteiras.
- **Ciclo** (`BalanceCadence`): ao vivo, 12–30 s conforme o número de carteiras e redes; 30 s ou 60 s se o usuário escolheu isso; desligado = só manual; baixo consumo = 5 min. Falha dobra a espera, com teto de 5 min.
- **Eventos** (`ChainWatch`), só em primeiro plano e fora do baixo consumo: Solana `logsSubscribe`, Bitcoin `track-addresses` no mempool.space, Litecoin no litecoinspace.org, EVM `newHeads` (no máximo uma leitura daquela rede a cada 12 s), XRP `subscribe`, Stellar SSE da Horizon. O aviso não vira saldo — só acorda o scanner. Redes sem canal público nos provedores já usados continuam no polling.
- A stake nativa de Solana (`getProgramAccounts`) não entra no ciclo rápido; a leitura completa (manual, ou a cada poucos minutos) é que a atualiza.
