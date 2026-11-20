# CoinPortracker 5.0.1 (versionCode 9)

Base: 5.0.0 (202cfd7). Foco: animações configuráveis e as inconsistências vistas no aparelho real.

## Correções (causa → solução)
1. **Carteira duplicada** — duas linhas no banco com o mesmo endereço (o teste de duplicidade só ignorava maiúsculas em EVM; bech32 e restaurações de backup passavam). `WalletDedupe` normaliza o endereço; o Hub ignora repetidas e, na abertura, `dedupeWallets()` arquiva a repetida (mantém o menor id; reversível). `addWallet` usa a mesma normalização.
2. **Alertas** — o interruptor só pedia a permissão depois de ligar e não reagia quando ela estava negada de vez. Agora: Android 13+ pede POST_NOTIFICATIONS; se negada permanentemente, abre as notificações do app e liga ao voltar (ON_RESUME). Estado da permissão relido ao voltar.
3. **Título Dev** — "Avançado (Dev)".
4. **Plural** — `core/Plural`: "1 ligada", "4 ligadas"; idem "token(s) ocultado(s)" e "operação(ões) importada(s)".
5/7. **Análise R$ 0,00 × Início +R$ 3,03; Ativos com lucro zero × Melhores e piores +R$ 1,71** — as recompensas de staking (`stakeViews`) só existiam após o primeiro cálculo de estimativas, segundos depois de abrir. Nesse intervalo o Resultado = valor − pago (+3,03, recompensa contada como lucro); depois = valor − pago − recompensas (0,00), como manda a regra (recompensas fora do P&L). As telas capturadas em momentos diferentes mostravam números diferentes. Agora a última visão de staking é salva e carregada em `warmStart()` antes de qualquer cálculo, então todas as telas usam a mesma base desde o primeiro quadro. Com o total pago informado, o Resultado correto pela regra é **valor − total pago − recompensas**.
6. **Alocação por ativo** — mostrava o P&L do rateio (≈ 0). Agora mostra o valor do item.
8. **Rendimento vazio** — recompensas de stake não são transações. O filtro Rendimento agora lista, por conta de stake, o acumulado e as recompensas por época (registro local que cresce a cada sincronização, guardado em `stake:epochs:v1`).
9. **Banner** — "Usar padrão" em linha própria; Galeria/Arquivos dividem a largura. "Melhorar resolução" não estava desabilitado: o interruptor desligado do tema preto parecia desabilitado. Novo `CpSwitch` com desligado visível em todo o app; legenda explica a pausa no modo de baixo consumo.
10. **Atividade** — nome da carteira em linha própria; endereço e hora numa terceira linha; coluna de valor limitada a 140 dp.
11. **Melhores e piores** — o preço cede espaço (peso) e a variação 24 h nunca é cortada; coluna direita limitada.
12. **BTC 24 h** — Mercado usava a variação da CoinGecko (lista cacheada); Início/Ativos usam a cotação ao vivo (Kraken sobre o preço de 24 h do DefiLlama). O Mercado agora usa a cotação ao vivo do portfólio para as moedas que você tem (lista e detalhe).
13. **Estimar preço médio** — o interruptor funcionava (mesmo problema visual do item 9). Legenda nova explica o efeito com o total pago informado.

## Correção reportada (interruptores)
Alertas, Bloqueio, Proteger tela, Ocultar saldos, Melhorar resolução e Estimar preço médio sempre funcionaram; o desligado parecia desabilitado. `CpSwitch` (desligado = contorno claro + polegar claro; esmaecido só quando desabilitado) em todo o app, e `cpButtonColors` (habilitado branco cheio) em Salvar, Exportar CSV e Compartilhar. Alertas: tocar pede a permissão de notificações se faltar.

## Animações (Ajustes → Animações)
- Números: Rolar dígitos (padrão), Contagem suave, Desfoque, Deslizar, Sem animação — prévia ao vivo no topo e em cada opção.
- Duração 150–1500 ms (padrão 560 ms = v5.0) e curva Suave (padrão), Mola, Linear, Expressivo.
- Transição entre telas: Deslizar (padrão), Fade, Escala, Nenhuma + velocidade.
- Gráficos: desenhar on/off + velocidade. Itens surgindo on/off. Vibração on/off.
- Velocidade geral (0,5×–2×) aplicada a todas as durações/molas do `Motion`.
- Restaurar padrão. Persistido no DataStore (chaves `an*`), valores inválidos caem no padrão.
- Modo de baixo consumo continua desligando tudo (LocalReducedMotion).

Implementação: `SlotText` virou o despachante (todas as telas que já usavam o rolo herdam o estilo escolhido); `NumberText` (contagem/desfoque/deslizar, algarismos tabulares, encolhe para caber); `NumberMorph` (formatação dos valores intermediários a partir do texto final); `Motion.prefs` alimenta transições, molas, gráficos e listas.

## Testes
`V501Test`: contagem (BRL, sinal, %, interpolação, oculto), plural, carteira duplicada, padrões e persistência das animações.

## Riscos
- Nada testado em aparelho; Desfoque usa blur só no Android 12+ (abaixo vira fade).
- Recompensas por época só acumulam a partir desta versão (não há busca retroativa).
- Na primeiríssima abertura (sem cache), o Resultado ainda pode mudar quando as recompensas chegam.
