# CoinPortracker 5.1.0 (versionCode 10)

Base: 5.0.1 (6fda1c5). Foco: **Rolagem de banner**.

## O que foi feito
- **Modelo** `settings/BannerRoll.kt`: `BannerRoll` (ligada, itens, tipos na sequência, migrada) e `RollRules` (limites 20 GIFs / 20 fotos / 2 vídeos, 60 s por vídeo, tetos por arquivo 16/24/64 MB, adicionar/remover/mover, sequência GIFs → fotos → vídeos, codificação tolerante no DataStore, mensagens pt-BR).
- **Arquivos** `media/BannerLibrary.kt`: cópias em `filesDir/banner_roll` (privado). Tipo detectado pelo conteúdo. O limite é conferido de novo dentro da gravação atômica do DataStore. Migração única: o banner atual escolhido entra como 1º item do seu tipo; itens sem arquivo e arquivos órfãos são limpos na abertura. Miniaturas em LruCache (48, 192 px).
- **Vídeo**: `VideoDest.ROLL` reaproveita a tela de recorte (60 s). O worker grava no cache e depois move para a biblioteca. A resolução fica até 1080p no lado menor, sem ampliar (`videoTarget`), com bitrate de 8, 5 ou 3 Mb/s e teto de 64 MB. Os vídeos da rolagem entram na fila (APPEND_OR_REPLACE) com arquivo de entrada próprio e não são apagados pelo teto da pasta `video/` do banner único. Os vídeos a caminho contam para o limite de 2.
- **Início** `ui/components/BannerCarousel.kt`: o `HorizontalPager` tem `beyondViewportPageCount = 1`, então só a página visível e a vizinha são compostas. Bitmaps são limitados a 1080×1920. O GIF só anima na página parada, e há um único ExoPlayer, criado só para o vídeo parado (as outras páginas mostram o 1º quadro). O arraste horizontal fica no Hero inteiro (`rollSwipe`), porque o saldo tem toque duplo e cliques que bloqueariam o pager embaixo. O snap usa `Motion.spatial()` (segue a velocidade de Animações) e vira corte com movimento reduzido. Há um fade e uma escala leves entre páginas e pontos discretos, que somem com "Ocultar textos". A opacidade é aplicada uma vez por página e o esmaecer usa o mesmo desenho (`BannerFade`). No baixo consumo tudo fica estático.
- **Ajustes** `ui/screens/BannerRollScreen.kt`: o bloco "Rolagem de banner" fica logo abaixo de "Banner, GIF e vídeo" e abre a rota `bannerRoll`. O cartão com a mão é desenhado em vetor no Canvas, com movimento sutil que fica parado no baixo consumo ou com movimento reduzido. A tela tem interruptores (rolagem e cada tipo), uso de armazenamento, seções GIFs/Fotos (setas de ordem) e Vídeos (só adicionar/remover). Quando a rolagem está ativa, a tela do Banner ganha uma nota de uma linha.
- **Banner único**: continua igual quando a rolagem está desligada ou vazia. Com a rolagem ativa, o banner único não é decodificado e o selo HD fica oculto.
- **Backup**: `bnRoll` (lista) e `bnRollMig` ficam fora da exportação e da importação, como os arquivos. Os interruptores (`bnRollOn/Gif/Photo/Video`) entram.

## Testes
`V510Test` (12): limites, vídeos pendentes, ids e nomes inseguros, sequência, ordem por tipo, remoção, codificação tolerante, ajustes antigos sem as chaves novas, migração em 1º lugar e idempotente, detecção de tipo, alvo de 1080p e resumos.

## Riscos
- Nada foi testado em aparelho.
- "Melhorar resolução" (HD) não se aplica às mídias da rolagem.
- GIFs grandes ficam inteiros na memória enquanto visíveis (no máximo 2 páginas; teto de 16 MB por arquivo).
- O seletor de fotos pode deixar escolher GIF na seção Fotos; ele entra como GIF.
- O vídeo 1080p a 8 Mb/s ocupa até cerca de 60 MB por vídeo.
- Ao trocar para um vídeo, aparece o 1º quadro até o ExoPlayer começar.
