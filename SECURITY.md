# Segurança / Security

## Modelo de segurança (pt-BR)

**O CoinPortracker é somente leitura (watch-only).** O app nunca pede, guarda ou usa frase-semente, chave privada ou assinatura. Ele não envia, compra, vende nem movimenta criptomoedas. Você informa só endereços públicos; entradas com cara de segredo (frase de 12/24 palavras, chave privada hex, WIF, chave Solana de 64 bytes) são recusadas na hora.

| Área | Como é protegida |
|---|---|
| Dados no aparelho | Banco Room cifrado com SQLCipher (AES-256). A chave de 256 bits é aleatória e fica cifrada por uma chave não exportável do Android Keystore. |
| Backup do Android / nuvem | Desligado (`allowBackup=false` + `data_extraction_rules`). Nada vai para a nuvem do Google. |
| Backup do app (`.cptbk`) | AES-256-GCM com chave derivada da sua senha (PBKDF2-HMAC-SHA256, 310.000 iterações, sal aleatório). Senha mínima de 8 caracteres, nunca armazenada. Qualquer alteração no arquivo ou senha errada é detectada e a restauração é cancelada. A restauração não apaga nada do que já está no app. |
| Rede (saída) | Só HTTPS para serviços públicos (RPCs, indexadores, cotações). Tráfego em texto puro proibido; CAs instaladas pelo usuário não são confiáveis. Só endereços públicos saem do aparelho: nunca saldos, nomes de carteira ou preço médio. |
| Telemetria | Nenhuma. Sem analytics, sem crash reporting, sem anúncios, sem conta, sem servidor próprio. |
| Bloqueio | Biometria ou PIN/padrão do aparelho, com rebloqueio por tempo; proteção de tela (FLAG_SECURE) ligada por padrão. |
| Web Host (6.0) | Painel no navegador **só na rede local**, **só leitura**, ligado manualmente, com notificação fixa enquanto roda. Pareamento por QR/código de uso único, aprovação no celular, sessões com token aleatório (só o hash SHA-256 fica salvo), expiração, revogação e bloqueio de aparelhos, limite de tentativas e de requisições, proteção contra CSRF, XSS e DNS rebinding (CSP estrita, Host/Origin conferidos). A conexão é **HTTP sem criptografia** na rede local, e o app diz isso claramente; para acesso cifrado fora de casa, use Tailscale (WireGuard), opcional e documentado. |

### Limites honestos
- A validação foi por revisão de código, testes unitários/Robolectric e inspeção do APK. Não houve auditoria externa nem teste de intrusão.
- Em um aparelho com root ou comprometido, nenhum app consegue garantir sigilo.
- O Web Host em HTTP pode ser lido por quem controla a mesma rede Wi-Fi. Use só em redes de sua confiança.

## Como reportar uma vulnerabilidade

Abra uma [issue](https://github.com/ChristopherKirstusCamargo/CoinPortracker/issues) **sem detalhes exploráveis** pedindo um contato privado, ou use "Report a vulnerability" (GitHub Security Advisories) neste repositório. Não publique endereços, saldos ou arquivos de backup reais.

---

## Security model (English)

CoinPortracker is **watch-only**: it never asks for, stores or uses seed phrases, private keys or signatures, and it cannot move funds. Data stays on the device in an SQLCipher (AES-256) database whose key is wrapped by a non-exportable Android Keystore key. Cloud backup is disabled. App backups are AES-256-GCM with a PBKDF2-derived key (310,000 iterations) and restore never deletes existing data. Outbound traffic is HTTPS-only to public endpoints, and only public addresses leave the device. There is no telemetry, no analytics, no account and no backend.

The optional **Web Host** serves a read-only dashboard on the local network only, started by hand and shown in a persistent notification. It uses QR/one-time-code pairing, on-phone approval, hashed expiring session tokens, device revocation and blocking, rate limiting and brute-force lockout, plus CSRF/XSS/DNS-rebinding defenses. The LAN connection is **plain HTTP (not encrypted)** and is labeled as such; use Tailscale (optional) for encrypted remote access.

Report vulnerabilities through GitHub Security Advisories ("Report a vulnerability") or an issue without exploitable details.
