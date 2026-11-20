# Especificação: Web Host e versão final (planejada como 5.1.6, entregue como 6.0.0)

Requisitos que guiaram a versão final. A implementação está descrita em [WEBHOST.md](WEBHOST.md), [SECURITY.md](SECURITY.md) e no [CHANGELOG](../CHANGELOG.md).

1. **Preservar:** sem reconstrução, sem remover funções, sem migrações destrutivas, sem mudar fórmulas financeiras; reaproveitar a arquitetura e usar o mínimo de dependências.
2. **Web Host:** o celular hospeda a interface web. Ligar/desligar, status, pareamento por QR (seguro, sem acesso aberto), endereço local (coinportracker.local via mDNS, com IP:porta de reserva), copiar endereço, aparelhos conectados e IPs, encerrar sessões, bloquear/desbloquear, ajustes de autenticação. Tela de conexão "CoinPortracker Web" com QR, endereço, status, número de aparelhos e botão de parar.
3. **Interface web:** painel para desktop, preto AMOLED. Visão geral, total, investido, P&L, %, 24 h, ativos, alocação por ativo/rede/carteira, staking, atividade e gráficos, com os mesmos dados e cálculos do app. **Somente leitura:** API só GET, sem endpoints de escrita; filtrar, buscar e ordenar são permitidos.
4. **Segurança:** autenticação obrigatória, pareamento, tokens de sessão que expiram, revogação, aparelhos autorizados, proteção contra força bruta, limite de requisições, validação de entrada, proteção contra CSRF/XSS, administração separada da API de leitura, nunca expor chaves/segredos, nenhum dado de terceiros. Só rede local, sem redirecionamento de porta, sem exposição pública. Tailscale opcional. HTTPS quando viável, sem nunca apresentar HTTP como cifrado. Nenhuma senha ou token guardado em texto puro (só hash).
5. **Estabilidade:** pouca RAM/CPU, nunca bloquear a interface, lidar com reconexão e troca de IP, parada limpa, sem servidores duplicados, sem vazamentos, serviço em primeiro plano com notificação fixa, nunca continuar rodando escondido depois de desligado.
6. **Rede:** mesma rede Wi-Fi primeiro. Dados móveis ou outra rede só por VPN gratuita opcional (Tailscale). Sem túneis públicos, sem serviços pagos.
7. **Prévia** leve da interface web dentro dos ajustes do Web Host, reaproveitando os componentes web.
8. Sem dependências ou APIs pagas, sem botões falsos, sem dados simulados; explicar limites em vez de improvisar.
9. **Testes:** ligar/parar, acesso pelo PC, QR, autenticação, revogação, bloqueio, atualização de dados, gráficos, resoluções, ausência de escrita, integridade dos dados, regressões.
10. **Prioridade:** preservar > host funcionando > interface web > segurança/sessões > estabilidade/testes > visual/prévia.

## Escopo adicional da versão final
- Recomeçar a partir da 5.1.5, revisar de novo a segurança e o compartilhamento, e manter todos os controles do Web Host dentro do app (o site só exibe).
- Refazer a aba Atividade vazia de forma correta e transparente (causa raiz, estados de carregando/erro/vazio, nova leitura).
- Backup/restauração completos: fotos e GIFs do banner (mídias da Rolagem, item escolhido, cópias HD), todos os ajustes (aparência, animações, alertas, metas, baixo consumo, Preço do portfólio, regras fiscais, endpoints/redes/tokens Dev), carteiras, cache de atividade/histórico, staking e ajustes do Web Host (aparelhos pareados e sessões ficam de fora). Arquivo cifrado, restauração não destrutiva, conferência de versão.
- Revisão geral: procurar e corrigir falhas no app inteiro (trabalho na thread principal, risco de ANR, vazamentos de memória, caminhos de crash, travadas em animações).
