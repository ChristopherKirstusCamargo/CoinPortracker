# CoinPortracker 5.0.0 — resumo da revisão

Versão de revisão (sem função nova). Integração em `master` de três frentes, nesta ordem: v5-A (dados/P&L/IR), v5-B (segurança/rede/desempenho), v5-C (UI/layout/fontes/fluidez). Detalhes: `docs/PNL_METHOD.md` (seção v5), `docs/V5_B_REVIEW.md`, `docs/V50_C_REVIEW.md`.

## A — dados, P&L e IR
- Histórico em reais pelo câmbio USD/BRL do dia (`FxRebase`, Frankfurter/BCE); sem câmbio cobrindo o período, mantém a conversão antiga. Corrige "-15,29%", "Lucro máximo" R$ 1.056 e "Maior queda" 16%.
- Lucro máximo, Devolvido e Maior queda pelo caminho do efeito preço; transferências (de fora ou entre carteiras) não mexem no lucro. Gráfico desenha `principal + lucro(t)`.
- Desempenho do período = efeito preço (staking à parte).
- Rateio do total pago por ativo; soma por ativo = Resultado real.
- APR do stake pela duração medida da época (2,2% → ~4,5%).
- Taxa do BTC não é descontada duas vezes; tokens SPL somados por mint.
- Contábil: carteira↔stake e carteira↔carteira como "Entre contas" (não venda); recompensas de stake como rendimento; informe fora da thread principal.
- Alerta de preço só compara na mesma moeda; meta em US$ sem depender do câmbio.
- Testes: `DataMathV5Test` (14).

## B — segurança, rede, desempenho
- Bloqueio: sem flash de saldos no cold start; morte do processo não desbloqueia; FLAG_SECURE ligado por padrão até carregar as configurações.
- Dev: só HTTPS público; anti-SSRF (`PublicOnlyDns` + checagem do IP real, redirecionamentos, DNS rebinding); limites de tamanho/quantidade; links de explorador validados.
- Regras fiscais validadas (`TaxRules.validate/parseValidated`), `codeFor` com fallback; IO fora da main thread.
- `HttpClient`: estado thread-safe, chamadas canceláveis, teto de 16 MB, URL inválida vira erro, pool/TLS reaproveitado, `Retry-After` em data HTTP.
- Alertas sem disparo duplo (Mutex); notificação sem valores com Ocultar saldos/bloqueio, versão pública e toque que abre o app.
- Backups: recusa segredos (seed/chave privada), ignora `banner`; detecção de segredo ampliada e mais rápida (regex pré-compiladas).
- `BoundedRead` para arquivos escolhidos; export com modo `"wt"`.
- Mercado Top-100 sem RWA tokenizados (`MarketFilter`), ranking 1..100 contínuo; parsing CoinGecko/Paprika robusto.
- Failover respeita cancelamento; QR TON url-safe; Receber filtra por formato de endereço; clipboard sensível.
- Banner HD (`MediaHd.resolveAsync`) resolvido em IO, fora da composição.
- Endpoints: Ethereum → `eth.drpc.org`; NEAR → fastnear primeiro; reservas Aptos/Cosmos.
- Testes novos: HttpClient, UrlGuard, DevSecurity, BoundedRead, TaxRulesValidation, SecretDetection, Migration v1/v2/v3, MarketFilter, SparkRange, CoinGeckoParse, ReceiveSafety, RegexBench.

## C — UI, layout, tipografia, fluidez
- Textos sem corte (fonte 100/130/200%, larguras 320–412 dp): KeyValue em 2 linhas/empilhado, chips sem quebra, pílulas/rótulos que encolhem, FitText mais leve.
- Lucro e prejuízo: sem a nota explicativa sob "Desempenho de mercado"; "Por ativo" com tag Stake; "Maior queda" em vermelho.
- Mercado: colunas fixas, sem colisão; sparkline com faixa mínima de 3% e média por blocos; some abaixo de 340 dp.
- Zero sempre cinza e sem sinal; sinal de menos único; Ocultar saldos também mascara quantidades.
- Acessibilidade: barra inferior (Tab/selected), ações (Botão), SwitchRow inteira como interruptor.
- Fluidez (sem remover animação): pull-to-refresh sem quadro contínuo ocioso; `LiveTotal` isolado; PnlChart sem realocação; glifos do SlotText medidos uma vez; LineChart e Donut respeitam Reduzir movimento.
- Prévias: `previews/v50/before` e `previews/v50/after`.

## Integração (este commit)
- Conflito único: `AccountingScreen.kt` (A e C moveram o informe para fora da thread principal). Ficou o `produceState` de C (mantém o informe anterior enquanto recalcula, esqueleto só na 1ª abertura) chamando `TaxFromPortfolio.report(h, activity, stakeSigs, rules)` de A (assinaturas de stake + rendimentos), com `stakeSigs` na chave.
- Sparkline: uma única regra. `market.SparkRange` (B) passou a ter 3% por padrão e o `sparkRange` da UI (C) delega para ele; nenhuma dupla escala.
- `Fmt.pct`: valores ≥ 100.000% viram ">+99.999%"/"<-99.999%" (antes "+3,72 bi%").
- Regras fiscais (Dev): importação recusada mostra a mensagem com o motivo.
- `ReceiveScreen`: imports sem uso removidos.
- Conferido: sem nota sob "Desempenho de mercado"; "Devolvido do lucro" sempre negativo (sem "+").

## Adiado
- Banco criptografado aberto na main thread no `Application.onCreate` (cold start); recuperação quando a chave do Keystore é invalidada ("reiniciar dados").
- Regras R8 `-keep` inteiras de media3/zxing (APK maior) — mantidas; remover exige teste em aparelho.
- SQLCipher 4.6.1 (SQLite 3.46.x); avaliar 4.10+ com rede no build.
- Coalescer `syncAll` concorrentes; baseline profile não regenerado.
- Pendências funcionais anteriores: endereço novo por xpub no Receber; saldo de redes EVM adicionadas no Dev; ATOM em staking.
- Nada testado em aparelho real ou emulador.
