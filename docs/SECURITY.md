# Armazenamento, criptografia e permissões

## Onde ficam os dados
| Dado | Local | Proteção |
|---|---|---|
| Operações, ativos, carteiras, staking, cotações, saldos on-chain | Room em `/data/data/com.coinportracker/databases` | SQLCipher (AES-256) |
| Chave do banco | 256 bits aleatórios (`SecureRandom`), guardada cifrada em `noBackupFilesDir/db.key.enc` | AES-256-GCM com chave **não exportável** do Android Keystore (`DatabaseKeyManager`) |
| Configurações (moeda, tema, chave Demo CoinGecko opcional) | DataStore privado | sandbox do app |
| Banner escolhido | cópia em `filesDir` | sandbox do app (sem permissão persistente de mídia) |

`android:allowBackup="false"` e `data_extraction_rules.xml` excluem tudo de backup em nuvem e transferência entre aparelhos.
`FLAG_SECURE` (opcional, ligado por padrão) bloqueia capturas de tela e oculta a prévia em "recentes".
O R8 remove chamadas `android.util.Log` do release.

## Backup criptografado (`.cptbk`)
Formato: `"CPTBK" | versão | iterações | salt(16) | IV(12) | ciphertext+tag(16)`.
- Derivação: PBKDF2-HMAC-SHA256, 310.000 iterações, salt aleatório por arquivo.
- Cifra: AES-256-GCM (criptografia autenticada); cabeçalho como dado associado — qualquer alteração ou senha errada é detectada e a restauração é cancelada sem tocar nos dados.
- Senha mínima de 8 caracteres, confirmada duas vezes; não é armazenada. Sem a senha o backup é irrecuperável.
- Restauração (até a 5.1.5): decodifica e valida tudo antes; depois substitui as tabelas em **uma única transação** (falha = nada muda).

### Backup completo v2 (6.0)
Formato em fluxo: `"CPTB2" | versão | iterações | salt(16) | prefixo(8)` seguido de blocos de 1 MiB, cada um `tamanho(4) | AES-256-GCM(bloco)`.
- Chave: PBKDF2-HMAC-SHA256 (310.000 iterações, salt aleatório). Nonce = prefixo aleatório ‖ contador; dado associado = cabeçalho ‖ contador ‖ marca de último bloco. Reordenar, cortar ou anexar blocos é detectado.
- Conteúdo: ZIP com `data.json`, `media/<tipo>/<nome>` e `manifest.json` (SHA-256 de cada entrada, versão do app e do formato).
- Leitura: extrai para uma pasta temporária privada, valida nomes (sem caminhos, sem `..`), tamanho por arquivo e total (com o espaço livre do aparelho), todos os hashes e a versão; só então mostra o resumo. Backup de versão mais nova é recusado.
- Aplicação **não destrutiva**: nada existente é apagado; registros entram só se faltam (carteira por rede+endereço; operações por impressão digital; demais por chave natural). Banco em uma transação. Os ajustes anteriores (incluindo Dev e regras fiscais) ficam guardados no banco cifrado para "Desfazer".
- Fora do backup: chave de API do CoinGecko, caminhos de arquivos deste aparelho, aparelhos pareados e sessões do Web Host.
- Exportações CSV/JSON são em texto aberto (avisado na UI).

## Bloqueio do app
Opcional (Configurações › Segurança). Usa `BiometricPrompt` com `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (API 30+) ou
`BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (API 26–29): biometria com **fallback para PIN/padrão/senha do aparelho**.
Só pode ser ativado se o aparelho tiver bloqueio de tela. Rebloqueia após 0/30/60/300 s fora do app.

## Watch-only
Somente endereços públicos. `AddressValidator` verifica checksum (Base58Check/Bech32/Bech32m, Base58 de 32 bytes, hex EVM) e
recusa entradas com cara de segredo (64 hex, WIF, chave Solana de 64 bytes, 12/24 palavras). O app não assina nem transmite transações.

## Web Host (6.0)
Painel somente leitura no navegador, servido pelo próprio celular na rede local. Detalhes e guia: [WEBHOST.md](WEBHOST.md).
| Controle | Implementação |
|---|---|
| Ligar | Só por ação do usuário; serviço em primeiro plano (`specialUse`) com notificação fixa e Parar; `START_NOT_STICKY` (não volta sozinho); desliga após o tempo sem acesso escolhido. |
| Rede | Escuta na porta escolhida (padrão 8787), mas só atende conexões que chegam por Wi-Fi, roteador do celular ou cabo/USB, de origem em faixa privada. Dados móveis e VPNs são recusados; Tailscale (100.64.0.0/10) só se permitido. |
| Pareamento | Código de 8 caracteres (alfabeto de 30 símbolos, ≈39 bits), 5 min, uso único; aprovação no celular (padrão ligado). 5 erros bloqueiam o IP (15 min, dobrando); 20 erros invalidam o código. |
| Sessão | Token de 256 bits em cookie `HttpOnly; SameSite=Strict`; só o SHA-256 é salvo (`noBackupFilesDir`); expira (1 h–7 dias); opcionalmente preso ao IP; revogação, bloqueio de IP e "encerrar todas". |
| Requisições | Host na lista do aparelho (contra DNS rebinding); POST só em pareamento/saída e com Origin igual; API só GET e com `X-Requested-With: cpt`; limite de 120/min por IP (10/min no pareamento); cabeçalho ≤ 16 KB, corpo ≤ 4 KB, 10 s de tempo limite, 16 conexões (4 por IP). |
| Navegador | CSP `default-src 'none'` (script/estilo só do próprio servidor, sem inline), `X-Frame-Options: DENY`, `nosniff`, `no-referrer`, `no-store`, COOP/CORP; texto só por `textContent`; JSON escapa `<`, `>` e `&`. |
| Dados | Formatados no celular com as mesmas funções das telas; "Ocultar saldos" mascara tudo e o gráfico vira só forma (0–1); endereços só recortados; sem ícones externos. |
| Criptografia | **HTTP sem criptografia** na rede local, avisado na tela, na notificação e no navegador. Um certificado próprio geraria alerta do navegador; o app não finge ser HTTPS. Para tráfego cifrado: Tailscale (WireGuard). |
| Prévia | WebView interno em `https://cpt.preview/`, servido pelo mesmo roteador sem rede; sem acesso a arquivos/conteúdo e sem ponte JavaScript. |

## Permissões Android
| Permissão | Motivo |
|---|---|
| `INTERNET` | cotações públicas e saldos de endereços públicos |
| `ACCESS_NETWORK_STATE` | evitar chamadas sem rede / WorkManager |
| `POST_NOTIFICATIONS` | reservada para alertas locais; a v1 não a solicita em tempo de execução (alertas de preço adiados), então nenhuma notificação é exibida no Android 13+ |
| `USE_BIOMETRIC` (+ `USE_FINGERPRINT` mesclada pela biblioteca) | bloqueio do app |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | mescladas pelo WorkManager para atualização periódica opcional |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | 6.0: Web Host em primeiro plano, com notificação, só enquanto ligado |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | 6.0: responder por `coinportracker.local` (mDNS) enquanto o Web Host está ligado |
Não há permissão de armazenamento, câmera, contatos, localização ou leitura de mídia (o seletor de fotos do sistema dispensa permissão).

## Revisão de segurança da v3 (13/11/2026)
| Item | Situação |
|---|---|
| Análise de uso / telemetria | Nenhum SDK de analytics, crash reporting ou anúncios (dependências conferidas em `app/build.gradle.kts`). O pacote `analytics/` é só matemática local. |
| Logs | Nenhuma chamada `Log`/`println`/`printStackTrace` no código; o R8 ainda remove `android.util.Log` do release. Endereços e saldos nunca vão para o logcat. |
| Rede | `network_security_config`: só HTTPS, cleartext proibido, só CAs do sistema (CAs instaladas pelo usuário não são confiáveis). Todos os endpoints são HTTPS públicos (Blockscout, RPC Solana, mempool.space, DefiLlama, CoinGecko, Jupiter, Frankfurter). |
| O que sai do aparelho | Endereços públicos e chaves de tokens para os serviços acima. Nada de saldos, nomes de carteira ou preço médio. Sem servidor próprio. |
| Componentes exportados | Conferido no manifesto final do APK: `MainActivity` (launcher) e três componentes de bibliotecas protegidos por permissão de sistema (`SystemJobService` do WorkManager — `BIND_JOB_SERVICE`; `DiagnosticsReceiver` e `ProfileInstallReceiver` — `DUMP`, só via adb). Fora isso, O novo `FileProvider` do cartão de compartilhar é `exported=false`, expõe apenas `cache/share/` e só por permissão temporária de leitura concedida ao app escolhido no menu de compartilhar. A pasta é limpa a cada nova geração. |
| Banco | SQLCipher + chave no Keystore (inalterado). Versão 3 com migração automática (`AutoMigration 2→3`), novas tabelas `history_state` (cursores de paginação) e `value_snapshots` (valor total ao longo do tempo) — ambas dentro do banco criptografado. Caches de preço histórico e recompensas ficam em `market_cache` (também criptografado). |
| Backup | Android backup e transferência entre aparelhos desligados (`allowBackup=false` + `data_extraction_rules`). Backup do app continua AES-256-GCM + PBKDF2. |
| Bloqueio | `BiometricPrompt` com fallback para credencial do aparelho; o conteúdo do app não é composto enquanto bloqueado; rebloqueio por tempo fora do app; estado "bloqueado" sobrevive à recriação da Activity. Novo: com o bloqueio **ou** a proteção de tela ligados, a miniatura em "recentes" é desativada (`setRecentsScreenshotEnabled(false)`, Android 13+). |
| FLAG_SECURE | Opcional (ligado por padrão). O cartão de compartilhar é desenhado fora da tela (Canvas em memória), então compartilhar funciona com a proteção ligada — capturas de tela continuam bloqueadas. |
| Salvar na galeria | Android 10+: MediaStore sem permissão de armazenamento; Android 8–9: "Salvar como" do sistema (sem permissão). |
| Permissões | Nenhuma permissão nova na v3. |
| Dependências | Mesmas da v2 (AndroidX, Compose, Room, SQLCipher 4.6.1, OkHttp 4.12, kotlinx.serialization, Coil 2.7). Sem bibliotecas de rede/analytics de terceiros adicionais. O codificador de GIF é próprio (sem dependência). |

Limitações: a verificação foi por revisão de código, testes unitários/Robolectric e inspeção do APK (manifesto e assinatura); não houve teste de intrusão em aparelho.
