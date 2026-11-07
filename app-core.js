(function(){
 const A=window.CPTApp={state:null,livePrices:{},fxRate:null,chartRange:86400000};
 A.defaults=()=>({version:2,wallets:[],tx:[],snap:[],flows:[],defi:[],costs:{total:0,fees:0,currency:'BRL',includesFees:false,startDate:'',notes:''},settings:{currency:'BRL',priceSource:'llama',coingeckoKey:'',includeDefi:false},priceCache:{},updatedAt:Date.now()});
 A.$=s=>document.querySelector(s);A.$$=s=>[...document.querySelectorAll(s)];
 A.esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 A.money=(n,cur)=>Number.isFinite(+n)?new Intl.NumberFormat('pt-BR',{style:'currency',currency:cur||A.s.settings.currency||'BRL',maximumFractionDigits:2}).format(+n):'—';
 A.num=(n,d=8)=>Number(n||0).toLocaleString('pt-BR',{maximumFractionDigits:d});
 A.convert=(n,from,to)=>{if(from===to)return +n||0;if(!A.fxRate)return null;return from==='USD'?(+n||0)*A.fxRate:(+n||0)/A.fxRate};
 A.key=a=>CPTMarket.llamaKey(a);
 A.toast=msg=>{const t=A.$('#toast');t.textContent=msg;t.classList.add('show');clearTimeout(A.toastTimer);A.toastTimer=setTimeout(()=>t.classList.remove('show'),2800)};
 A.chains={BTC:'Bitcoin',ETH:'Ethereum',BASE:'Base',ARB:'Arbitrum',POL:'Polygon',SOL:'Solana',XRP:'XRP Ledger',TRON:'TRON'};
 A.assets=()=>A.s.wallets.flatMap(w=>(w.assets||[]).map(a=>({...a,walletId:w.id})));
 A.holdings=()=>{
  const map=new Map();for(const a of A.assets()){const id=[a.chain,a.symbol,a.contract||'native'].join('|'),p=A.livePrices[A.key(a)],v=p?.value??null,x=map.get(id)||{...a,amount:0,value:0,priced:v!==null,price:v};x.amount+=+a.amount||0;if(v!==null)x.value+=(+a.amount||0)*v;map.set(id,x)}
  return [...map.values()].sort((a,b)=>(b.value||0)-(a.value||0));
 };
 A.defiValue=()=>A.s.defi.reduce((n,d)=>{if(d.alreadyCounted)return n;const v=A.convert(+d.value||0,d.currency||A.s.settings.currency,A.s.settings.currency);return n+(d.type==='liability'?-Math.abs(v||0):Math.abs(v||0))},0);
 A.total=()=>{const h=A.holdings();return {value:h.reduce((n,x)=>n+(x.priced?x.value:0),0)+(A.s.settings.includeDefi?A.defiValue():0),priced:h.every(x=>x.priced),assets:h}};
 A.costs=()=>{const c=A.s.costs,b=A.convert(+c.total||0,c.currency||'BRL',A.s.settings.currency),f=A.convert(+c.fees||0,c.currency||'BRL',A.s.settings.currency);if(b===null||f===null)return {base:null,fees:null,withFees:null,withoutFees:null};return {base:b,fees:f,withoutFees:c.includesFees?Math.max(0,b-f):b,withFees:c.includesFees?b:b+f}};
 A.flowsBetween=(from,to)=>A.s.flows.filter(f=>f.time>=from&&f.time<=to).reduce((n,f)=>{const v=A.convert(+f.amount||0,f.currency||A.s.settings.currency,A.s.settings.currency);return n+(v===null?0:(f.type==='withdrawal'?-v:v))},0);
 A.periodPnl=range=>{if(range==='all'){const t=A.total(),c=A.costs(),start=A.s.costs.startDate?Date.parse(A.s.costs.startDate+'T00:00:00'):0;return t.priced&&c.withFees!==null?t.value-c.withFees-A.flowsBetween(start,Date.now()):null}const start=Date.now()-range,p=A.s.snap.filter(x=>x.t<=start).sort((a,b)=>b.t-a.t)[0],now=A.total(),gap=range<=3600000?1800000:range<=86400000?21600000:range<=604800000?86400000:604800000;return p&&start-p.t<=gap&&now.priced?now.value-p.v-A.flowsBetween(p.t,Date.now()):null};
 A.save=()=>CPTStore.save(A.s);
 A.started=()=>{const v=A.s.wallets.map(w=>w.oldestFound).filter(Boolean);return v.length?Math.min(...v):null};
 A.syncInternalTransfers=()=>{const chains=new Map();for(const w of A.s.wallets)chains.set(w.network,[...(chains.get(w.network)||[]),w.address.toLowerCase()]);for(const t of A.s.tx){const own=chains.get(t.chain)||[];if(t.from&&t.to&&own.includes(t.from.toLowerCase())&&own.includes(t.to.toLowerCase()))t.kind='internal'}};
 A.refreshPrices=async()=>{const assets=A.assets();if(!assets.length){A.livePrices={};A.fxRate=null;return}const r=await CPTMarket.getPrices(assets,A.s.settings,A.s.priceCache);A.livePrices=r.prices;A.fxRate=r.fxRate;await A.save()};
})();