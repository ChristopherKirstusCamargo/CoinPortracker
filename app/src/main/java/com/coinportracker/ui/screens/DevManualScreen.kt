package com.coinportracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun DevManualScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Manual das configurações avançadas", { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp, 8.dp, 20.dp, 40.dp)) {
            Text(
                "Este manual fica no próprio aplicativo, sem internet. Serve para você manter o CoinPortracker por conta própria: trocar fontes, acrescentar uma rede ou um token e se recuperar se algo quebrar. A carteira continua só de observação — o aplicativo nunca pede, guarda ou mostra chave privada ou seed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            H("O que é um RPC")
            P("RPC é o endereço de um servidor que responde perguntas sobre uma blockchain: saldo, blocos, transações. O aplicativo não “é” a rede; ele pergunta a um RPC (e, quando existe, a um indexador) e mostra a resposta. Se o servidor cai ou fica lento, o saldo pode atrasar ou falhar — por isso dá para ter vários e testá-los.")
            P("Um indexador (por exemplo a API de um explorador) é outra fonte, usada sobretudo para histórico e listas de tokens. Não substitui o RPC, complementa.")

            H("Como achar um RPC público")
            P("Procure a documentação oficial da rede ou listas conhecidas de endpoints públicos (Chainlist, documentação do próprio projeto, provedores como Cloudflare, LlamaRPC, PublicNode). O endereço parece com isto:")
            Ex("https://cloudflare-eth.com")
            Ex("https://eth.drpc.org")
            P("Use só http ou https. Não cole chave privada, seed, nem um URL com token secreto que você não queira deixar no aparelho. Endpoints públicos podem ter limite de uso; se um falhar, o aplicativo tenta o próximo da lista (failover).")

            H("Fontes por rede")
            P("Em Configurações avançadas → Fontes por rede, escolha a cadeia (Ethereum, Bitcoin, Solana…). Cada linha é um endpoint, na ordem em que o aplicativo tenta. O primeiro ligado que responder é o que vale.")
            P("Passo a passo para acrescentar um RPC:")
            P("1. Abra a rede desejada.")
            P("2. Cole o URL em “Novo endpoint (https://…)”.")
            P("3. Marque RPC ou Indexador.")
            P("4. Toque em Adicionar. Se o endereço for inválido, a alteração não é salva e a mensagem explica o motivo.")
            P("Use Subir e Descer para definir a ordem, o interruptor para ligar ou desligar sem apagar, Testar para ver latência e se a resposta veio, e Remover para tirar da lista. “Restaurar padrão desta rede” volta só aquela cadeia ao original.")

            H("Como testar")
            P("O botão Testar dispara um pedido curto àquele endereço e mostra se funcionou e quanto tempo levou, em milissegundos. “OK · 180 ms” é um bom sinal. Se falhar, leia a mensagem, confira o URL (https, sem espaço no fim) e tente outro endpoint. Testar não muda a configuração; só informa.")

            H("Fontes de preço")
            P("Aqui está a ordem das cotações: Kraken, DefiLlama, CoinGecko, CoinPaprika, Frankfurter (câmbio). O aplicativo percorre as fontes ligadas nessa ordem. Você pode desligar uma, reordenar, ou acrescentar um endpoint no formato já suportado (por exemplo outro espelho da API da DefiLlama). Também há Testar e “Restaurar fontes de preço”.")

            H("Como adicionar uma rede EVM")
            P("Se você usa uma cadeia que o aplicativo ainda não traz pronta, abra Redes e tokens e preencha:")
            P("• Nome — como você quer ver a rede, por exemplo “Linea”.")
            P("• Chain id — o número oficial (Linea é 59144). Não pode repetir uma rede que já existe no aplicativo.")
            P("• RPC — o https:// do servidor.")
            P("• API do explorador — opcional, se houver uma API tipo Blockscout/Etherscan.")
            P("• Símbolo e decimais da moeda nativa (ETH costuma ter 18).")
            P("Toque em Adicionar rede. Se algo estiver errado, nada é gravado. Você pode desligar ou remover depois. Nesta versão o saldo de uma rede totalmente nova ainda não entra na lista do portfólio (fica para uma próxima); a rede já fica salva, testável e vai no export. Tokens em redes que o aplicativo já conhece (Ethereum, Base, Polygon…) aparecem no portfólio normalmente.")

            H("Como adicionar um token")
            P("Ainda em Redes e tokens, escolha a rede, cole o contrato (0x seguido de 40 caracteres hexadecimais), o símbolo e os decimais. Exemplo:")
            Ex("Contrato  0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
            Ex("Símbolo   USDC")
            Ex("Decimais  6")
            P("Confira o contrato no explorador oficial antes de salvar. Um endereço errado não rouba nada (o aplicativo é só observação), mas mostra saldo zero ou um token que não é o que você queria.")

            H("Rede, intervalos e cache")
            P("Os timeouts (conexão, leitura, chamada) são o tempo máximo de espera, em segundos, entre 1 e 120. Valores muito baixos fazem fontes boas falharem; muito altos deixam a atualização lenta quando um servidor não responde. O padrão é 10 / 20 / 30.")
            P("Limpar cache apaga cotações e listas guardadas, não as carteiras nem os saldos observados. Na próxima atualização tudo é buscado de novo. Uma confirmação aparece antes de apagar. O intervalo de atualização do saldo continua em Ajustes → Atualização do saldo (e há um atalho nesta mesma seção).")

            H("Diagnóstico e registros")
            P("A seção Diagnóstico lista fontes que falharam nesta sessão e um diário curto em memória (não vai para a internet). Por padrão os endereços aparecem recortados (0x1234…abcd). Se precisar ver o endereço completo para depurar, desligue “Ocultar endereços” — e volte a ligar depois. Atualizar relê o diário; Limpar esvazia só essa lista.")

            H("Exportar, importar e backup")
            P("Exportar configuração gera um arquivo JSON com fontes, redes personalizadas, tokens e timeouts — sem carteiras. Sirva para copiar o arranjo para outro aparelho ou guardar um ponto conhecido. Importar substitui a configuração atual; o aplicativo valida o arquivo e recusa se houver URL inválido ou algo que pareça chave privada. Confirme antes de importar.")
            P("O backup completo e cifrado (carteiras e dados) continua em Segurança e backup, com a mesma senha de sempre. Use os dois se quiser tanto o arranjo técnico quanto os dados.")

            H("Se algo quebrar")
            P("1. Desfazer última alteração — volta um passo, no topo das configurações avançadas.")
            P("2. Em cada seção, “Restaurar padrão…” devolve só aquela parte.")
            P("3. “Restaurar tudo ao padrão” no início zera fontes, personalizados e timeouts. Não apaga carteiras.")
            P("4. Se você exportou a configuração antes, importe esse arquivo.")
            P("5. Se os saldos parecerem velhos depois de um teste ruim, limpe o cache e atualize de novo.")
            P("Nada disso pede reinstalação do APK. Se ainda assim uma rede pública inteira estiver instável, troque o RPC e teste até um responder.")

            H("O que este aplicativo não faz")
            P("Não executa código de terceiros, não aceita plugins, não assina transação e não guarda seed. Cole apenas dados: URL, chain id, contrato, símbolo. Qualquer texto que pareça chave privada, seed ou xprv é recusado na validação.")
        }
    }
}

@Composable
private fun H(t: String) {
    Spacer(Modifier.height(22.dp))
    Text(t, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun P(t: String) {
    Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Ex(t: String) {
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
}
