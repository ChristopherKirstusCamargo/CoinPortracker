# Método do P&L (v4.1)

## Causa do "−R$ 1.643" (3.1)
O resultado do período era `valor final − valor inicial − aportes líquidos`. Dois erros se somavam:
1. **Saldo "fantasma" no início.** Quando falta histórico de entrada de uma posição (rede sem histórico, carteira recém-sincronizada, transferência não capturada), o motor assume que a quantidade atual existia desde o começo do gráfico. Isso coloca ~R$ 1,9 mil no "valor inicial".
2. **Aportes contados de novo.** Os depósitos (entradas on-chain precificadas na hora) somavam R$ 7,67 mil. Parte desse dinheiro é o mesmo que já estava no saldo fantasma.
Resultado: 7.942 − 1.9xx − 7.673 ≈ −1.6 mil, mesmo com o valor quase igual ao pago (+R$ 18,83).
Além disso, o período "Tudo" começava no primeiro movimento (poeira/airdrop), o que gerava linha plana e depois um salto vertical. O PriceBook também interpolava em linha reta entre pontos distantes, o que criava diagonais longas.

## Dois conceitos
- **Principal (investido)**: dinheiro que saiu do bolso. Quando o usuário informa o total pago em "Preço do portfólio", esse valor é a fonte da verdade.
- **Lucro**: valor acima do principal. Abaixo dele: **perda sobre o aporte**.
- **Lucro máximo** = max(valor − principal) na série. **Devolvido do lucro** = lucro máximo − max(lucro atual, 0). Esse valor nunca se mistura com perda do principal.

## Períodos (1D, 1S, 1M, 1A, Tudo)
- Ganho de mercado = Σ quantidade(tᵢ₋₁) × (preço(tᵢ) − preço(tᵢ₋₁)), somado em todas as posições, mais os rendimentos (staking/juros). Aportes, saques e carteiras novas mudam a **quantidade**, não o preço, por isso nunca viram lucro ou prejuízo.
- % = retorno ponderado no tempo, encadeado por intervalo (∏(1 + gᵢ/Vᵢ₋₁) − 1).
- Série do principal = valor no primeiro ponto + aportes − saques acumulados.
- "Tudo" com valor pago informado: o destaque é valor atual − valor pago, que é o mesmo número do topo (Resultado real). O principal é reescalado para terminar no valor pago. A decomposição mostra Valorização (preço), Rendimentos e "Outros (taxas, ajustes)", que é o resíduo.
- Lacunas maiores que 3× o passo mediano da série de preços viram degrau. O gráfico começa no primeiro ponto com valor acima de 1% do máximo.

## Testes
`HistoryEngineTest` (aporte nunca é ganho, nem com saldo fantasma; aporte com preço divergente não cria perda; TWR ignora o tamanho do aporte; lacuna = degrau; início ignora poeira) e `RealPnlTest` (caso 7.924 × 7.942 → +18,83; lucro máximo, devolvido e perda separados).


## Staking (v4.1)
O principal em stake (por exemplo o SOL delegado) entra no valor e no Resultado como qualquer ativo: conta o efeito de preço.
**Só o rendimento** (recompensas) fica de fora do Resultado e aparece à parte como «Rendimentos de staking».
Fórmula: Resultado = valor atual − investido − rendimentos de staking.

## v5 (revisão de dados)
- **Câmbio da época.** O histórico é reconstruído em USD, mas cada ponto do passado agora é convertido pelo câmbio USD/BRL do dia (Frankfurter/BCE, baixado sob demanda), não pelo de hoje. Em nov/2026 o dólar subiu ~15% em 30 dias: converter o passado pelo câmbio de hoje inflava o "Valor no início", criava um "lucro máximo" que nunca existiu em reais e mostrava −15% de mercado (o SOL caiu ~14% em reais e ~24% em dólar). Sem câmbio cobrindo o período, mantém a conversão antiga (`FxRebase`).
- **Caminho do lucro pelo efeito preço.** lucro(t) = Resultado real − (ganho de mercado acumulado de t até agora). Transferências (de fora ou entre carteiras suas) e o período antes de as moedas chegarem não mexem no lucro. Lucro máximo, Devolvido e Maior queda saem desse caminho; Maior queda = recuo de (principal + lucro) do topo ao fundo. O gráfico (Início e Lucro e prejuízo) desenha a mesma linha: `principal + lucro(t)`, terminando no valor atual.
- **Desempenho do período** = efeito preço (sem rendimento de staking, que aparece à parte). Bate com a variação do Resultado real no período.
- **Rateio por ativo.** Com o total pago informado, o custo de cada ativo é o rateio desse total (peso: custo estimado na chegada, ou valor de mercado). Lucro do ativo = valor − custo − rendimento de staking do ativo; a soma por ativo é o Resultado real.
- **APR do stake.** Épocas por ano pela duração medida da época (slots × s/slot), não mais 2,2 dias fixos (≈1,1 dia hoje): 2,2% → ~4,5%.
