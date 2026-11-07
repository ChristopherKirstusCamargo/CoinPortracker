(function(){
 const A=window.CPTApp;
 function bind(){
  A.$$('[data-page]').forEach(b=>b.addEventListener('click',()=>A.navigate(b.dataset.page)));
  A.$$('[data-go]').forEach(b=>b.addEventListener('click',()=>A.navigate(b.dataset.go)));
  A.$('#walletForm').addEventListener('submit',A.addWallet);
  A.$('#syncAll').addEventListener('click',()=>A.syncAll());
  A.$('#loadOlder').addEventListener('click',A.loadOlder);
  A.$('#walletList').addEventListener('click',e=>{const s=e.target.closest('[data-sync-wallet]'),r=e.target.closest('[data-remove-wallet]');if(s)A.syncOne(s.dataset.syncWallet);if(r&&confirm('Remover este endereço do CoinPortracker?')){const id=r.dataset.removeWallet;A.s.wallets=A.s.wallets.filter(w=>w.id!==id);A.s.tx=A.s.tx.filter(t=>!t.walletIds?.includes(id)&&t.walletId!==id);A.save().then(A.render)}});
  ['transactionSearch','filterNetwork','filterType','filterFrom','filterTo'].forEach(id=>A.$('#'+id).addEventListener('input',A.renderTransactions));
  A.$('#chartRanges').addEventListener('click',e=>{const b=e.target.closest('[data-range]');if(!b)return;A.chartRange=b.dataset.range==='all'?'all':+b.dataset.range;A.$$('#chartRanges button').forEach(x=>x.classList.toggle('selected',x===b));A.drawChart()});
  A.$('#costForm').addEventListener('submit',A.saveCosts);A.$('#flowForm').addEventListener('submit',A.addFlow);A.$('#defiForm').addEventListener('submit',A.addDefi);
  A.$('#includeDefi').addEventListener('change',e=>{A.s.settings.includeDefi=e.target.checked;A.save().then(A.render)});
  A.$('#displayCurrency').addEventListener('change',async e=>{A.s.settings.currency=e.target.value;await A.save();await A.refreshPrices();A.render()});
  A.$('#priceSource').addEventListener('change',async e=>{A.s.settings.priceSource=e.target.value;await A.save();await A.refreshPrices();A.render()});
  A.$('#coingeckoKey').addEventListener('change',async e=>{A.s.settings.coingeckoKey=e.target.value.trim();await A.save();await A.refreshPrices();A.render()});
  ['topBackup','sidebarBackup','exportBackup'].forEach(id=>A.$('#'+id).addEventListener('click',A.exportBackup));
  A.$('#importBackup').addEventListener('change',A.importBackup);A.$('#connectBackupFile').addEventListener('click',A.linkBackup);
  A.$('#requestPersistence').addEventListener('click',async()=>{const ok=await CPTStore.requestPersistence();A.$('#persistenceStatus').textContent=ok?'O navegador concedeu armazenamento persistente.':'O navegador não concedeu ou não oferece esta opção.';A.toast(ok?'Armazenamento persistente concedido.':'Persistência não garantida pelo navegador.')});
  A.$('#installButton').addEventListener('click',A.install);A.$('#installButtonSettings').addEventListener('click',A.install);
  A.$('#clearData').addEventListener('click',async()=>{if(confirm('Apagar carteiras, custos, DeFi, transações e histórico deste navegador? Faça backup antes.')){A.s=A.defaults();await A.save();A.render();A.toast('Dados locais apagados.')}});
  A.$('#flowList').addEventListener('click',e=>{const b=e.target.closest('[data-remove-flow]');if(b){A.s.flows=A.s.flows.filter(x=>x.id!==b.dataset.removeFlow);A.save().then(()=>{A.renderCosts();A.renderMetrics()})}});
  A.$('#defiList').addEventListener('click',e=>{const b=e.target.closest('[data-remove-defi]');if(b){A.s.defi=A.s.defi.filter(x=>x.id!==b.dataset.removeDefi);A.save().then(A.render)}});
  window.addEventListener('cpt-backup-warning',()=>A.$('#backupStatus').textContent='O backup vinculado precisa que você renove a permissão de gravação.');
  window.addEventListener('resize',A.drawChart);
 }
 async function init(){
  try{await CPTStore.restoreHandle();A.s=await CPTStore.load();if(!A.s.settings)A.s.settings=A.defaults().settings;if(!A.s.costs)A.s.costs=A.defaults().costs;if(!Array.isArray(A.s.flows))A.s.flows=[];if(!Array.isArray(A.s.snap))A.s.snap=[];if(!Array.isArray(A.s.tx))A.s.tx=[];bind();A.render();A.registerPwa();await A.refreshPrices();A.render();if(A.s.wallets.length)A.toast('Dados locais recuperados. Sincronize para atualizar os saldos.')}
  catch(e){console.error(e);A.s=A.defaults();bind();A.render();A.toast('Falha ao ler o armazenamento. Verifique se existe backup.')}
 }
 init();
})();