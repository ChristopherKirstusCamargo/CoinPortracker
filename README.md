# CoinPortracker

**Portfólio de criptomoedas privado e somente leitura, no seu Android.** Kotlin · Jetpack Compose · versão final **6.0.0**

> 🇺🇸 [English](#english)

## Por que este app existe

O CoinPortracker nasceu para acompanhar todas as carteiras de cripto num só lugar **sem entregar chaves nem dados a terceiros**. Os rastreadores populares pedem conta, enviam o patrimônio para servidores de outra empresa, mostram anúncios ou pedem para "conectar a carteira". Este projeto faz o contrário:

- **Watch-only:** você informa só endereços públicos. O app nunca pede frase-semente, chave privada ou assinatura, e não consegue mover fundos.
- **Local-first:** tudo fica cifrado no próprio aparelho. Não existe conta, servidor próprio, anúncio ou telemetria.
- **Direto da fonte:** saldos, histórico e staking vêm de endpoints públicos das blockchains, pela internet, em HTTPS.
- **Visual próprio:** preto e branco, minimalista, com verde e vermelho só para alta e queda.

O desenvolvimento começou em 12/11/2026. O app passou por 1.0 (livro contábil e P&L), 2.0, 3.0 (valor real reconstruído da blockchain), 4.0 (receber, vídeo, Dev), 5.0 (animações, Rolagem de banner) e chega aqui à **6.0.0, a versão final**: Web Host, Atividade refeita, backup completo e uma revisão geral.

## Funcionalidades

| Área | O que faz |
|---|---|
| **Início** | Patrimônio total, variação 24 h, resultado real (quanto você pagou × quanto vale), gráfico 1D/1S/1M/1A/Tudo, maiores altas e quedas, banner com GIF ou foto. |
| **Carteiras** | Cole um endereço público e o app descobre saldos e tokens em Bitcoin, Solana, Ethereum, Base, Arbitrum, Optimism, Polygon, BNB Chain, Avalanche, Litecoin, Dogecoin, Tron, XRP, Cardano, TON, Stellar, Cosmos, Near, Sui e Aptos. Carteiras manuais e de corretora também. |
| **Lucro e prejuízo** | Realizado, não realizado e rendimentos separados; preço médio estimado pela blockchain (editável); custo médio ou FIFO; BRL e USD. |
| **Staking** | Contas de stake de SOL lidas da blockchain, com recompensas por época em SOL e na sua moeda, e APR. |
| **Atividade** | Entradas, saídas, swaps, rendimentos e movimentos entre suas contas, com busca, filtros e o estado de leitura de cada rede. |
| **Contábil** | Operações manuais (compra, venda, swap, transferência, staking, airdrop…), histórico de edições, importação/exportação CSV/JSON, informe de rendimentos com regras fiscais editáveis. |
| **Alertas e metas** | Alertas de preço e patrimônio processados no aparelho; metas de valor ou quantidade. |
| **Banner e Rolagem** | Até 200 GIFs e 200 fotos, seletor por toque, melhoria de resolução local (sem nuvem). |
| **Compartilhar** | Cartão monocromático do portfólio, só com % por padrão. |
| **Receber** | QR offline do seu endereço para cada rede do portfólio. |
| **Web Host** | O portfólio no navegador do computador, na mesma rede Wi-Fi, somente leitura, com pareamento por QR. |
| **Backup** | Backup completo cifrado e restauração que não apaga nada. |
| **Proteção** | Banco cifrado, bloqueio por biometria/PIN, proteção de tela, ocultar saldos. |
| **Avançado (Dev)** | Fontes RPC/indexador e de preço com ordem e teste, redes EVM e tokens personalizados, timeouts e diagnóstico. |

## Instalar pelo APK

1. No celular Android (**8.0 ou mais novo**), baixe o APK da [versão mais recente](../../releases/latest) (arquivo `CoinPortracker.apk`) ou direto do repositório: [`release/CoinPortracker.apk`](release/CoinPortracker.apk?raw=true).
2. **Confira a integridade.** O SHA-256 do arquivo precisa ser igual ao de [`release/CoinPortracker.apk.sha256`](release/CoinPortracker.apk.sha256):
   - Linux: `sha256sum CoinPortracker.apk`
   - macOS: `shasum -a 256 CoinPortracker.apk`
   - Windows (PowerShell): `Get-FileHash CoinPortracker.apk -Algorithm SHA256`
   - No próprio celular: qualquer app de hash de arquivos.
3. Abra o APK. Se o Android pedir, permita **"Instalar apps desconhecidos"** para o navegador ou o gerenciador de arquivos.
4. Toque em **Instalar**. Uma versão anterior assinada com a mesma chave é atualizada por cima e mantém os dados.
5. Abra o CoinPortracker.

## Como usar

### Primeiros passos
1. Na Início, toque em **Adicionar carteira** e cole um endereço **público** (por exemplo, `bc1…`, `0x…` ou um endereço Solana). O app reconhece a rede sozinho.
2. Aguarde a primeira leitura: saldos, preços e histórico aparecem em segundos.
3. Opcional: em **Ajustes › Preço do portfólio**, informe quanto você pagou no total. A Início passa a mostrar o **resultado real**.

### Início e gráfico
- Toque nos períodos (1D a Tudo) para trocar o gráfico. O valor é reconstruído do histórico on-chain, então aportes não contam como lucro.
- Toque duas vezes no saldo para ocultar ou mostrar os valores.
- Toque no banner para escolher outro GIF ou foto da Rolagem.

### Atividade
- Abre já lendo o histórico. Puxe para baixo para ler de novo na hora.
- Filtre por tipo (entradas, saídas, swaps, rendimentos, entre contas) ou busque por ativo, rede ou carteira.
- Se uma rede falhar, o motivo aparece em português, com **Tentar de novo**.

### Lucro e prejuízo
- Mostra realizado, não realizado e rendimentos de staking separados, por período e por ativo.
- O preço médio estimado pela blockchain pode ser corrigido no ativo. O método está em [docs/PNL_METHOD.md](docs/PNL_METHOD.md).

### Web Host (painel no navegador)
1. Celular e computador na mesma rede Wi-Fi.
2. **Ajustes › Web Host › Ligar o painel.** Uma notificação fixa mostra que ele está ligado.
3. No computador, abra o endereço mostrado (ex.: `http://192.168.0.10:8787/`) e digite o código de 8 caracteres. Em outro celular ou tablet, leia o QR.
4. Toque em **Aprovar** no app ou na notificação.
5. Para encerrar, toque em **Parar**. O painel também desliga sozinho depois de 1 h sem acessos (ajustável).

O navegador só lê: nada pode ser alterado por ele. A conexão na rede local é **HTTP, sem criptografia**; use em redes de confiança. Para tráfego cifrado ou acesso de fora, há o Tailscale (opcional). Guia completo: [docs/WEBHOST.md](docs/WEBHOST.md).

### Backup e restauração
1. **Ajustes › Segurança e privacidade › Backup completo criptografado.**
2. Crie uma senha (mínimo 8 caracteres) e escolha onde salvar o arquivo `.cptbk`. **Sem a senha, o backup não pode ser recuperado.**
3. Para restaurar: **Restaurar backup**, escolha o arquivo e digite a senha. O app confere tudo, mostra um resumo e só então aplica.

O backup leva carteiras, operações, histórico on-chain, staking, fotos e GIFs da Rolagem (com as cópias HD), banner, todos os ajustes, regras fiscais e configurações Dev. A restauração **não apaga nada**: só acrescenta o que falta, e os ajustes anteriores podem voltar com **Desfazer ajustes da restauração**. Ficam de fora a chave de API do CoinGecko e os aparelhos pareados do Web Host. Backups de versões antigas continuam abrindo.

## Modelo de segurança

| Camada | Proteção |
|---|---|
| Chaves | Nenhuma. O app é watch-only e recusa entradas com cara de segredo (frase de 12/24 palavras, chave privada, WIF). |
| Dados no aparelho | SQLCipher (AES-256). A chave do banco é cifrada por uma chave não exportável do Android Keystore. |
| Nuvem | Backup do Android e transferência entre aparelhos desligados. |
| Rede | Só HTTPS para serviços públicos; texto puro proibido; CAs instaladas pelo usuário não são aceitas. Só endereços públicos saem do aparelho. |
| Backup | AES-256-GCM em blocos com PBKDF2 (310.000 iterações) e SHA-256 por arquivo. A restauração não apaga nada. |
| Web Host | Só rede local, só leitura, pareamento com aprovação, sessões com hash e expiração, limites de tentativas, CSP estrita, proteção contra CSRF e DNS rebinding. |
| Privacidade | Sem telemetria, analytics, anúncios ou conta. |

Detalhes: [SECURITY.md](SECURITY.md) e [docs/SECURITY.md](docs/SECURITY.md).

## Limites

- Os dados vêm de endpoints públicos gratuitos. Quando um deles limita ou cai, a leitura fica parcial e o app avisa qual rede falhou.
- Algumas redes (Dogecoin, Stellar, Tron, XRP, Cardano, TON, Cosmos, Near, Sui, Aptos) mostram saldo, mas não têm histórico público gratuito na Atividade.
- O preço médio estimado é uma estimativa a partir do preço da hora de cada entrada; pode ser corrigido à mão.
- O Web Host usa HTTP na rede local. O nome `coinportracker.local` depende de a rede permitir multicast; o IP sempre funciona.
- O app não substitui contador: o informe de rendimentos segue regras editáveis, sem garantia fiscal.
- A validação foi feita por testes unitários e Robolectric (425 testes), compilação e inspeção do APK. Não houve auditoria externa nem teste em aparelho físico neste ambiente.

## Compilar do código-fonte

Requisitos: **JDK 17** e **Android SDK** (platform 35, build-tools 35).

```bash
git clone <endereço deste repositório>
cd CoinPortracker
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew testDebugUnitTest      # testes
./gradlew assembleRelease        # APK em app/build/outputs/apk/release/
```

Testes opcionais: `-Plive=true` (consultas reais às redes) e `-Pshots=true` (capturas de tela geradas na JVM).

Para assinar o release, crie `keystore.properties` na raiz com `storeFile`, `storePassword`, `keyAlias` e `keyPassword`. Sem ele, o release sai assinado com a chave de debug. Keystores e senhas nunca vão para o repositório (`.gitignore`).

Os arquivos binários do projeto (fontes Inter, ícones, GIF padrão, modelos de super-resolução, listas de tokens e o `gradle-wrapper.jar`) estão no repositório; tamanhos e SHA-256 de cada um ficam em [docs/BINARY_ASSETS.md](docs/BINARY_ASSETS.md).

**Stack:** Kotlin 2.1, Compose BOM 2024.12, Material 3, Room 2.6 + SQLCipher 4.6, DataStore, WorkManager, OkHttp 4.12, kotlinx.serialization, Coil 2.7, Media3 1.6, ZXing 3.5, LiteRT e androidx.biometric. Sem Firebase, sem SDK de analytics. O Web Host usa um servidor HTTP próprio, sem dependências novas.

**Documentação:** [arquitetura](docs/ARCHITECTURE.md) · [APIs e limites](docs/APIS.md) · [segurança](docs/SECURITY.md) · [Web Host](docs/WEBHOST.md) · [método do P&L](docs/PNL_METHOD.md) · [regras fiscais](docs/TAX_RULES.md) · [super-resolução](docs/SUPER_RESOLUTION.md) · [CHANGELOG](CHANGELOG.md)

## Licença

[Apache-2.0](LICENSE).

---

## English

**CoinPortracker** is a private, **watch-only** crypto portfolio tracker for Android. It exists to follow every wallet in one place without handing keys or data to third parties, with a black-and-white minimalist design. You paste public addresses; the app reads balances, history and staking directly from public blockchain endpoints over HTTPS and keeps everything encrypted on the phone (SQLCipher + Android Keystore). No account, no ads, no telemetry, no backend. Version **6.0.0** is the final release.

### Features
- Automatic multi-chain portfolio: Bitcoin, Solana, Ethereum, Base, Arbitrum, Optimism, Polygon, BNB Chain, Avalanche, Litecoin, Dogecoin, Tron, XRP, Cardano, TON, Stellar, Cosmos, Near, Sui and Aptos.
- Real value-over-time chart rebuilt from on-chain history, plus real P&L (what you paid vs. what it is worth).
- Realized, unrealized and staking income kept apart, with on-chain cost estimates you can edit.
- SOL staking rewards per epoch.
- Activity feed with per-network read status, search and filters.
- Manual ledger with CSV/JSON import/export and an editable tax report.
- Price and portfolio alerts, goals, a GIF/photo banner, a share card and an offline receive QR.
- **Web Host:** a read-only dashboard in your computer's browser on the same Wi-Fi, with QR pairing.
- **Full encrypted backup** with non-destructive restore.

### Install
1. On an Android 8.0+ phone, download `CoinPortracker.apk` from the [latest release](../../releases/latest) or straight from the repository: [`release/CoinPortracker.apk`](release/CoinPortracker.apk?raw=true).
2. Verify its SHA-256 against [`release/CoinPortracker.apk.sha256`](release/CoinPortracker.apk.sha256):
   - Linux: `sha256sum`
   - macOS: `shasum -a 256`
   - Windows: `Get-FileHash -Algorithm SHA256`
3. Allow "Install unknown apps" if asked, then install.

### Web Host
1. Go to **Settings › Web Host › Ligar o painel**.
2. Open the shown address (e.g. `http://192.168.0.10:8787/`) on a computer on the same Wi-Fi.
3. Type the 8-character one-time code, then approve on the phone.

The dashboard is read-only and served only to the local network. Pairing is code-based and approved on the phone. Sessions use hashed, expiring tokens and can be revoked, and there is rate limiting and lockout, plus CSP and CSRF/DNS-rebinding defenses. The LAN connection is **plain HTTP (not encrypted)** and is labeled as such. Optional Tailscale gives you an encrypted path. See [docs/WEBHOST.md](docs/WEBHOST.md).

### Backup
**Settings › Security › Backup completo criptografado** creates a password-protected `.cptbk` file (AES-256-GCM, PBKDF2 310k iterations, per-file SHA-256). It covers wallets, history, staking, banner media, all settings, tax rules and developer config. Restore shows a summary first and never deletes existing data. The CoinGecko API key and Web Host paired devices are excluded.

### Limits
- Data comes from free public endpoints and can be partial when they rate-limit; the app reports which network failed.
- Several chains show balances without public activity history.
- LAN Web Host traffic is unencrypted HTTP.
- Validation was done with 425 JVM/Robolectric tests and APK inspection, with no external audit and no physical-device testing in this environment.

### Build
JDK 17 + Android SDK 35, then:

```bash
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

Release signing reads `keystore.properties` (never committed). Licensed under [Apache-2.0](LICENSE).
