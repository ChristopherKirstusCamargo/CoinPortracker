# CoinPortracker v3.2 — progresso

### 08:25 BRT — início (uso semanal ~49%)
- Base: v3.1.0 (commit 4965a69). Meta: versionCode 5 / 3.2.0, mesma chave, sem emulador.
- PT1 P&L (causa raiz + redesenho), PT2 Preço do portfólio, PT3 upscale rápido + progresso, PT4 novas redes, PT5 fluidez/animações, PT6 build/docs.
- Próximo: motor de P&L (efeito preço por período, principal, sem interpolação em lacunas).

## 13/11/2026 08:46 BRT — 55% (uso 54%)
Feito:
- PT1 P&L: causa raiz (saldo "fantasma" sem histórico + aportes precificados na hora → −R$ 1.643); novo motor por efeito de preço + TWR, série do principal, degraus em lacunas, início quando as posições começam. RealPnl com Lucro máximo / Devolvido do lucro / Perda sobre o aporte. PnlChart (tracejado do investido, área verde/vermelha, morph entre períodos). Testes com 7.924 × 7.942 → +18,83.
- PT2 Preço do portfólio: só entrada; "Avançado" recolhido; Salvar só com mudança + "Salvo ✓".
- PT5 (parcial): rolos de dígitos assentam em inteiro (tween 560 ms < tick 1 s; desenho arredondado em repouso) + testes.
- PT4 Redes: Polygon (RPCs que funcionam, POL nativo sem duplicar), Avalanche, Litecoin (com histórico), Dogecoin, Stellar (+trustlines), Tron (+USDT/USDC), XRP (+trustlines), Cardano, TON, Cosmos, Near, Sui, Aptos. Detecção com checksum; Sui×Aptos pede escolha + confirmação (formato igual ao de chave privada). Log ao vivo em docs/chains_live_v32.txt.
Próximo: PT3 upscale (progresso, velocidade), PT5 resto, PT6 build/prévias/docs.

## 13/11/2026 09:03 BRT — 100% (uso ~58%)
Feito: PT1–PT6. APKs 3.2.0 (versionCode 5) assinados com a mesma chave e verificados pelo apksigner, prévias em previews/v32 e documentação.
Ressalvas: nada testado em aparelho real; NNAPI e os tempos de IA no celular são estimativas; transições compartilhadas só no ícone do ativo (lista → detalhe); perfil base escrito à mão (sem Macrobenchmark).
