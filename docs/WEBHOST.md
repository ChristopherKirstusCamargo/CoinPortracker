# Web Host — painel no navegador (6.0)

O Web Host mostra o portfólio do CoinPortracker no navegador de um computador, tablet ou outro celular **da mesma rede Wi-Fi**. Quem serve o painel é o próprio celular, sem nuvem, sem conta, sem túnel e sem serviço pago. O painel é **somente leitura**: todos os controles ficam no app.

## Passo a passo
1. Conecte o celular e o computador à mesma rede Wi-Fi (ou ligue o roteador do celular e conecte o computador a ele).
2. No app: **Ajustes › Web Host › Ligar o painel**. No Android 13+, permita as notificações: uma notificação fixa mostra que o painel está ligado e tem o botão **Parar**.
3. A tela mostra um **QR**, um **código** de 8 caracteres (vale 5 minutos, uso único) e o **endereço**, por exemplo `http://192.168.0.10:8787/`.
   - Computador: abra o endereço no navegador e digite o código.
   - Celular/tablet: leia o QR com a câmera (o código já vai junto).
   - Pelo nome: `http://coinportracker.local:8787/` também funciona na maioria das redes.
4. Com "Aprovar cada novo aparelho" ligado (padrão), toque em **Aprovar** no app ou na notificação.
5. Pronto. O painel atualiza sozinho a cada 15 segundos enquanto a aba está visível.

Para trocar de computador, gere um novo código. Para tirar o acesso de alguém, use **Desconectar**, **Bloquear IP** ou **Encerrar todas as sessões**.

## O que o painel mostra
- Patrimônio, variação 24 h, investido, resultado real, realizado e não realizado.
- Gráfico do patrimônio (1D, 1S, 1M, 1A, Tudo), o mesmo do app.
- Ativos com busca e ordenação (valor, 24 h, resultado, nome).
- Alocação por ativo, rede e carteira.
- Staking de SOL (principal, recompensas, APR).
- Atividade com busca, filtro por tipo e paginação.

Com **Ocultar saldos** ligado no app, o navegador mostra `••••••` em todos os valores e o gráfico só com a forma.

## Ajustes (no app)
| Ajuste | Padrão | Efeito |
|---|---|---|
| Aprovar cada novo aparelho | ligado | além do código, você confirma no celular |
| Sessão dura | 1 dia | 1 h, 8 h, 1 dia ou 7 dias |
| Prender a sessão ao IP | ligado | se o computador mudar de IP, precisa parear de novo |
| Desligar sozinho sem acessos | 1 h | nunca, 15 min, 1 h ou 4 h |
| Nome coinportracker.local | ligado | anúncio mDNS na rede local |
| Permitir Tailscale | desligado | aceita acesso pela sua rede Tailscale |
| Porta | 8787 | entre 1024 e 65535 (com o painel parado) |

## Segurança
- **Só a rede local:** o servidor só atende conexões que chegam por Wi-Fi, roteador do celular ou cabo/USB, vindas de endereços privados. Dados móveis e VPNs comuns são recusados.
- **Pareamento:** código de uso único com validade curta e aprovação no celular. Cinco erros bloqueiam o IP por 15 minutos (o tempo dobra a cada reincidência); 20 erros invalidam o código.
- **Sessões:** token aleatório de 256 bits em cookie `HttpOnly` e `SameSite=Strict`. O celular guarda só o hash SHA-256. As sessões expiram e podem ser revogadas.
- **Navegador:** Content-Security-Policy estrita, sem iframes, sem cache, sem referrer, Host e Origin conferidos e API só de leitura.
- **Sem criptografia na rede local:** a conexão é **HTTP**. Quem controla a mesma rede pode ler o tráfego, então use só redes de confiança. O app não usa certificado próprio, porque o navegador mostraria um alerta de risco, e nunca chama a conexão de "segura".

## Tailscale (opcional)
Para acessar fora de casa, ou para ter tráfego cifrado:
1. Instale o Tailscale (gratuito para uso pessoal) no celular e no computador, com a mesma conta.
2. No app, ligue **Permitir Tailscale** e ligue o painel (ou desligue e ligue de novo).
3. No computador, abra `http://<IP 100.x do celular>:8787/`. O endereço também aparece na tela do Web Host.

O tráfego vai cifrado pelo WireGuard do Tailscale. O CoinPortracker não usa túneis públicos nem serviços pagos.

## Consumo
O servidor usa no máximo 4 threads e atende até 16 conexões. O gráfico fica em cache por 30 segundos. Sem acessos, o painel desliga sozinho no tempo escolhido. Ele nunca liga por conta própria e não volta sozinho se o sistema encerrar o app.

## Limites
- O nome `coinportracker.local` depende de a rede deixar passar multicast. Se não abrir, use o IP.
- Redes de visitantes e algumas redes corporativas isolam os aparelhos entre si. Nelas o painel não abre.
- O Web Host não foi testado em aparelho físico neste ambiente. A validação foi por testes na JVM, incluindo um servidor real em socket local.
