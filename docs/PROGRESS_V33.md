# CoinPortracker v3.3 — progresso

### 10:10 BRT — início (uso semanal 58,7%)
- Base: v3.2.0 (commit ad7eb2c, versionCode 5). Meta: versionCode 6 / 3.3.0, mesma chave, sem emulador, sem upload.
- PT1 Receber: botão ao lado de Carteira; passo 1 só ativos/redes com endereço no portfólio; passo 2 escolhe o endereço; QR offline + copiar (endereço cru, "Copiado") + compartilhar; URI padrão (bitcoin:, solana:, EIP-681…). Sem chave privada, sem assinar. xpub não é suportado (já era assim) — fica registrado.
- PT2 Vídeo de fundo (banner e cartão de compartilhar): no máximo 1 min, recorte no app, transcode 720p sem áudio em segundo plano (Media3 Transformer + WorkManager, com progresso), ExoPlayer mudo e em loop, pausa fora da tela/tela apagada. Sem upscale de IA em vídeo. Exportar o cartão em MP4 se der; senão, quadro estático e ressalva.
- PT3 Testes, prévias em previews/v33/, docs, APKs assinados.
- Próximo: motor do QR/URI e a lógica do Receber (testes JVM), depois a tela.


## 13/11/2026 10:25 BRT — PT1 e PT2 no código (uso ~59%)
Feito:
- Receber: PaymentUri (BIP-21, Solana Pay, EIP-681 com chain id, SEP-7, TON, CIP-13; demais = endereço cru), lista só com endereços do portfólio (XLM some/volta sozinho), vários endereços no passo 2, QR ZXing offline, copiar endereço cru com "Copiado", compartilhar, rótulo "Rede: …". xpub não suportado (já era).
- Vídeo: regras de corte (teto 60 s), detecção, transcode Media3 Transformer ~720p sem áudio em WorkManager com progresso e teto de tamanho, ExoPlayer mudo/loop com pausa fora da tela, recorte no app, picker do banner e do cartão aceitam vídeo, export MP4 do cartão com overlay (fallback: quadro estático). Upscale de IA não roda em vídeo.
- Testes JVM de URI, lista Receber e recorte: passando. Compilação OK.
Próximo: prévias v33, suíte completa, APKs 3.3.0, docs.
