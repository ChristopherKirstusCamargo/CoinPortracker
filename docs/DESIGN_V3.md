# Design v3

## Princípios
Monocromático (preto, grafite, branco) com verde/vermelho só para variação. Movimento discreto, sem quiques.
Tudo segue "Remover animações" do Android (`LocalReducedMotion`): rolos viram texto, revelações e transições viram cortes.

## Movimento
| Elemento | Especificação |
|---|---|
| Números em rolo (`SlotText`) | Um Canvas por número; cada dígito numa célula de largura fixa (maior largura entre 0–9, `tnum`), recortado na célula; rolagem com mola criticamente amortecida (`dampingRatio = 1`, rigidez 260), sem overshoot; primeira exibição gira com desaceleração enfatizada; sentido: subiu = rola para cima, caiu = para baixo; dígitos novos entram com fade. Só a fase de desenho é invalidada → 120/144 Hz sem recomposição. |
| Revelação ao rolar (`Modifier.reveal`) | Ao entrar na tela: alpha 0→1, translateY 12 dp→0, escala 0,97→1, 380 ms, curva desacelerada. |
| Paralaxe do GIF | GIF do topo desloca 35 % da rolagem. |
| Navegação | Abas: fade-through; telas internas: eixo X compartilhado (10 % + fade). |
| Saldo ao vivo | Passos de 1 s entre dois valores reais; nunca ultrapassa o alvo. |

## Tipografia (Inter / Inter Display)
Corpo Medium (500), títulos SemiBold/Bold, rótulos SemiBold, display Inter Display SemiBold; dígitos tabulares em todo o app.

## Ajustes em blocos
Cartões de 1 linha de altura fixa: ícone 20 dp em quadrado de 40 dp, título (titleSmall), resumo do estado atual em uma linha
(bodySmall, reticências), chevron. Seções: Personalização, Portfólio, Proteção, Geral. Cada bloco abre a própria tela com grupos em cartões.
Rótulos em "Sentence case"; tema: Escuro / Preto / Claro.

## Correções da varredura
- Linha de ativo: esquerda = símbolo + selos / preço + variação 24 h; direita = valor / quantidade. O preço nunca é cortado.
- Carteira: nome com linha própria; valor e lucro na segunda linha; redes como ícones sobrepostos + status na terceira.
- Cabeçalho: "3 carteiras · 5 redes" (singular/plural corretos). "Lucro total +R$ X (+Y %) ›" ou "Ver lucro e prejuízo ›".
- Gráfico: título é o resultado do período; ao arrastar, data e valor aparecem no cabeçalho do próprio cartão.
- Segmentos: indicador desenhado no mesmo Row dos rótulos (largura = total ÷ n) → alinhamento exato.
- Cartão de P&L do Início: linhas rótulo/valor (sem três colunas espremidas).

## Prévias
`previews/v3/` (geradas por `./gradlew testDebugUnitTest -Pshots=true -PshotsDir=previews/v3 --tests '*ScreenshotTest*'`).
