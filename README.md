# CoinPortracker

**Seu portfólio de criptomoedas, só leitura, no seu celular.** Android · Kotlin · Jetpack Compose · versão final 6.0.0

> 🇺🇸 English version below: [English](#english)

## Por que este app existe

O CoinPortracker nasceu para acompanhar todas as carteiras de cripto num só lugar **sem entregar chaves nem dados a terceiros**. Os rastreadores populares pedem conta, enviam o patrimônio para servidores de outra empresa, mostram anúncios ou pedem para "conectar a carteira". Este projeto faz o contrário: é um app **privado e somente leitura (watch-only)**, que lê endereços públicos direto da blockchain, guarda tudo cifrado no próprio aparelho e segue um visual **preto e branco, minimalista**, com verde e vermelho só para alta e queda.

O desenvolvimento começou em 12/11/2026 e evoluiu da 1.0 até esta 6.0.0, a versão final.

## O que ele faz

- **Portfólio automático pela blockchain:** cole um endereço público e o app descobre saldos e tokens em Bitcoin, Solana, Ethereum, Base, Arbitrum, Optimism, Polygon, BNB Chain, Avalanche, Litecoin, Dogecoin, Tron, XRP, Cardano, TON, Stellar, Cosmos, Near, Sui e Aptos.
- **Valor real no tempo:** gráfico reconstruído do histórico on-chain (1D, 1S, 1M, 1A, Tudo), sem contar aportes como lucro.
- **Lucro e prejuízo:** realizado, não realizado e rendimentos separados, com preço médio estimado pela blockchain (editável) e método explicado.
- **Staking de SOL** com recompensas reais por época, em SOL e na sua moeda.
- **Atividade** com entradas, saídas, swaps, rendimentos e movimentos entre suas próprias contas.
- **Operações manuais** (compra, venda, swap, transferência…), custo médio ou FIFO, BRL e USD, importação/exportação CSV/JSON e informe de rendimentos.
- **Alertas de preço, metas e watchlist.**
- **Banner com GIFs e fotos** (até 200 de cada), seletor por toque e melhoria de resolução local (sem nuvem).
- **Compartilhar** um cartão monocromático do portfólio, só com % por padrão.
- **Web Host:** veja o portfólio no navegador do computador, na mesma rede Wi-Fi, só leitura, com pareamento por QR.
- **Backup completo criptografado** (carteiras, histórico, staking, fotos e GIFs, ajustes, regras fiscais) com restauração que não apaga nada.
- **Bloqueio por biometria/PIN**, proteção de tela e ocultar saldos.

Sem conta. Sem anúncios. Sem telemetria. Sem servidor próprio.

## Instalar (passo a passo)

1. No celular Android (8.0 ou mais novo), baixe [`release/CoinPortracker.apk`](release/CoinPortracker.apk) deste repositório.
2. Confira a integridade: o SHA-256 do arquivo tem de ser igual ao de [`release/CoinPortracker.apk.sha256`](release/CoinPortracker.apk.sha256).
   - Computador: `sha256sum CoinPortracker.apk` (Linux), `shasum -a 256 CoinPortracker.apk` (macOS) ou `certutil -hashfile CoinPortracker.apk SHA256` (Windows).
3. Abra o APK. Se o Android pedir, permita **"Instalar apps desconhecidos"** para o navegador ou gerenciador de arquivos.
4. Toque em **Instalar**. Quem já tem uma versão anterior assinada com a mesma chave instala por cima e mantém os dados.
5. Abra o app, toque em **Adicionar carteira** e cole um endereço **público**. Pronto.

## Segurança em uma frase

O app **nunca** pede frase-semente nem chave privada, guarda tudo cifrado (SQLCipher + Android Keystore) e só envia endereços públicos para serviços públicos via HTTPS. Detalhes e limites: [SECURITY.md](SECURITY.md).

## Compilar do código-fonte

Requisitos: JDK 17 e Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew testDebugUnitTest   # testes
./gradlew assembleRelease     # APK em app/build/outputs/apk/release/
```

Para assinar o release, crie `keystore.properties` na raiz com `storeFile`, `storePassword`, `keyAlias` e `keyPassword` (esse arquivo e a keystore nunca vão para o repositório).

## Licença

[Apache-2.0](LICENSE).

---

## English

**CoinPortracker** is a private, **watch-only** crypto portfolio tracker for Android. It exists to follow every wallet in one place without handing keys or data to third parties, with a black-and-white minimalist design. You paste public addresses; the app reads balances, history and staking directly from public blockchain endpoints and keeps everything encrypted on the phone (SQLCipher + Android Keystore). No account, no ads, no telemetry, no backend.

**Install:** download [`release/CoinPortracker.apk`](release/CoinPortracker.apk), check its SHA-256 against [`release/CoinPortracker.apk.sha256`](release/CoinPortracker.apk.sha256), allow "Install unknown apps" if asked, and install. Requires Android 8.0+.

**Highlights:** automatic multi-chain portfolio (Bitcoin, Solana, EVM chains and more), real value-over-time chart, P&L with on-chain cost estimates, SOL staking rewards, activity feed, manual ledger, alerts and goals, GIF/photo banner, share card, a read-only LAN **Web Host** dashboard with QR pairing, and full encrypted backup with non-destructive restore.

**Build:** JDK 17 + Android SDK 35, then `./gradlew assembleRelease`. See [SECURITY.md](SECURITY.md) for the security model and its limits. Licensed under [Apache-2.0](LICENSE).
