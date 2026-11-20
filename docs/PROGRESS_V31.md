# CoinPortracker v3.1 — progresso

Início: 13/11/2026 06:40 (horário de Brasília). Base: v3.0.0 (`e107f21`).

| # | Pedido | Situação |
|---|---|---|
| 1 | Saldo: tirar o olho, ocultar/mostrar com toque duplo | feito (código) — validar no emulador |
| 2 | Aba Mercado: ativos não abrem, preço/Cap vazios, nome quebrando | feito (código): tela de detalhe, espaço não separável após R$ |
| 3 | Remover operações manuais; "Preço do portfólio" nos Ajustes | feito (código) |
| 4 | Bug: desligar saldo suave e voltar a 60 s congela o valor | corrigido (laço único com tempo-limite) — validar no emulador |
| 5 | Compartilhar: escolher outro GIF; layout adaptável ao aparelho | em andamento |
| 6 | Cartão compartilhado: remover as duas frases do rodapé | em andamento |
| 7 | Preço atualizar sozinho, sem recarregar | feito (código): Kraken ao vivo + DefiLlama, Mercado a cada 60 s |
| 8 | P&L com o valor total pago (perda/ganho real) | feito (código) + testes |
| 9 | Tirar textos explicativos desnecessários | em andamento |
| 10 | Fluidez | pendente |
| 11 | Upscale de imagem/GIF em segundo plano (opcional, desligado) | em andamento (Lanczos-3 + nitidez) |
| 12 | Números enormes (bilhões/trilhões) sem quebrar o layout | pendente |
| 13 | Atualizações de progresso | contínuo |
| E | Emulador Android (KVM) para testes reais | em andamento |

## Registro
- 06:40 — início da v3.1.
- 06:48 — emulador: KVM liberado (`sudo chmod 666 /dev/kvm`), emulator + imagem `android-34;google_apis;x86_64` instalados, AVD `cpt34` (Pixel 6, 3 GB) iniciando sem janela.
- 06:50 — commit WIP: toque duplo no saldo, detalhe do Mercado, portfólio só on-chain + "Preço do portfólio", resultado real, cotações ao vivo (Kraken) e laço de atualização novo.
- 06:54 — item 12 (números enormes) adicionado.


### 07:20 BRT — marcos (uso semanal 43,6% na última consulta)
- IA real: modelos oficiais Real-ESRGAN general-x4v3 e animevideov3 convertidos para TFLite fp16; LiteRT 1.4.0 CPU/XNNPACK. Tiles 128, margem cobrindo campo receptivo; fotos/modelo geral, GIF/modelo anime. Lanczos só reserva (pouca RAM, falha, orçamento/tempo).
- Worker opcional, switch em Banner e GIF, desligado por padrão; original continua visível até resultado pronto. Nenhuma mídia enviada.
- Modelos: 2.469.172 + 1.266.164 = 3.735.336 bytes (3,56 MiB). LiteRT nativo arm64: 4.320.480 bytes sem compressão; impacto APK FINAL ainda não medido. Universal inclui outros ABIs.
- Compartilhar: Banner/Galeria/Sólido/Degradê em chips, galeria aceita GIF e fotos, seletor de arquivos, prévia animada, tela adaptativa com ações fixas; frases de rodapé removidas.
- Números: notação bi/tri/qua, negativos corrigidos no compact; SlotText escala à largura, FitText nos valores, testes e 29 capturas em previews/v31.
- Testes antes dos últimos ajustes visuais: 139 descobertos, 33 pulados, 106 executados, 0 falhas. Capturas: 29 casos rodados com shots=true.
- Emulador API34 instalado. KVM existe/accel-check diz usável, mas kernel gera kvm_spurious_fault ao criar vCPU. Tentativa com -accel off está rodando por software, adb já online; boot ainda não completo. Não é evidência de teste de app concluído.
- Próximo: validar LiteRT no Android, terminar smoke/migration/jank; corrigir restante de UI/manual; APK versão4/3.1.0 e relatório final.

### 08:05 BRT — fechamento (uso semanal 48,3%)
- APKs 3.1.0 finais (versionCode 4) em dist/, assinados com o mesmo certificado. 139 testes descobertos, 106 executados, 33 pulados, 0 falhas.
- Emulador (só emulação por software): migração Room 1→3 PASSOU. O teste de inferência LiteRT foi interrompido depois de ~15 min sem terminar. Atualização 3.0.0→3.1.0 por cima: Success (versionCode 4) e o app abriu; as capturas saíram vazias/ANR do sistema e foram descartadas; gfxinfo 100% com travamento (artefato da emulação). O emulador foi desligado a pedido do usuário.
- Item 10 (fluidez): parcial. A prévia do Compartilhar reaproveita o bitmap, números e valores se ajustam sem relayout em cascata, o profileinstaller já estava. Nada foi medido em aparelho.
