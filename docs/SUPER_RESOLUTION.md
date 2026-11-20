# Super-resolução local (v3.1)

Opção `Melhorar resolução` em Banner e GIF, desligada por padrão. Os originais ficam no armazenamento privado. WorkManager produz uma cópia HD sem bloquear a interface, com bateria/armazenamento não baixos. Sem API, upload ou download de modelo em runtime.

## Modelos

Pesos oficiais de https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0 (BSD-3-Clause, licença em assets/sr/LICENSE.txt):
- reais imagens: realesr-general-x4v3, SRVGG compacto, 32 convoluções de corpo, PReLU, pixel shuffle 4× + residual nearest.
- GIF: realesr-animevideov3, corpo 16 convoluções, menor custo. Não é rede temporal; GIF pode apresentar variação temporal.

Convertidos dos .pth para TFLite float16, entradas/saídas float32 NHWC RGB 0..1, tile fixo 128. `tools/sr_convert.py` preserva ordem PyTorch do pixel shuffle via permutação dos canais. Conversão gerada com TensorFlow CPU. `tools/sr_verify.py` é script exploratório local (diretório de modelos via variável ML_DIR; não faz parte do build), compara com convolução NumPy e crop reduzido. A comparação inicial no crop de screenshot melhorou PSNR com anime (28,37 vs Lanczos 27,58 dB); modelo geral não ganhou nesse crop (26,04). Isso NÃO promete reconstrução fiel de detalhes perdidos nem valida qualidade em todos os GIFs/fotos. Ainda falta teste de inferência instrumentado no aparelho.

## Execução

LiteRT 1.4.0, Interpreter CPU/XNNPACK, 2 threads, entrada por blocos com margem 36 para fotos/20 GIFs (campo receptivo completo), descarta bordas antes de unir. Saída 4× reduzida com Lanczos antialias ao tamanho necessário; não só resize/sharpen. Transparência de fotos mantida (alfa nearest); GIF é composto sobre preto.

Aparelhos low-RAM / <3GB RAM / heap <192MB, imagens >700k pixels ou GIFs >6M pixels somados, falha de modelo/tempo >8min: Lanczos-3 + nitidez. GIF é tudo-ML ou tudo-Lanczos para não alternar quadros. Limites: GIF 960px, 60M pixels de saída somados; modelo opcional não melhora fontes que já têm resolução suficiente.

## Tamanho

Modelos embutidos, sem compressão de asset (mmap): 3.735.336 bytes = 3,56MiB.
- anime: 1.266.164 bytes, SHA256 1fc20f702718090a57bf5d2e3e03c5b05ac9150f295d65d9e169fd76babfe920
- geral: 2.469.172 bytes, SHA256 4a4a1cc06099850aecc28a22c1b5690e1bdafa2c5cda8081e30feb17064dcab3

LiteRT arm64 JNI: 4.320.480 bytes; zip comprimido no AAR 1.748.567 bytes. Universal também traz x86/x86_64/armv7. Medir impacto final do APK no release; os tamanhos do AAR não são delta final de APK.


## v3.2: velocidade e progresso
- Modelos reexportados com bloco fixo 256×256 (`SR_TILE=256 tools/sr_convert.py`). Os pesos e o tamanho são os mesmos: geral 2.469.172 B, anime 1.266.164 B.
- Margem por bloco medida contra a imagem inteira (`tools/sr_bench.py`):
  - geral, pad 24: diferença máxima 0,22/255;
  - anime, pad 16: 0,16/255;
  - antes eram 36/20.
  - Pixels processados: fotos (256/208)² = 1,51× sobre a área útil, contra 5,2× antes; GIF 1,31×, contra 2,1×.
- Amplia no máximo 2×, nunca além da largura da tela. Com fator final < 2, a entrada é reduzida para metade do tamanho final antes do modelo 4×.
- GIF: só os quadros únicos passam pelo modelo, com no máximo 150. O 1º quadro é cronometrado: se a estimativa passar de 110 s, o GIF todo vai para o Lanczos. Prazo duro de ~2 min.
- Foto: prazo de 20 s; depois disso, Lanczos.
- NNAPI no Android 10+: o 1º bloco é comparado com a CPU (diferença ≤ 6/255) e precisa ser mais rápido. Uma marca em `noBackupFilesDir` desativa o NNAPI se o driver travar. Sem isso, a execução fica na CPU com XNNPACK e 2 threads. GPU delegate não incluído, porque o artefato litert-gpu não está no build offline.
- Pausa com economia de bateria ou `THERMAL_STATUS_SEVERE`, com nova tentativa em 2 min. Bateria baixa já é restrição do WorkManager. A thread roda em prioridade de segundo plano.

### Tempos medidos (PC de desenvolvimento: Xeon x86, TFLite CPU, 2 threads) e estimativa honesta para o celular
| Caso | Medido em PC de desenvolvimento (CPU x86) | Estimativa em celular intermediário (2–3× mais lento, não medido) |
|---|---|---|
| Bloco 256, modelo geral | 659 ms | 1,3–2,0 s |
| Bloco 256, modelo anime | 346 ms | 0,7–1,0 s |
| Foto 540×300 → 1080×600 (6 blocos) | ≈ 4,0 s | ≈ 8–12 s (meta < 20 s) |
| GIF 240×135, 40 quadros únicos → 480×270 (1 bloco/quadro) | ≈ 14 s | ≈ 28–42 s |
| GIF 480×270, 60 quadros únicos → 960 (6 blocos/quadro) | ≈ 125 s | > 2 min, então a estimativa do 1º quadro desvia para Lanczos |
| Lanczos foto 540×300 → 1080×600 (JVM) | 90 ms | — |
| Lanczos GIF 240×135×40 → 480×270 (JVM, inclui codificar o GIF) | 1,4 s | — |

Não houve teste em aparelho real (o emulador não roda aqui). Os números do celular são estimativas.
