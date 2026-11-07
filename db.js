(function(){
 const DB='coinportracker', VERSION=1, STORE='records', FALLBACK='coinportracker-state-v2';
 let dbPromise, fileHandle=null, writeQueue=Promise.resolve();
 const clone=x=>typeof structuredClone==='function'?structuredClone(x):JSON.parse(JSON.stringify(x));
 function open(){
  if(dbPromise)return dbPromise;
  dbPromise=new Promise((resolve,reject)=>{
   const req=indexedDB.open(DB,VERSION);
   req.onupgradeneeded=()=>{const d=req.result;if(!d.objectStoreNames.contains(STORE))d.createObjectStore(STORE,{keyPath:'key'})};
   req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
  });return dbPromise;
 }
 async function get(key){const d=await open();return new Promise((resolve,reject)=>{const r=d.transaction(STORE,'readonly').objectStore(STORE).get(key);r.onsuccess=()=>resolve(r.result?.value);r.onerror=()=>reject(r.error)})}
 async function put(key,value){const d=await open();return new Promise((resolve,reject)=>{const r=d.transaction(STORE,'readwrite').objectStore(STORE).put({key,value});r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error)})}
 function stable(value){if(Array.isArray(value))return '['+value.map(stable).join(',')+']';if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+stable(value[k])).join(',')+'}';return JSON.stringify(value)}
 async function checksum(payload){if(!crypto?.subtle)return null;const bytes=new TextEncoder().encode(stable(payload));const hash=await crypto.subtle.digest('SHA-256',bytes);return [...new Uint8Array(hash)].map(x=>x.toString(16).padStart(2,'0')).join('')}
 function migrate(value){
  const s=value?.payload||value;
  if(!s||typeof s!=='object')throw Error('Formato de backup inválido.');
  return {version:2,wallets:Array.isArray(s.wallets)?s.wallets:[],tx:Array.isArray(s.tx)?s.tx:[],snap:Array.isArray(s.snap)?s.snap:Array.isArray(s.snapshots)?s.snapshots:[],flows:Array.isArray(s.flows)?s.flows:[],defi:Array.isArray(s.defi)?s.defi:[],costs:s.costs||{total: s.wallets?.reduce((n,w)=>n+(+w.basis||0),0)||0,fees:0,currency:'BRL',includesFees:false,startDate:'',notes:''},settings:{currency:s.settings?.currency||s.currency||'BRL',priceSource:s.settings?.priceSource||'llama',coingeckoKey:s.settings?.coingeckoKey||'',includeDefi:!!(s.settings?.includeDefi??s.includeDefi)},priceCache:s.priceCache||{},updatedAt:s.updatedAt||Date.now()};
 }
 async function load(){
  let s=await get('state');
  if(s)return migrate(s);
  const raw=localStorage.getItem(FALLBACK)||localStorage.getItem('cpt-v1');
  if(raw){try{s=migrate(JSON.parse(raw));await save(s);return s}catch(e){console.warn('Migração local incompleta',e)}}
  return migrate({});
 }
 async function serialize(state){
  const payload=migrate(state);delete payload.priceCache;
  return {app:'CoinPortracker',format:'coinportracker-backup',version:2,exportedAt:new Date().toISOString(),checksum:await checksum(payload),payload};
 }
 async function save(state){
  state.version=2;state.updatedAt=Date.now();
  const raw=JSON.stringify(state);
  try{await put('state',clone(state));localStorage.setItem(FALLBACK,raw)}catch(e){try{localStorage.setItem(FALLBACK,raw)}catch(_){}console.warn('IndexedDB indisponível; fallback localStorage',e)}
  if(fileHandle){writeQueue=writeQueue.then(()=>writeLinkedBackup(state)).catch(e=>{console.warn('Backup vinculado não atualizado',e);window.dispatchEvent(new CustomEvent('cpt-backup-warning',{detail:e}))})}
 }
 async function backupText(state){return JSON.stringify(await serialize(state),null,2)}
 async function exportFile(state){
  const text=await backupText(state),blob=new Blob([text],{type:'application/json'});
  if(window.showSaveFilePicker){
   try{const h=await showSaveFilePicker({suggestedName:'coinportracker-backup.json',types:[{description:'Backup CoinPortracker',accept:{'application/json':['.json']}}]});const w=await h.createWritable();await w.write(blob);await w.close();return 'Arquivo exportado com checksum SHA-256.'}catch(e){if(e.name==='AbortError')return 'Exportação cancelada.';throw e}
  }
  const a=document.createElement('a'),url=URL.createObjectURL(blob);a.href=url;a.download='coinportracker-backup.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return 'Backup baixado. Guarde uma cópia fora deste aparelho.';
 }
 async function importFile(file){
  const data=JSON.parse(await file.text());let payload;
  if(data?.format==='coinportracker-backup'){
   if(data.version>2)throw Error('Este backup foi criado por uma versão mais nova.');
   payload=data.payload;
   const actual=await checksum(payload);
   if(data.checksum&&actual&&actual!==data.checksum)throw Error('Checksum inválido: o backup pode ter sido alterado ou corrompido.');
  }else payload=data;
  return migrate(payload);
 }
 async function setHandle(handle){fileHandle=handle;await put('fileHandle',handle)}
 async function restoreHandle(){try{fileHandle=await get('fileHandle')}catch(e){fileHandle=null}}
 async function writeLinkedBackup(state){
  if(!fileHandle)return;
  const permission=await fileHandle.queryPermission({mode:'readwrite'});
  if(permission!=='granted')throw Error('Permissão do arquivo precisa ser renovada.');
  const w=await fileHandle.createWritable();await w.write(await backupText(state));await w.close();
 }
 async function linkFile(state){
  if(!window.showSaveFilePicker)throw Error('Este navegador não oferece gravação vinculada de arquivos. Use Baixar backup JSON.');
  const h=await showSaveFilePicker({suggestedName:'coinportracker-backup.json',types:[{description:'Backup CoinPortracker',accept:{'application/json':['.json']}}]});
  fileHandle=h;await setHandle(h);const w=await h.createWritable();await w.write(await backupText(state));await w.close();return true;
 }
 async function unlinkFile(){fileHandle=null;await put('fileHandle',null)}
 async function requestPersistence(){return navigator.storage?.persist?navigator.storage.persist():false}
 window.CPTStore={load,save,exportFile,importFile,linkFile,unlinkFile,restoreHandle,requestPersistence,checksum};
})();