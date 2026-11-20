# v5.0 — frente C (UI, layout, tipografia, fluidez)

Branch `v5-C` a partir de `43fbe15`. Sem função nova, sem mudança de cálculo/rede/segurança. Nenhuma animação removida ou reduzida.

## Corrigido
- Lucro e prejuízo: "Perda s/ aporte" → "Perda" (rótulos encolhem antes de cortar); nota "Aportes e carteiras novas…" removida; "Estimativa por ativo" só mostra parcelas ≠ 0, tag "Estimado" quando há custo estimado e "—" limpo sem custo; "Por ativo" com tag Stake (SOL em stake × SOL líquido); "Maior queda" em vermelho; "Última época · ativo desde" quebra no separador (FlowRow) e respeita ocultar saldos; com fonte ≥150% as 3 colunas empilham.
- Mercado: colunas com largura fixa (rank 22, preço 88/80 dp), símbolo e cap com FitText (sem "Cap R…"/"FIGR_H…"), sparkline encolhe <360 dp e some <340 dp; sparkline com faixa mínima de 3% e média por blocos (≤48 pts) — stablecoin ±0,3% fica reta.
- Global: KeyValue com rótulo em 2 linhas (e empilhado com fonte ≥150%); zero sempre cinza e sem seta/sinal (upOf/gainColor/signedMoney arredondam a 2 casas antes de decidir o sinal); sinal de menos "-" único (Atividade usava "−"); RangeChips sem quebra de linha com fonte grande; ChangePill, Stat, ações do Início e barra inferior encolhem em vez de cortar.
- Ocultar saldos: quantidades também mascaradas (Atividade, Ocultos, staking).
- Início: título, linha 24 h, "Resultado real"/"Lucro total" e rótulo do gráfico sem corte; rodapé do gráfico até 3 linhas com reticências.
- Análise: cartão do resultado com toque/escala de cartão (antes ripple retangular sem forma); "sobre R$ … investidos" sem corte.
- Ativos: "1 ativo/N ativos"; colunas de valor limitadas; subtítulos com reticências.
- Contábil: mês por extenso ("Março de 2026"); valores longos sem corte.
- Textos explicativos enxutos: Preço do portfólio, Metas, Carteira manual, Adicionar carteira.
- Acessibilidade: barra inferior com papel Tab/selected (sem leitura dupla do rótulo); ações do Início com papel Botão; chips de período com selected; SwitchRow inteira como interruptor (Role.Switch, um só alvo).

## Fluidez / tempo de resposta
- Pull-to-refresh: a varredura infinita só existe durante a atualização (antes pedia quadro a cada 16 ms em toda tela com Refreshable); o puxão lê a fração só no desenho.
- Saldo ao vivo (1 s) isolado em `LiveTotal`: recompõe só o número, não o topo inteiro.
- SlotText: glifos de símbolos ("R$", ".", ",") medidos uma vez por estilo.
- PnlChart: caminhos e pincéis alocados uma vez por tamanho; remontados só quando a série muda ou durante o morph (arrastar não remonta nada).
- FitText: Layout simples com intrínseca + escala na camada (antes SubcomposeLayout + 2 medições) — mais leve nas listas.
- Contábil: informe montado em Dispatchers.Default (antes, na composição a cada emissão do hub); esqueleto só na primeira abertura.
- Atividade: rótulos de dia calculados uma vez por lista filtrada.
- Reduzir movimento: LineChart e Donut respeitam (PnlChart já respeitava).

## Prévias
`previews/v50/before/` (base v4.1, testes existentes) e `previews/v50/after/` (testes `ScreenshotTest.v50*`, larguras 320/360/393/412, fonte 1.0/1.3/2.0, trilhões, oculto).
`./gradlew testDebugUnitTest --offline -Pshots=true -PshotsDir=previews/v50 --tests '*ScreenshotTest.v50*'`
