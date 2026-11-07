# CoinPortracker

**Portfólio cripto local-first, somente leitura e sem servidor próprio.** O CoinPortracker consulta dados públicos diretamente do navegador, mantém os registros no armazenamento local e pode ser instalado como PWA.

**Aplicação:** https://christopherkirstuscamargo.github.io/CoinPortracker/  
**Repositório:** https://github.com/ChristopherKirstusCamargo/CoinPortracker

## Começar

Abra o site, adicione um ou mais endereços públicos e use **Sincronizar todas**. Nenhuma seed phrase ou chave privada é solicitada.

No celular, a navegação fica na barra inferior. Para instalar:
- **Android:** Chrome/Samsung Internet → menu do navegador → Instalar app / Adicionar à tela inicial (ou use o botão de instalação quando aparecer).
- **iPhone:** Safari → Compartilhar → Adicionar à Tela de Início.

## Funcionalidades da versão 2

- Dashboard minimalista e responsivo, com patrimônio, saúde, cobertura de preços, performance e P/L por período.
- Carteiras somente leitura em Bitcoin, Ethereum, Base, Arbitrum, Polygon, Solana, XRP Ledger e TRON.
- Saldos nativos das redes listadas; tokens e transferências de tokens quando o indexador da rede disponibiliza os dados.
- Histórico de transações indexadas, filtros por rede/tipo/data e pesquisa por data, hash, endereço e valor.
- Histórico de páginas anteriores em redes que fornecem cursor de paginação.
- Fonte de preço principal DefiLlama, sem chave; opção de chave Demo CoinGecko, guardada apenas neste navegador.
- Custo agregado: informe o custo total das posições sem exigir custo por transação. Registre taxas acumuladas e marque se elas já estão incluídas no custo.
- Distribuição proporcional do custo agregado entre posições, identificada como **estimativa sintética**, não como custo histórico real de cada ativo.
- Registro de aportes e retiradas externas para descontar movimentos de capital do P/L por período. Transferências entre endereços cadastrados são identificadas como internas quando os indexadores fornecem origem e destino.
- Aba DeFi integrada ao patrimônio, com registro manual de posições, recompensas, bloco/slot, data, evidência e passivos. O usuário pode incluir/excluir DeFi do total e evitar dupla contagem de saldos já incluídos.
- Taxas de rede observadas nas transações indexadas, mostradas em unidade nativa e com conversão pela cotação atual quando disponível.
- IndexedDB, migração dos dados da versão anterior, backup JSON com versão e checksum SHA-256, restauração e solicitação de armazenamento persistente.
- PWA com service worker que armazena somente a interface. A sincronização da blockchain e os preços exigem internet.

## Como funciona o custo agregado

O campo **Custo de aquisição total** aceita o montante conhecido para o conjunto de carteiras. O sistema calcula:

- **Custo sem taxas:** custo total informado, descontando taxas quando o usuário declara que elas já estavam incluídas.
- **Custo com taxas:** custo sem taxas somado às taxas acumuladas, ou o total declarado quando já inclui taxas.
- **P/L total estimado:** patrimônio atual menos custo com taxas e movimentos externos posteriores à data-base registrados no CoinPortracker.

Para mostrar uma parcela estimada por ativo, o total sem taxas é distribuído proporcionalmente ao valor de mercado atual dos ativos precificados. Isso **não** é custo médio histórico nem apuração fiscal. O preço atual não permite inferir quanto foi pago no momento de cada compra. Para manter a conta coerente, informe o custo total conhecido e registre aportes/retiradas externos feitos depois da data-base; não registre transferências entre suas próprias carteiras como aportes.

## Cobertura e limitações

| Rede / recurso | Cobertura atual |
|---|---|
| Bitcoin | Saldo UTXO, transações recentes pagináveis e taxa da transação observada |
| Ethereum / Base / Arbitrum / Polygon | Saldo nativo, tokens e transferências conforme Blockscout |
| Solana | Saldo SOL, contas do programa SPL clássico e assinaturas recentes via RPC público |
| XRP Ledger | Saldo XRP e histórico account_tx via servidor público |
| TRON | Saldo TRX e transações via gateway público; disponibilidade/limites podem variar |
| DeFi | Cadastro manual; descoberta automática universal ainda não está disponível |
| NFTs, LPs, vaults, lending, restaking e bridges | Não há cobertura universal automática nesta versão |
| Histórico desde a gênese | Parcial: limitado à paginação, retenção e disponibilidade de cada provedor |

A transação mais antiga encontrada é apenas a mais antiga **consultada/indexada até aquele momento**. A primeira página de um explorer não prova que é a primeira atividade da carteira. Use “Carregar transações antigas” quando disponível; ainda assim, indexadores podem ter limites ou histórico incompleto.

O P/L de 1h/24h/7d/1a exige snapshots locais suficientemente próximos do início do período. O CoinPortracker não reconstrói automaticamente o saldo histórico de todas as redes e contratos. O gráfico usa snapshots locais e desconta fluxos externos registrados; não é um cálculo fiscal ou TWR certificado.

As taxas automáticas são aquelas observadas nas transações indexadas. A exibição em moeda fiduciária usa cotação atual quando disponível, não cotação histórica do momento da taxa. Taxas de bridge, protocolo e corretora são categorias separadas e ainda não são consolidadas automaticamente. Em Bitcoin, a taxa é da transação; quando há múltiplos participantes/inputs, atribuí-la integralmente a uma única carteira pode superestimar o custo individual.

## Privacidade e backup

- Não há cadastro, servidor de portfólio, analytics, publicidade ou coleta própria.
- Endereços, custos, fluxos, DeFi, snapshots e preferências são armazenados no IndexedDB deste navegador.
- Ao sincronizar, o provedor público recebe o endereço consultado e metadados da conexão, incluindo IP. Logo, “sem servidor próprio” não significa anonimato perante RPCs/explorers.
- O botão **Backup** está no cabeçalho e nas configurações. O arquivo JSON é versionado e verificado por SHA-256 quando a API Web Crypto está disponível.
- Em navegadores compatíveis, é possível vincular um arquivo local para atualização automática enquanto a permissão do navegador estiver ativa. A permissão pode precisar ser renovada. Em navegadores móveis sem File System Access, use o download manual.
- Solicite armazenamento persistente em Configurações, quando o navegador oferecer. Nenhum navegador pode garantir que o usuário nunca limpará os dados; mantenha cópias de backup fora do aparelho.
- O service worker mantém apenas os arquivos estáticos da aplicação em cache; não armazena as respostas das APIs de blockchain ou preços.

## Rodar localmente

```bash
git clone https://github.com/ChristopherKirstusCamargo/CoinPortracker.git
cd CoinPortracker
python3 -m http.server 8080
```

Acesse http://localhost:8080. HTTPS ou localhost é necessário para recursos como service worker e instalação PWA.

## Publicação

O GitHub Actions publica os arquivos estáticos na branch `main` pelo GitHub Pages após cada alteração relevante. O workflow executa `node --check` nos scripts e valida o manifesto antes do deploy.

## Licença

MIT.
