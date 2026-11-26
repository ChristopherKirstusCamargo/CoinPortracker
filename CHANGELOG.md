# Changelog

## 8.0.0 — 26/11/2026 (versão final)

**Assinatura de uso único**
- Esta é a versão final publicada, no lugar da 7.0.0. O APK foi assinado com uma chave nova, gerada só para esta build (RSA 4096, `CN=CoinPortracker Final, O=CoinPortracker, C=BR`). A chave e a senha foram destruídas depois da assinatura. Não existe cópia com o autor nem no repositório, então ninguém pode publicar outra atualização com a mesma identidade.
- Qualquer APK com outro certificado não é este original. O SHA-256 do APK é `2f6c409817f8cf02597e809ebeec3358e6376cf1482bcf183222d0fdc44b3905`. O certificado (`CN=CoinPortracker Final, O=CoinPortracker, C=BR`) tem SHA-256 `bd60fd566b085c892816dba0a0fd0125a24f0da5e6133263386f23fc69abd6be` e SHA-1 `741e93d4868cacdf8e40b23c19973b960da07d34`. Os mesmos valores estão na release `v8.0.0`.
- Quem já tem a 7.0.0 não consegue atualizar por cima: a assinatura é outra. Exporte o backup completo, desinstale a 7.0.0, instale a 8.0.0 e restaure o backup.

**Posição total**
- Em Ativos, tocar um ativo abre a posição inteira daquele ativo: saldo livre, stake nativo e o que estiver em outras carteiras, somados na mesma unidade. O detalhe continua em Preço e gráfico.
- Abaixo do total, a divisão por carteira (livre e em stake) e o staking líquido. mSOL, jitoSOL, stETH e os demais só entram na quantidade do ativo-base quando o app já tem uma taxa de conversão verificável. Sem essa taxa, a linha aparece em "Fora do total", com o aviso, e não altera o número.
- Nada é estimado. Saldo desatualizado, leitura parcial ou saldo mantido de uma leitura anterior leva a mesma marca que o restante do app.
- Cartão para compartilhar, 1080×1350 (4:5): logo, quantidade em tipo grande e um nome em fonte de assinatura (Great Vibes, OFL, embutida). O valor em reais ou dólares fica desligado por padrão. O nome fica salvo nos ajustes e entra no backup. O cartão não leva endereço, carteira nem rede. Dá para enviar pelo compartilhamento do Android e salvar na galeria.

## 7.0.0 — 26/11/2026 (versão final)

**Assinatura de uso único**
- Esta é a versão final publicada. O APK foi assinado com uma chave nova, gerada só para esta build (RSA 4096, `CN=CoinPortracker Final`). A chave e a senha foram destruídas depois da assinatura. Não existe cópia com o autor nem no repositório, então ninguém pode publicar outra atualização com a mesma identidade.
- Qualquer APK com outro certificado não é este original. O SHA-256 do arquivo e os fingerprints SHA-256 e SHA-1 do certificado estão na release `v7.0.0`.
- Quem já tem a 6.0.0 não consegue atualizar por cima: a assinatura é outra. Exporte o backup completo, desinstale a 6.0.0, instale a 7.0.0 e restaure o backup.

**Saldos e cotações deixam de dividir o mesmo relógio**
- O ciclo automático não espera mais 5 minutos para reler a blockchain. No modo ao vivo a quantidade confirmada é consultada a cada 12–30 s (conforme carteiras e redes); nos intervalos de 30 s e 60 s, a cada ciclo escolhido; em baixo consumo, a cada 5 min. A cotação continua mais frequente e recalcula o patrimônio sem reler as carteiras.
- Falha total não avança o horário da última sincronização bem-sucedida e não troca saldo válido por zero. Leitura de tokens incompleta preserva os tokens anteriores. O texto da Início distingue "atualizando", "em cache", limite do provedor, sem conexão e atualização parcial.
- Avisos reais, só em primeiro plano: Solana (`logsSubscribe`), Bitcoin (mempool.space), Litecoin (litecoinspace.org), EVM (`newHeads`, no máximo uma leitura da rede a cada 12 s), XRP (`subscribe`) e Stellar (stream da Horizon). O aviso não é saldo — dispara a leitura HTTP. Redes sem canal público nos provedores atuais seguem no polling.
- Kraken cobre mais pares (XRP, LTC, ADA e outros) e, no host oficial, um ticker WebSocket. Cotação fora de faixa é descartada. O DefiLlama, no ciclo rápido, não é chamado mais de uma vez a cada 30 s.
- O modo "Saldo ao vivo" não desenha mais passos intermediários de patrimônio. O número é o valor calculado; a animação dos dígitos continua só visual.

**Mercado**
- Máxima e mínima de 7 dias convertem a sparkline da CoinGecko, que vem em USD mesmo quando o preço está em reais. XRP a R$ 5,46 deixava de mostrar máxima "R$ 0,99".

**Visual**
- Cartões em vidro escuro (camada, borda fina, brilho, sombra curta) sem desfoque em cada cartão.
- Barra inferior virou uma cápsula flutuante, acima da barra de gestos, com indicador da aba. As cinco rotas, o estado e o toque para voltar ao topo continuam os mesmos.

## 6.0.0 — 19/11/2026

**Web Host — o portfólio no navegador do computador**
- Novo em Ajustes › Web Host: liga um painel somente leitura na rede Wi-Fi local, servido pelo próprio celular (sem nuvem, sem túnel, sem serviço pago).
- Pareamento por QR ou código de 8 caracteres (vale 5 min, uso único) e aprovação no celular (na tela ou pela notificação).
- Sessões com token aleatório de 256 bits, guardado só como hash SHA-256, que expiram (1 h, 8 h, 1 dia ou 7 dias). Opcionalmente presas ao IP. Você desconecta, bloqueia ou desbloqueia aparelhos e encerra todas as sessões.
- Proteções: limite de requisições e de tentativas (5 erros bloqueiam o IP por 15 min, em dobro a cada reincidência; 20 erros invalidam o código), Host e Origin conferidos (contra DNS rebinding e CSRF), CSP estrita, sem iframes, sem cache e sem referrer. A API só aceita leitura (GET).
- Só redes locais: Wi-Fi, roteador do celular e cabo/USB. Dados móveis e VPNs comuns são recusados. Tailscale é opcional, desligado por padrão e documentado no "?".
- Serviço em primeiro plano com notificação fixa e botão Parar. Desliga sozinho sem acessos (15 min, 1 h ou 4 h; padrão 1 h) e nunca liga por conta própria.
- Endereço por IP e por nome (coinportracker.local, via mDNS, quando a rede permite). Se o IP muda, o endereço novo aparece na hora.
- Painel AMOLED: patrimônio, 24 h, investido, resultado real, realizado e não realizado, gráfico (1D a Tudo), ativos com busca e ordenação, alocação por ativo, rede e carteira, staking e atividade com filtros. Os números são os mesmos do app, e "Ocultar saldos" vale também no navegador.
- Prévia do painel dentro do app, sem rede.
- A conexão na rede local é HTTP, sem criptografia, e o app diz isso em todo lugar.

**Atividade refeita**
- Causa raiz da Atividade vazia: quando a primeira leitura da Solana voltava sem nenhuma transação, o estado ficava "completo" sem referência e nada mais era lido. Agora a leitura recomeça sozinha.
- Mais de 100 transações novas entre duas leituras deixavam um trecho do meio sem ler. Agora ele é lido na sincronização seguinte.
- Carregando, erro por rede (com o motivo em português e "Tentar de novo"), "sem histórico público gratuito", filtros sem resultado e vazio de verdade agora têm cada um sua tela. A lista enche aos poucos durante a leitura.
- Puxar para atualizar lê o histórico na hora (antes podia esperar 30 min). Movimentos sem data aparecem em "Sem data". Transações de contrato sem detalhes são relidas.

**Backup completo**
- O backup agora inclui tudo: carteiras, operações, histórico on-chain, staking (recompensas e épocas), fotos e GIFs da Rolagem (com a escolhida e as cópias HD), o banner único, todos os ajustes (inclusive os do Web Host), regras fiscais e configurações Dev.
- Ficam de fora, por segurança: a chave de API do CoinGecko e os aparelhos pareados do Web Host.
- Formato novo (cifrado em blocos AES-256-GCM, PBKDF2 com 310.000 iterações), gravado em fluxo, sem carregar mídias na memória. A integridade de cada arquivo é conferida por SHA-256. Backups de versões mais novas são recusados com aviso.
- A restauração não apaga nada: mostra um resumo e só acrescenta o que falta. Carteiras já existentes são reconhecidas, e mídias repetidas não duplicam. Os ajustes anteriores ficam guardados em "Desfazer ajustes da restauração". Backups antigos (.cptbk da 1.0 à 5.1.5) continuam abrindo.

**GIFs e mídia**
- GIFs do banner e da Rolagem tocam com o decodificador animado do sistema (Android 9+), sem o arquivo inteiro na memória e sem decodificar quadro a quadro na thread principal. Isso elimina travadas.
- "Melhorar resolução" não estoura mais a memória com GIFs grandes: há limite de decodificação e o cache libera quadros já usados.
- Prévia de compartilhar com buffer duplo, sem corrida entre desenho e tela e sem alocar a cada quadro.
- Limpeza única dos vídeos antigos da Rolagem, ocultos desde a 5.1.5. Os arquivos, as cópias HD e as miniaturas são removidos, com aviso do espaço liberado; os originais da galeria não são tocados. Restos de recortes de vídeo antigos também são limpos.

**Revisão geral**
- A leitura de arquivos escolhidos (detecção de vídeo) saiu da thread principal no Banner e no Compartilhar, eliminando o risco de ANR com provedores lentos.
- Testes antigos que ainda esperavam vídeo na Rolagem foram corrigidos.
- Novos testes cobrem pareamento, bloqueio, expiração e hash de sessão, roteador (só GET, Host/Origin, CSRF, cabeçalhos), leitor HTTP (limites), servidor real em socket, redes permitidas, mDNS, painel com saldos ocultos, cifra em fluxo (senha errada, adulteração, corte, anexo), backup completo, junção sem apagar, histórico da Solana (vazio, lacuna, nó alternativo) e estados da Atividade.

## 5.1.5 — 13/11/2026
- Rolagem de banner só com GIFs e fotos: a opção de vídeo saiu da tela da Rolagem e do seletor da Início. Vídeos já salvos ficam ocultos, sem afetar nada. O banner único continua aceitando vídeo.

## 5.1.4 — 13/11/2026
- Rolagem de banner: acabou o limite de 2 vídeos. Adicione quantos quiser, ainda com até 1 minuto cada e na maior resolução possível (até 1080p). O uso de armazenamento continua aparecendo na tela.
- Correção: adicionar vídeo travava/fechava o app. A cópia agora roda fora da tela, em blocos (sem carregar o vídeo na memória). Vídeo de até 1 minuto usado inteiro entra como está (sem recodificar, qualidade original). Se a recodificação falhar, o original é guardado, e qualquer erro vira aviso em vez de fechar o app.

## 5.1.3 — 13/11/2026
Seletor do banner muito mais fluido.
- A janela nasce do ponto onde você tocou (escala em mola), com o escurecido e o desfoque subindo juntos. Ao fechar, ela volta pelo mesmo caminho, suave.
- Abas GIF / Foto / Vídeo na grade, com a pílula branca deslizando em mola até a aba escolhida. A grade troca de lado conforme a direção.
- Cascata item a item, linha a linha: cada miniatura entra com mola (escala de 85% a 100%), fade e leve subida.
- Rolando, as linhas que saem e as que chegam pelas bordas somem e aparecem com fade e escala mais suaves, calculados só no desenho (sem recompor a cada quadro).
- Toque com retorno: a miniatura afunda ao encostar e volta com mola. O ✓ da mídia em uso aparece com um "pop".
- Ao escolher, a miniatura voa da grade até o banner (que já trocou por baixo) e a janela se desfaz.
- As miniaturas são pré-carregadas em segundo plano para o seletor já abrir cheio.
- Correção: a Início, a prévia em "Banner, GIF e vídeo" e o HD agora usam a mesma mídia (a escolhida no seletor), e a escolha fica salva ao reabrir o app. Com a Rolagem ativa, Galeria e Arquivos adicionam a mídia à Rolagem e já a deixam em uso. A tela do Banner mostra qual item da Rolagem está em uso, e tocar numa linha da Rolagem também a escolhe.
- "Melhorar resolução" vale também para a Rolagem: a mídia escolhida é melhorada em segundo plano (com o mesmo progresso de antes) e troca sozinha quando fica pronta. Trocar a escolha melhora a nova. Ao desligar, as cópias HD são apagadas e tudo volta ao original (os originais nunca são alterados). No baixo consumo, nada é melhorado.
- A tela cheia da 5.1.2 foi mantida. As Animações (velocidade, transição "Nenhuma", itens surgindo) e o baixo consumo continuam valendo (sem voo nem cascata quando desligados).

## 5.1.2 — 13/11/2026
- Seletor do banner: o escurecido e o desfoque agora cobrem a tela inteira, inclusive a barra de status no topo e a barra de navegação (antes ficava uma faixa clara em cima). Animações iguais.

## 5.1.1 — 13/11/2026
Rolagem de banner: seletor por toque.
- Limites maiores: até 200 GIFs e 200 fotos (vídeos continuam 2, até 1 minuto).
- Na Início, um toque no banner abre uma janela grande no centro, com o fundo desfocado. Primeiro você escolhe o tipo (GIF, Foto ou Vídeo; a pergunta é pulada se só houver um tipo) e depois toca na mídia desejada numa grade com GIFs animados e nitidez original. O banner troca na hora, com transição suave.
- A grade surge linha a linha. Ao rolar, as linhas que saem e as que entram aparecem e somem suavemente.
- O arraste lateral do banner foi substituído pelo toque. O toque duplo no saldo continua ocultando e mostrando o valor.
- Novo cartão em Ajustes com um dedo tocando o banner. A lista de mídias aguenta 200 + 200 sem travar.
- As mídias são cópias guardadas no próprio app, na qualidade original: continuam funcionando mesmo se você apagar da galeria.
- Os ajustes de Animações (velocidade, transição "Nenhuma", itens surgindo) e o modo de baixo consumo valem também para o seletor.

## 5.1.0 — 13/11/2026
Nova Rolagem de banner.
- Ajustes → Rolagem de banner (bloco novo logo abaixo do Banner, em tela própria): até 20 GIFs, 20 fotos e 2 vídeos. No topo, um cartão com uma mão minimalista arrastando para o lado.
- GIFs e fotos: adicionar, remover e mudar a ordem (setas para subir e descer). Vídeos: adicionar e remover, até 1 minuto cada (usa o mesmo recorte de vídeo), na maior resolução possível até 1080p.
- Ao chegar no limite, o app avisa ("Limite de 20 GIFs atingido.") e não deixa adicionar mais.
- Na Início, arraste o banner para o lado para trocar de mídia, com transição suave. A ordem é GIFs, depois fotos, depois vídeos; cada tipo pode sair da sequência.
- Fluidez: só a mídia visível e a vizinha ficam carregadas, com imagens reduzidas; só a mídia parada toca vídeo ou anima GIF, e as outras são liberadas.
- Os textos sobre o banner, "Ocultar textos sobre o banner", os ajustes de aparência do Banner e as Animações continuam valendo. No modo de baixo consumo, tudo fica parado (sem vídeo nem GIF animado).
- O banner que você já usava entra como primeiro item da rolagem. A rolagem começa desligada, então a Início fica igual até você ligá-la ou adicionar mídia.
- As mídias ficam no armazenamento privado do app (o uso aparece na tela) e não vão para o backup; a ordem e as escolhas ficam salvas nos ajustes.

## 5.0.1 — 13/11/2026
Foco em animações e nas correções vistas no celular.
- Nova tela Ajustes → Animações: estilo dos números (Rolar dígitos, Contagem suave, Desfoque, Deslizar, Sem animação) com prévia ao vivo, duração, curva (Suave, Mola, Linear, Expressivo), transição entre telas (Deslizar, Fade, Escala, Nenhuma) e velocidade, desenho dos gráficos e velocidade, itens surgindo, vibração, velocidade geral e Restaurar padrão. O padrão é idêntico à 5.0. Com o modo de baixo consumo ligado, as animações continuam desligadas.
- Números com algarismos de largura fixa em todos os estilos; só o número é redesenhado.
- Resultado igual em Início, Análise e Ativos desde a abertura: as recompensas de staking salvas são carregadas na hora (antes, nos primeiros segundos, elas contavam como lucro).
- Análise: a Alocação mostra o valor de cada item (antes "R$ 0,00"); a variação 24 h em Melhores e piores não é mais cortada.
- Mercado usa a mesma cotação ao vivo do Início e de Ativos para as moedas que você tem (BTC −3,59% em todas as telas).
- Atividade: o filtro Rendimento lista as recompensas de staking (acumulado e por época); o nome da carteira tem linha própria.
- Carteiras: a mesma carteira cadastrada duas vezes aparece uma vez só (a repetida é arquivada, sem apagar dados).
- Alertas: o interruptor pede a permissão de notificações e, se ela foi negada de vez, abre as configurações de notificação do app e liga ao voltar.
- Banner: botões sem quebra de linha; "Melhorar resolução" explica quando está pausado. Interruptores desligados não parecem mais desabilitados.
- "Estimar preço médio pela blockchain" explica o que faz com o total pago informado.
- Tela Dev com título que cabe ("Avançado (Dev)"); plurais corretos ("4 ligadas", "1 operação importada").

## 5.0.0 — 13/11/2026
Versão de revisão: nenhuma função nova. Revisão completa do código, correção de bugs, segurança, cálculos, layout e fontes, sem tirar nenhuma animação.
- P&L e histórico: o passado agora é convertido para reais pelo câmbio de cada dia (não pelo de hoje). Isso corrige o "-15%" de desempenho, o "Lucro máximo" de R$ 1.056 e a "Maior queda" de 16% que não existiam. Transferências entre carteiras não contam como lucro. "Devolvido do lucro" nunca aparece com "+".
- Custo por ativo sai do rateio do total pago e a soma bate com o Resultado real. APR do stake de SOL pela duração real da época (~4,5%, antes 2,2%). Taxa do Bitcoin não é mais descontada duas vezes. Tokens SPL somados por mint.
- Contábil: mover SOL para o stake ou entre carteiras próprias não aparece mais como venda; recompensas de stake como rendimento. O informe é montado fora da thread principal (rolagem sem travar).
- Alertas: alerta em R$ não é mais comparado com preço em US$; alerta não dispara duas vezes; notificação sem valores quando Ocultar saldos/bloqueio estão ligados.
- Segurança: bloqueio sem "piscar" saldos na abertura e sem ser burlado após o Android encerrar o app; Proteger tela ligado desde a abertura; tela Dev aceita só HTTPS público (sem rede local/IP interno); regras fiscais e backups validados (backup com chave privada/seed é recusado); arquivos lidos com limite de tamanho; endereço copiado marcado como sensível.
- Rede: chamadas canceláveis, limite de tamanho de resposta, failover mais robusto, endpoints mortos trocados (Ethereum, NEAR) e reservas novas (Aptos, Cosmos). Top 100 do Mercado sem ativos tokenizados (ex.: "FIGR_HELOC").
- Layout e fontes: textos sem corte em todas as telas, inclusive com fonte grande (130%/200%); colunas do Mercado sem colisão; sparkline das stablecoins lisa (faixa mínima de 3%); zero sempre em cinza e sem sinal; percentuais absurdos limitados (">+99.999%"); nota explicativa do P&L removida; acessibilidade da barra inferior, ações e interruptores.
- Fluidez: sem redesenho contínuo à toa no puxar para atualizar; saldo ao vivo recompõe só o número; gráfico sem realocação ao arrastar; FitText mais leve; banner HD resolvido fora da thread principal.
- Dev: importação de regras fiscais recusada agora mostra o motivo.
- versionCode 8. Mesma chave das versões anteriores: instala por cima.

## 4.1.0 — 13/11/2026
- Alertas locais de preço (acima/abaixo) e de variação do portfólio no dia (±X%), com notificação no aparelho (Android 13+: permissão POST_NOTIFICATIONS).
- Atividade: entradas, saídas, swaps e rendimentos, com filtro por carteira, tipo e ativo; o detalhe abre o explorador.
- P&L do staking: o principal em stake entra no resultado; só o rendimento fica de fora, em «Rendimentos de staking».
- Área Contábil / Informe de rendimentos no lugar de Compartilhar na home: livro de aquisição, Bens e Direitos em 31/12 (grupo 08), alienações mensais com isenção de R$ 35.000, rendimentos de staking, exportação CSV e PDF. Regras versionadas e editáveis em Avançado (Dev). Não substitui um contabilista.
- Metas: valor alvo em Ajustes e anel 0–100% só no Início.
- Modo de baixo consumo: desligado por padrão, só no interruptor (nunca automático). Desliga animações, vídeo/GIF e o upscale, e atualiza com menos frequência.
- Compartilhar portfólio saiu do Início e de Ajustes; fica no bloco Alocação da Análise.
- Análise usa o mesmo Resultado do Início (Investido / Valor / Resultado). Carteiras sem o «R$ 0,00» vazio. Ocultos com estado vazio limpo. Ações da home: Receber, Contábil, Mercado, Carteiras. Gráfico com principal plano no total pago e destaque alinhado ao Resultado. Textos de staking e «Avançado (Dev)» sem corte.
- Ajustes: Segurança e privacidade unificados (sem «Proteger tela» e «Ocultar saldos» repetidos); exportar posição CSV foi para o Contábil; o aviso «Sem conta, anúncios ou rastreamento» foi para Sobre.
- versionCode 7. Mesma chave das versões anteriores: instala por cima.

## 4.0.0 — 13/11/2026
- **Receber** no Início, ao lado de Carteira: escolhe o ativo (só os que você já tem endereço no portfólio), depois o endereço se houver mais de um; QR offline, copiar o endereço cru (“Copiado”) e o menu de compartilhar. URI padrão (bitcoin:, litecoin:, dogecoin:, solana:, ethereum: com chain id EIP-681, web+stellar, ton://, web+cardano; demais redes usam o endereço cru). Rótulo “Rede: …”. Continua só observação: sem chave, sem assinar. Derivação a partir de xpub não entra nesta versão.
- **Vídeo de fundo** no banner do Início e no cartão de compartilhar, no mesmo seletor de GIF/foto. Recorte no aplicativo (linha do tempo, até 60 s), transcode em segundo plano (~720p, sem áudio, teto de tamanho) e reprodução muda em loop, com pausa fora da tela. Sem upscale de IA em vídeo. O cartão pode sair em MP4 com o vídeo atrás; se o encodificador falhar, usa um quadro estático.
- **Configurações avançadas (Dev)** em Ajustes: fontes por rede (RPC/indexador, ordem, ligar/desligar, Testar com latência, failover, restaurar padrão), fontes de preço (ordem, espelhos no formato já suportado, Testar), redes EVM e tokens personalizados com validação, timeouts, atalho para o intervalo de atualização, limpar cache, diagnóstico (fontes com falha + registros com endereços recortados), exportar/importar a configuração em arquivo e atalho para o backup cifrado já existente. Desfazer e restaurar por seção ou tudo, com confirmação no que apaga. Sem plugins, sem execução de código, sem chave privada — só dados. O botão “?” abre um manual offline em pt-BR.
- Tokens personalizados em redes EVM que o aplicativo já conhece entram no portfólio. O saldo de uma cadeia EVM totalmente nova (só a que você cadastrou) ainda não aparece na lista; a rede fica salva, testável e vai no export.
- versionCode 6. Mesma chave das versões anteriores: instala por cima.


## 3.2.0 — 13/11/2026
- P&L refeito: "Investido (do seu bolso)", "Valor atual" e "Resultado" separados; "Perda sobre o aporte" quando abaixo do pago; "Lucro máximo" e "Devolvido do lucro" quando acima. Períodos 1D/1S/1M/1A mostram só o desempenho de mercado (efeito de preço + rendimentos, % ponderado no tempo): aporte ou carteira nova nunca vira lucro/prejuízo. "Tudo" bate com o Resultado real do topo. Causa do −R$ 1.643: docs/PNL_METHOD.md.
- Gráfico: linha do valor + linha tracejada do investido, área verde acima / vermelha abaixo; lacunas viram degrau (sem retas falsas); começa quando as posições começam (poeira/airdrop não estica a linha); troca de período animada (morph).
- Preço do portfólio: só os campos; "Estimar pela blockchain" em "Avançado"; Salvar só com mudança e confirmação "Salvo ✓".
- Melhorar resolução: status (Na fila / Melhorando 45% / Pausado / Pronto / Usando original), tempo decorrido e estimado, antes × depois arrastável, selo "HD" no banner. ~3,4× menos trabalho nas fotos (blocos 256, margem medida) e 2× máx. até a largura da tela; GIF processa só quadros únicos e desiste cedo se a estimativa passar de ~2 min; NNAPI quando validado contra a CPU; pausa com economia de bateria/celular quente; prazos 20 s (foto) e ~2 min (GIF) com reserva Lanczos.
- Redes: Polygon corrigido (RPCs funcionando, POL nativo sem duplicar). Novas: Avalanche C-Chain, Litecoin (com histórico), Dogecoin, Stellar (XLM + trustlines), Tron (TRX + USDT/USDC), XRP Ledger (+ trustlines), Cardano, TON, Cosmos Hub, Near, Sui, Aptos. Detecção automática com checksum; Sui × Aptos pede a rede e confirmação (o formato é igual ao de chave privada).
- Números rolando: param sempre num dígito inteiro (sem meio glifo sobreposto) mesmo com atualização a cada 1 s.
- Ícone do ativo "voa" da lista para o detalhe (elemento compartilhado); respeita "Remover animações".

## 3.1.0 — 13/11/2026
- Saldo: toque duplo oculta/mostra (sem o ícone de olho).
- Mercado: cada moeda abre um detalhe; preço e capitalização preenchidos; nomes longos numa linha só.
- Só blockchain: operações manuais, carteira manual e importação removidas da interface. Novo bloco "Preço do portfólio" (valor total pago, moeda, desde quando) e P&L real (resultado, pior/melhor momento, queda máxima).
- Atualização: um único agendador para preços (1 min ou ao vivo), cotações ao vivo da Kraken com checagem contra DefiLlama; corrigido o saldo congelado ao sair do modo suave.
- Compartilhar: Banner, Galeria (qualquer foto ou GIF, inclusive dos Arquivos), Sólido, Degradê; prévia animada; tela adaptativa (lado a lado em telas largas) com botões fixos; rodapé "Alocação da carteira · sem valores / coinportracker" removido do cartão.
- Melhorar resolução (opcional, desligado): super-resolução por IA no aparelho (Real-ESRGAN compacto via LiteRT, em blocos), em segundo plano; Lanczos-3 só como reserva.
- Números enormes: "R$ 1,23 bi", "R$ 4,57 tri", "qua"; valores encolhem para caber (inclusive com fonte do sistema maior).
- Textos explicativos enxugados; prévia do Compartilhar sem alocar bitmaps a cada quadro; bibliotecas nativas comprimidas no APK.

## 3.0.0 — 13/11/2026
**Lucro e prejuízo de verdade**
- Gráfico Evolução mostra o valor real do portfólio no tempo, reconstruído das transações on-chain (cada ativo só conta a partir de quando entrou). Fim das quedas de −25 % inventadas.
- Resultado do período (1D/1S/1M/1A/Tudo) = variação − aportes líquidos; aportes, retiradas e rendimentos aparecem separados.
- Preço médio estimado automaticamente pelo preço de mercado de cada entrada, com fonte e confiança (Alta/Média/Baixa); continua editável.
- Nova tela Lucro e prejuízo: período, desde a compra (não realizado, realizado, rendimentos), por ativo com origem do preço médio, Staking e DeFi, "Como calculamos".
- Recompensas de staking nativo da Solana lidas da conta de stake (SOL e R$, APR, última época). Corrige "Rendimentos R$ 0,00".
- Banco v3 (migração automática): estado do histórico por carteira/rede e fotografias do valor.

**Interface**
- Ajustes em blocos: Aparência, Moeda, Banner e GIF, Carteiras, Preço médio e custos, Atualização do saldo, Compartilhar, Segurança e backup, Privacidade, Mercado e staking manual, Sobre.
- Correção do botão Início (causa raiz na navegação entre abas) + tocar de novo na aba rola até o topo.
- Números em rolo reescritos: célula de largura fixa, recorte por dígito, sem sobreposição nem overshoot, sentido conforme sobe/desce.
- Cartões surgem com animação discreta ao rolar; leve paralaxe no GIF do topo; fontes um pouco mais encorpadas.
- "Ocultar textos sobre o GIF" (desligado por padrão). Data/valor ao arrastar o gráfico ficam dentro do cartão do gráfico.
- "Saldo ao vivo (suave)" (desligado por padrão): preços reais a cada 15 s e passos de 1 s entre valores reais; pausa fora da tela.
- Compartilhar portfólio: cartão monocromático com alocação em % (rosca ou barras, top 3/5/8, redes), sem valores por padrão; fundo GIF (exporta GIF animado), foto, sólido ou degradê; menu de compartilhar e salvar na galeria.
- Design: linha de ativo com preço inteiro (sem "R$ 400…"), cartões de carteira sem colisão nome/valor, cabeçalho "3 carteiras · 5 redes", "Lucro total" legível, seletor de tema alinhado ("Escuro/Preto/Claro"), tema claro revisado.

**Segurança**
- FileProvider não exportado só para a pasta temporária do cartão; miniatura de "recentes" oculta com bloqueio ou proteção de tela (Android 13+); revisão documentada em docs/SECURITY.md.

## 2.0.0 — 12/11/2026
Watch-only multi-rede automático, novo visual monocromático, banner GIF, P&L por ativo com preço médio informado.

## 1.0.0 — 12/11/2026
Primeira versão: livro de operações, P&L, análises, backup criptografado, bloqueio.
