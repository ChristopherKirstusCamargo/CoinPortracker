# CoinPortracker v4.1.0 — progresso

Início: 13/11/2026 ~13:10 America/Sao_Paulo. Fechamento: 13/11/2026 13:35 America/Sao_Paulo.
Base: v4.0.0 (`2cf0d38`), versionCode 6 → 7 / 4.1.0.

## Pacotes
1. Alertas de preço e variação do portfólio (local, WorkManager + notificações)
2. Histórico legível em Atividade (entradas, saídas, swaps, recompensas; filtros)
3. P&L do staking: o principal em stake entra no resultado; só o rendimento fica de fora, em «Rendimentos de staking»
4. Área Contábil / Informe de rendimentos (IRPF), regras versionadas e editáveis
5. Metas: alvo em Ajustes + anel 0–100% só no Início
6. Modo de baixo consumo: desligado por padrão; **nunca** ativa sozinho (nem com economia de bateria, nem com bateria baixa). Só no interruptor. O worker de upscale da v3.2 continua pausando em economia de bateria / aparelho quente — é comportamento separado.
7. Fluidez e revisão de layout
8. Compartilhar sai do Início e de Ajustes; fica no bloco Alocação da Análise
9. Correções das capturas da v4.0 (P&L da Análise, carteiras, ocultos, ações da home, gráfico, textos cortados)
10. Auditoria de redundância: cada ajuste/ação em um único lugar

## Uso
Semana em 64% no início desta entrega; checagem no fechamento no relatório final. Em 95% gravar contexto; em 98% parar.

## Auditoria de redundância (achados e decisão)

Varredura das telas Início, Análise, Atividade, Ativos, Ajustes (todas as categorias), Sobre, Dev, Compartilhar, Receber, Lucro e prejuízo.

| Achado | Onde estava | Decisão |
| --- | --- | --- |
| «Proteger tela» repetido | Segurança e backup **e** Privacidade | Telas unificadas em **Segurança e privacidade** (`settings/seguranca`). Bloqueio, Proteger tela, Ocultar saldos, Backup e Restaurar ficam só ali. A rota antiga `settings/privacidade` redireciona. A categoria Privacidade sai da lista. |
| «Ocultar saldos» também em Aparência | Aparência + Segurança | Removido de Aparência. Fica só em Segurança e privacidade (atalho: toque duplo no saldo do Início). |
| Exportar posição (CSV) | Ajustes | Removido dos Ajustes. CSV e PDF do informe ficam na área **Contábil**. |
| «Sem conta, anúncios ou rastreamento.» | Cartão próprio nos Ajustes | Cartão removido da lista. O aviso foi para **Sobre**. |
| Compartilhar | Ações do Início e item em Ajustes | Saiu dos dois. Ícone de compartilhar só no cabeçalho do bloco **Alocação** na Análise. A categoria SHARE permanece no enum só para redirecionar rotas antigas, fora da lista. |
| «Carteira» e «Carteiras» na home | Duas ações distintas | Mescladas em **Carteiras**. Conjunto novo: Receber, Contábil, Mercado, Carteiras. |
| P&L da Análise ≠ Início | Análise tinha decomposição/nota própria | Análise usa o mesmo `RealPnl` (Investido / Valor / Resultado). Nota de rodapé antiga removida. |
| Aba Carteiras com «R$ 0,00» sem sentido | Ativos → Carteiras | P&L da carteira só aparece quando há custo/resultado; some o zero vazio. |
| Ocultos com frase explicativa + lista vazia | Ativos → Ocultos | Estado vazio limpo: «Nenhum token oculto.» |
| «Configurações avançada…» cortado | Ajustes | Título «Avançado (Dev)», uma linha com reticências. |
| Linhas de staking cortadas | Análise / Ativos | Rótulo curto «SOL em staking»; linhas com `maxLines` + ellipsis. |

Nenhum outro ajuste ou ação ficou em dois lugares. Mercado continua só em Ajustes → Mercado (e no atalho da home). Banner, preço do portfólio, atualização e metas têm um único bloco cada.

## Status por item

1. **Alertas** — feito. `AlertKind.PORTFOLIO_DAY`, avaliação com cotações ao vivo do hub (a tabela de quotes pode não ter preços on-chain), WorkManager já existente, `POST_NOTIFICATIONS` no manifesto. Tela em Ajustes → Alertas.
2. **Atividade** — feito. Classificador (`ActivityKind`: entrada, saída, swap, rendimento, entre contas, outro), filtros por carteira, tipo e ativo; detalhe abre o explorador. Usa o histórico on-chain já gravado (Solana, Bitcoin e demais).
3. **P&L do staking** — feito. Staked SOL entra no valor e no Resultado. Só `stakingYieldCur > 0` sai do Resultado e aparece como «Rendimentos de staking». Sem fatiar DeFi do P&L total. Principal do gráfico fica plano no total pago quando só esse total foi informado (`flatPrincipal()`).
4. **Contábil** — feito. Substitui Compartilhar na home. Livro de aquisição, holdings em 31/12 (grupo 08: 01 BTC, 02 altcoins, 03 stablecoins, 10 NFTs, 99 outros), alienações mensais com isenção de R$ 35.000 e dicas de GCAP, staking como rendimentos, CSV + PDF. Regras versionadas (`TaxRules` 2026.1) editáveis em Avançado (Dev) com importar/exportar, «regras vigentes em \<data\>» e aviso de que não substitui um contabilista. Custos rateados pelo total pago (estimado) vêm marcados. Se o histórico on-chain não cobre a posição atual, a diferença entra como lote estimado em 01/01 do ano corrente (anos anteriores ficam só com o histórico conhecido). Fontes em `docs/TAX_RULES.md`.
5. **Metas** — feito. Alvo em Ajustes → Metas. Anel 0–100% só no canto inferior do Início; toque abre o detalhe.
6. **Baixo consumo** — feito conforme o recorte final. `AppSettings.lowPower = false`. Só o interruptor em Atualização do saldo («Só liga se você ativar.»). Não há listener de economia de bateria nem de bateria baixa que mude essa flag. Com o modo ligado: menos animações, vídeo/GIF parados, sem novo upscale (HD já pronto permanece), atualização mais espaçada e sem cotação ao vivo. O worker de upscale da v3.2 segue pausando em economia de bateria / thermal — independente deste modo.
7. **Fluidez / layout** — feito no recorte aprovado: botões da home com largura total e reticências, títulos sem corte, gráfico alinhado ao Resultado, abas e estados vazios limpos. Extras de fluidez fora dessa lista ficam para depois.
8. **Compartilhar** — feito. Só no cabeçalho de Alocação na Análise.
9. **Correções v4.0** — feitas (ver tabela da auditoria e o item 3 para o gráfico).
10. **Redundância** — feita (tabela acima).

## Testes
`./gradlew testDebugUnitTest --offline`: 278 testes, 205 executados, 73 ignorados (capturas e rede ao vivo, o padrão das versões anteriores), 0 falhas, 0 erros.
Novos: `TaxEngineTest`, `ActivityClassifierTest`, extensão de `AlertEvaluatorTest` e `RealPnlTest`.
Correção no caminho: o rateio do total investido nos lotes de compra arredondava o último lote com `MathContext` e a soma ficava um tick abaixo do total; o resto do último lote passa a ser `invested - acc` exato.
Prévias JVM: `previews/v41/` (`-Pshots=true -PshotsDir=previews/v41`, testes `*ScreenshotTest.v41*`).
Sem emulador (KVM indisponível; pedido para não emular). Sem teste no telefone nesta entrega.

## Fontes das regras fiscais (resumo)
Ver `docs/TAX_RULES.md`. Embarcadas como `TaxRules` versão 2026.1, editáveis no Dev:
- Perguntas e Respostas IRPF 2026 (PDF da Receita);
- Manual Bens Financeiros (gov.br), atualizado em 26/05/2026 — grupo 08, códigos 01/02/03/10/99, limite de R$ 5.000 por espécie para declaração;
- Nota da Fazenda atualizada em 26/10/2026;
- Lei 8.981/1995 art. 21 e Lei 9.250/1995 art. 22 (isenção mensal de R$ 35.000 nas alienações, alíquotas 15 / 17,5 / 20 / 22,5 %, DARF 4600 — 1897 fica editável);
- Ato Declaratório Executivo COTEC nº 67/2025: MP 1.303/2025 caducou em 08/10/2025.
Método de custo: média. Total pago rateado nos lotes de compra (estimado, quando o preço médio não veio de nota). Replay fatiado em 31/12.

## Adiado / fora do recorte
- Teste em aparelho e em emulador.
- Derivação a partir de xpub.
- Saldos de cadeias EVM 100% novas (só as que o usuário cadastrou).
- Custos fiscais na maior parte estimados (preço atual + rateio do total pago), não de notas fiscais.
- Staking Cosmos e redes além do que já entra no histórico on-chain.
- Extras de fluidez além do layout já revisado.

## APKs
Assinados com o mesmo `release.jks` (certificado SHA-256 `567740b090706df5666afbc63c02b1ed484d9bf152d3338c35c2ec8197b3d85c`).
`dist/CoinPortracker-4.1.0.apk` — 28.065.104 B (26,76 MiB), SHA-256 `2052c2f800f116d44a01574e4d85ab28dbc755355e9b65c3e75352b1298e3151`.
`dist/CoinPortracker-4.1.0-arm64.apk` — 14.397.341 B (13,73 MiB), SHA-256 `fc7b7e8f21a38683457c25f331caa504367931aef0755bdd9e84e31956da15fb`.
`apksigner verify`: v2 válido nos dois. Sem upload.
