# CoinPortracker

Portfólio de criptomoedas somente leitura, local-first e sem backend próprio.

## Fonte do projeto

O código-fonte completo está disponível em `CoinPortracker-source.zip`. Para extrair:

```bash
unzip CoinPortracker-source.zip
python3 -m http.server 8080
```

Abra http://localhost:8080.

## Publicação

O workflow em `.github/workflows/pages.yml` extrai o arquivo-fonte e publica a aplicação no GitHub Pages a cada push na branch `main`.

## Privacidade e limites

Endereços públicos, preferências, snapshots e registros manuais ficam no armazenamento local do navegador. O site não possui conta nem backend próprio. Para buscar dados, o navegador consulta diretamente provedores públicos de blockchain e mercado, que recebem o endereço consultado e metadados normais da conexão.

A aplicação é um MVP com suporte a Bitcoin, Solana e redes EVM selecionadas. A cobertura histórica e DeFi é parcial; custos de aquisição precisam ser registrados/importados e não são deduzidos com certeza apenas por uma chave pública.

Licença MIT.
