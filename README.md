# CoinPortracker

Aplicação estática para acompanhar carteiras de criptomoedas usando apenas endereços públicos. O projeto não possui backend próprio, cadastro de usuário ou telemetria própria.

**Site:** https://christopherkirstuscamargo.github.io/CoinPortracker/

## Executar localmente

Clone o repositório e abra o projeto por um servidor HTTP local:

```bash
git clone https://github.com/ChristopherKirstusCamargo/CoinPortracker.git
cd CoinPortracker
python3 -m http.server 8080
```

Acesse http://localhost:8080.

## Funcionalidades desta versão

- Carteiras somente leitura: Bitcoin, Solana, Ethereum, Base, Arbitrum e Polygon (saldo nativo).
- Saldos e preços consultados diretamente no navegador por APIs públicas.
- Histórico de snapshots local e gráfico de patrimônio.
- Transações indexadas para Bitcoin e Solana, conforme limites dos provedores.
- P/L com custo de aquisição informado manualmente.
- Registro manual de posições/recompensas DeFi e opção para incluí-las no total.
- Backup e restauração JSON usando armazenamento local do navegador.
- Filtros de pesquisa para transações.

## Privacidade

Os endereços, preferências e snapshots ficam no armazenamento local do navegador. Não existe servidor CoinPortracker recebendo ou armazenando esses dados. Entretanto, ao sincronizar, o navegador envia o endereço consultado diretamente ao provedor RPC/explorer e consulta provedores de preço; esses serviços recebem o endereço/IP e os metadados normais da conexão. Para maior privacidade, use provedores próprios ou não sincronize endereços sensíveis.

**Nunca informe seed phrase ou chave privada.** O CoinPortracker não precisa delas.

## Limitações conhecidas

- A consulta de saldo EVM é apenas do ativo nativo; tokens ERC-20, NFTs e posições DeFi não são indexados automaticamente.
- Histórico de transações é limitado pelo indexador; a consulta atual não garante descoberta da primeira transação de cada carteira.
- A área DeFi nesta versão é manual: bloco/slot, recompensa e horário são registros inseridos pelo usuário, não eventos automaticamente verificados on-chain.
- P/L por hora/dia/semana/ano não é ainda uma série histórica ajustada por depósitos, saques e transferências. O gráfico usa snapshots capturados depois que a carteira é adicionada.
- O preço histórico de compra e o custo exato não podem ser deduzidos de maneira universal apenas de uma chave pública. Informe o custo real ou importe seus registros contábeis.
- Taxas de rede ainda não são convertidas historicamente para BRL/USD; por isso não exibimos um total fictício.

## Publicação

O workflow `.github/workflows/pages.yml` publica automaticamente `index.html`, `styles.css` e `app.js` na branch `main` pelo GitHub Pages a cada alteração nesses arquivos.

## Licença

MIT.
