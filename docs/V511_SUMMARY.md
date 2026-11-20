# CoinPortracker 5.1.1 (versionCode 11)

Base: 5.1.0 (2291f2f). Pedido: limites maiores e seletor por toque no lugar do arraste.
(As pastas chegaram a ser pedidas e depois foram retiradas do escopo, então não entraram.)

## O que mudou
- **Limites**: 200 GIFs, 200 fotos e 2 vídeos (até 1 min, maior resolução até 1080p). A checagem dentro da gravação atômica do DataStore e a leitura tolerante (`decode`) seguem os novos tetos.
- **Cópia local (verificado)**: GIFs e fotos já eram copiados byte a byte para `filesDir/banner_roll` na importação (qualidade original). Vídeos são transcodificados no worker e movidos para lá. O banner antigo migrado também é copiado. Nenhuma entrada guarda só uma URI da galeria (o `bannerUri` é sempre um caminho de arquivo do app), então nada precisou de migração: apagar o arquivo da galeria não afeta o app.
- **Início** (`ui/components/RollBanner.kt`):
  - O carrossel e o arraste lateral (`BannerCarousel`/`rollSwipe`) foram removidos.
  - O banner mostra a mídia escolhida (`BannerRoll.selected`, chave `bnRollSel`, fora do backup) ou a primeira da sequência.
  - A troca usa fade com leve zoom. A mídia sai do arquivo original: o GIF inteiro, a foto decodificada sem ficar abaixo de 1080×1920 e o vídeo no ExoPlayer.
- **Toque**:
  - Um toque no banner abre o seletor.
  - Sobre o saldo, o toque duplo continua ocultando/mostrando o valor. O toque simples ali abre o seletor depois da janela do toque duplo (`detectTapGestures(onTap, onDoubleTap)`), então os dois funcionam.
  - Botões e linhas clicáveis (moeda, resultado) continuam com prioridade.
- **Seletor** (`ui/components/BannerPicker.kt`):
  - **Janela**: uma janela grande no centro, com margens (até 96% da largura, 80% da altura, cantos de 28 dp).
  - **Fundo**: o fundo fica desfocado com blur entre janelas (Android 12+, quando o aparelho permite), com o raio subindo junto com a abertura. Nos outros casos, fica só escurecido.
  - **Animação da janela**: entra com mola (escala, fade e leve subida) e sai rápido.
  - **Passo 1**: pergunta o tipo (GIF, Foto, Vídeo), mostrando só os tipos com mídia; se houver um só, pula direto para a grade. Os cartões trazem uma prévia e entram em cascata.
  - **Passo 2**: mostra a grade preguiçosa com 3 colunas, chaves estáveis, miniaturas de 384 px em cache LRU por bytes (1/8 da memória, até 64 MB) e decodificação fora da thread principal (até 3 em paralelo).
  - **GIFs na grade**: animam via `AnimatedImageDrawable` no tamanho da célula (Android 9+).
  - **Animação da grade**: as linhas entram em cascata, uma após a outra. Ao rolar, as linhas que saem por cima e as que chegam por baixo fazem fade e escala. Isso é calculado na fase de desenho, sem recompor.
  - **Escolha**: ao tocar num item, o banner troca na hora por trás e a janela fecha animada. A mídia em uso aparece com borda e um ✓.
- **Animações respeitadas**:
  - A velocidade geral vale para o seletor.
  - Transição de tela "Nenhuma", modo de baixo consumo ou animações do sistema desligadas fazem a janela abrir e fechar sem animação.
  - "Itens surgindo" desligado tira a cascata.
  - No baixo consumo, os GIFs ficam parados e não há vídeo.
- **Ajustes → Rolagem de banner**:
  - O novo cartão em preto e branco mostra um dedo tocando o banner, ondas de toque e o seletor surgindo com a grade.
  - A lista agora é preguiçosa (aguenta 200 + 200), com animação ao reordenar.
  - Os interruptores viraram "GIFs/Fotos/Vídeos no seletor".
  - O seletor de mídia do sistema pede no máximo o teto dele (normalmente 100) por vez.

## Testes (metade, como pedido)
V511Test (6 novos), V510Test (atualizado para 200), V501Test, backup, core, portfolio, media, SecretDetection e TabNav: **107 testes, 0 falhas**.

## Riscos / não testado
- Nada foi testado em aparelho (KVM sem emulador).
- O blur entre janelas depende do aparelho; se ele não oferecer, o fundo fica só escurecido.
- A grade foi escrita para 60/120 fps, mas a fluidez precisa ser conferida no celular.
- 200 GIFs de até 16 MB cada podem ocupar bastante espaço; o uso aparece na tela.
