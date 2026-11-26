# Funcionalidades da v4 (novas)
- **Receber**: QR offline, copiar endereço cru e compartilhar, só com ativos/redes que já estão no portfólio; URI padrão por rede.
- **Vídeo de fundo** (banner e cartão de compartilhar), recorte de até 60 s, transcode ~720p sem áudio, reprodução muda em loop; cartão em MP4 quando o encodificador consegue.
- **Configurações avançadas (Dev)**: fontes RPC/indexador e de preço com ordem, Testar e failover; redes EVM e tokens personalizados (validados); timeouts, cache, diagnóstico e registros com endereços recortados; exportar/importar a configuração; desfazer e restaurar padrão; manual offline em pt-BR no “?”. Sem chave privada, sem plugins, continua watch-only.
- Tokens personalizados em redes EVM já conhecidas aparecem no portfólio. Saldo de uma cadeia EVM cadastrada pelo usuário ainda não entra na lista (fica para depois).

# Funcionalidades da v3 (novas)
- **Gráfico com o valor real do portfólio** reconstruído do histórico on-chain + operações manuais; resultado do período sem contar aportes; "Histórico desde…" quando parcial.
- **Preço médio estimado pela blockchain** (preço da hora de cada entrada), com fonte e confiança, editável; realizados de swaps on-chain.
- **Tela Lucro e prejuízo**: período (1D–Tudo), não realizado / realizado / rendimentos, por ativo, Staking e DeFi, método explicado.
- **Recompensas de stake da Solana** em SOL e R$, APR da última época, ativação; staking líquido, rebase, lending e LP identificados.
- **Ajustes em blocos** com telas por categoria; **Início corrigido** (e re-toque rola ao topo).
- **Números em rolo sem cortes**, revelação discreta dos cartões, paralaxe leve, fontes mais encorpadas, respeito a "Remover animações".
- **Ocultar textos sobre o GIF** (desligado por padrão); data do gráfico dentro do cartão.
- **Compartilhar portfólio**: cartão monocromático só com % (valores opcionais), fundo GIF animado/foto/sólido/degradê, compartilhar e salvar na galeria.
- **Saldo ao vivo** opcional (6.1): cotações dos principais ativos em segundos (Kraken) e saldos on-chain em cerca de 12–30 s, ou na hora quando a rede avisa. O número na tela é o patrimônio calculado, sem passos inventados. Pausa em segundo plano.

Limitações da v3: ver [PNL_METHOD.md](PNL_METHOD.md#5-limitações-conhecidas). Exportação em MP4 não foi feita (o GIF animado cobre o caso). Não houve teste em aparelho físico neste ambiente: validação por compilação, testes unitários/Robolectric e capturas geradas na JVM.

# Funcionalidades da v1

**Portfólio e contabilidade**
- Ativos via busca (CoinGecko/CoinPaprika) ou manuais (preço manual, para tokens sem cotação).
- Carteiras manuais, exchanges e watch-only; filtro do portfólio por carteira.
- Operações: compra, venda, depósito/saque fiduciário, transferência entre carteiras, swap, staking, airdrop, rendimento, taxa, ajuste manual.
- Custo médio ponderado ou FIFO (troca recalcula todo o histórico), BigDecimal em todos os valores.
- P&L completo: patrimônio, aporte líquido, custo das posições, realizado e não realizado (lucro e prejuízo separados), rendimentos, taxas, resultado total e %; cada número abre a explicação com fórmula e operações de origem.
- BRL e USD com câmbio por operação ou taxa diária do BCE; visão na outra moeda mostra o efeito cambial.
- Histórico auditável: revisões de cada edição, exclusão lógica com restauração.
- Importação CSV/JSON com pré-visualização, edição de linha, validação e detecção de duplicatas; exportação CSV/JSON; relatório CSV do portfólio.

**Painel e análises**
- Saldo com transição animada, atualização automática em primeiro plano (30/60 s ou desligada) e em segundo plano opcional (WorkManager).
- Banner do portfólio com GIF animado ou imagem (Coil + ImageDecoder/GifDecoder), altura ajustável.
- Gráfico patrimonial × aportes (7D–Tudo, zoom/arraste/toque), distribuição por ativo/categoria/rede/carteira, principais ativos, últimas operações, status dos dados; cartões do painel configuráveis.
- Análises: resultado por ativo, concentração (HHI, maior ativo, top 5, stablecoins, exchanges), risco (volatilidade, drawdown máx., Sharpe, Sortino, VaR 95%, TWR, XIRR, correlação/beta com BTC, cenários de queda), análise crítica por regras.
- Mercado: top 100 com sparkline, favoritos (watchlist).

**Watch-only on-chain**
- Bitcoin (legacy, P2SH, SegWit, Taproot), Solana (SOL, tokens SPL e Token-2022, contas de stake), EVM: Ethereum, Arbitrum, Base, Optimism, Polygon, BSC + rede EVM personalizada; tokens ERC-20 por contrato (decimais lidos on-chain).
- Saldo observado × livro, reconciliação por ajuste manual auditável, vínculo de mint/contrato a um ativo.

**Staking**
- Posições com ativo, quantidade inicial/atual, validador, provedor, APY informado, bloqueio, prazo de saída, status.
- Recompensas recebidas (operações de staking no livro) × rendimento estimado pelo APY, com razão recebido/estimado — estimativa nunca entra no resultado.

**Segurança e privacidade**
- Banco SQLCipher com chave no Keystore; backup `.cptbk` AES-256-GCM + PBKDF2; restauração atômica.
- Bloqueio por biometria com fallback para PIN/padrão/senha; proteção de tela; ocultar valores.
- Temas AMOLED, azul-marinho e claro; pull-to-refresh com indicador próprio; shimmer, sem spinners; alta taxa de atualização.

# Limitações conhecidas
- Não testado em aparelho/emulador físico neste ambiente (sem aceleração KVM disponível): a validação foi por compilação, lint vital do release, 48 testes unitários e teste real de rede dos provedores. Recomenda-se um teste manual de interface antes de distribuir.
- Histórico de preço gratuito limitado a 365 dias (CoinGecko público / CoinPaprika); antes disso o gráfico mostra lacunas, sem interpolar.
- Watch-only lê **saldos**; não importa automaticamente o histórico on-chain como operações (custo de aquisição não existe on-chain). Use reconciliação ou importação CSV. Solana mostra só as 25 assinaturas recentes; EVM não lista histórico (RPC público não indexa).
- Tokens ERC-20 precisam ser adicionados por contrato (RPC público não lista tokens). Tokens SPL desconhecidos aparecem pelo mint até serem vinculados.
- RPCs públicos podem limitar ou recusar chamadas pesadas (ex.: `getProgramAccounts` de stake Solana); a falha aparece como limitação, sem apagar dados anteriores.
- Câmbio: Frankfurter publica só em dias úteis (usa o último disponível).
- Adiados para versões futuras (código parcial existe, sem interface): relatório PDF, Monte Carlo, alertas de preço/watchlist com notificação, extras da seção 19.
- Estatísticas exigem observações mínimas e mostram "dados insuficientes" abaixo disso; são estimativas sobre o passado, não previsões.
- O APK de distribuição é assinado com uma chave local gerada na compilação; atualizações futuras precisam da mesma chave (`release.jks`).
