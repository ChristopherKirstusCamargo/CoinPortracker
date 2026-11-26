# Provedores externos

Somente dados públicos e gratuitos. Nenhum dado do portfólio é enviado; as únicas informações que saem do aparelho são
os IDs de moedas consultados e, na sincronização watch-only, o endereço público que o usuário cadastrou.
Todas as chamadas são HTTPS (cleartext bloqueado por `network_security_config`).

| Uso | Provedor | Endpoint | Limite do plano gratuito | Política no app |
|---|---|---|---|---|
| Cotações, histórico, busca, ranking, dados globais | CoinGecko (público ou chave Demo gratuita) | `api.coingecko.com/api/v3` (`pro-api` não é usado) | ~5–15 req/min público; 30 req/min e 10k/mês com chave Demo; histórico de 365 dias | 1 req/4 s sem chave, 1 req/0,7 s com chave; cotações em lote (até 250 IDs) |
| Cotações e histórico (alternativo) | CoinPaprika | `api.coinpaprika.com/v1` | 20k chamadas/mês; histórico diário de 1 ano, só USD | 1 req/0,3 s; histórico em USD convertido por câmbio diário |
| Câmbio USD/BRL | Frankfurter (taxas de referência do BCE) | `api.frankfurter.dev/v1` | sem chave; taxas de dias úteis | 1 req/s; fim de semana usa o último dia útil |
| Bitcoin | mempool.space (fallback Blockstream) | `/api/address/{addr}`, `/txs` | público, limitado por IP | 1 req/s; troca de host em falha |
| Solana | RPC público oficial | `api.mainnet-beta.solana.com` (`getBalance`, `getTokenAccountsByOwner` Token e Token-2022, `getProgramAccounts` stake, `getSignaturesForAddress`) | ~100 req/10 s por IP; algumas chamadas pesadas podem ser recusadas | 1 req/0,4 s; falha parcial vira "limitação" exibida |
| Ethereum / Arbitrum / Base / Optimism / Polygon / BSC | RPCs públicos (publicnode, arbitrum.io, base.org, optimism.io, bnbchain.org) | `eth_getBalance`, `eth_call balanceOf/decimals` | variam por operador | 1 req/0,3 s; usuário pode trocar o RPC ou adicionar rede EVM personalizada |

Verificação em 12/11/2026 (BRT): todos os endpoints acima responderam; teste `BlockchainTest.liveProviders` passou contra BTC, SOL (incl. SPL) e ETH (incl. USDC ERC-20).

## Tempo real (6.1)

O aviso nunca é tratado como saldo. Ele só dispara a mesma leitura HTTP de sempre.

| Rede | Canal | O que foi conferido |
|---|---|---|
| Solana | `logsSubscribe` em `wss://` no host do RPC (publicnode e `api.mainnet-beta.solana.com`) | o RPC público fala WebSocket; a notificação não traz o saldo novo |
| Bitcoin | `wss://mempool.space/api/v1/ws` com `track-addresses` | protocolo do mempool (mensagem `multi-address-transactions`) |
| Litecoin | `wss://litecoinspace.org/api/v1/ws` | mesmo protocolo (fork do mempool); se cair, fica o polling |
| EVM | `eth_subscribe` `newHeads` no `wss://` do RPC | um bloco novo lê aquela rede no máximo a cada 12 s |
| XRP | `subscribe` de contas em `wss://xrplcluster.com` (e o fallback já configurado) | transação validada acorda a leitura da conta |
| Stellar | SSE `https://horizon.stellar.org/accounts/{id}/payments?cursor=now` | evento `data:` acorda a leitura |
| Preço | `wss://ws.kraken.com/v2` ticker | só no host oficial; par fora de ±12% do último preço guardado é descartado |

Dogecoin, Tron, Cardano, TON, Cosmos, Near, Sui e Aptos não têm, nos provedores já usados, um canal público de conta sem chave. Entram no polling (12–30 s ao vivo, 30/60 s no intervalo escolhido, 5 min em baixo consumo). Blockstream não tem o WebSocket de endereço do mempool; continua como fallback HTTP.

A sparkline de 7 dias da CoinGecko (`sparkline_in_7d`) vem em USD mesmo com `vs_currency=brl`. Máxima e mínima são convertidas pelo câmbio USD/BRL antes de receber o símbolo R$.

## Robustez
- `HttpClient`: um mutex por provedor, intervalo mínimo entre chamadas, backoff exponencial (2 s → 60 s, até 3 tentativas) em 429/5xx/timeout, honra `Retry-After`.
- Contador mensal por provedor visível em Configurações › Dados de mercado.
- Cache: cotações e históricos em Room com horário e origem; histórico diário só busca o que falta; câmbio diário persistido.
- A origem real de cada valor é sempre exibida (ex.: "CoinPaprika (alternativo) · há 2 min").
- Atribuição: "Dados de mercado fornecidos por CoinGecko" nas telas de mercado.
