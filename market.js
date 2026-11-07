(function(){
 const FX='https://api.frankfurter.dev/v1/latest?base=USD&symbols=BRL';
 const chainAlias={ETH:'ethereum',BASE:'base',ARB:'arbitrum',POL:'polygon',SOL:'solana'};
 function llamaKey(asset){if(!asset.priceKey)return null;const k=asset.priceKey;if(k.startsWith('coingecko:'))return k;if(k.startsWith('0x')||k.length>30){const chain=chainAlias[asset.chain]||asset.chain.toLowerCase();return chain+':'+k}return k}
 async function json(url,headers={}){const r=await fetch(url,{headers,signal:AbortSignal.timeout?AbortSignal.timeout(15000):undefined});if(!r.ok)throw Error('HTTP '+r.status);return r.json()}
 async function fxRate(){try{const d=await json(FX);return +d.rates?.BRL||null}catch(_){return null}}
 async function getPrices(assets,settings,cache){
  const keys=[...new Set(assets.map(llamaKey).filter(Boolean))],usd={},source={},now=Date.now();
  for(let i=0;i<keys.length;i+=40){const batch=keys.slice(i,i+40);try{const d=await json('https://coins.llama.fi/prices/current/'+batch.map(encodeURIComponent).join(',')+'?searchWidth=4h');for(const [key,v] of Object.entries(d.coins||{})){if(+v.price>0){usd[key]=+v.price;source[key]='DefiLlama'}}}catch(e){console.warn('DefiLlama indisponível',e)}}
  if(settings.priceSource==='coingecko'&&settings.coingeckoKey){
   const idMap={'coingecko:bitcoin':'bitcoin','coingecko:ethereum':'ethereum','coingecko:solana':'solana','coingecko:ripple':'ripple','coingecko:tron':'tron','coingecko:polygon-ecosystem-token':'polygon-ecosystem-token'};
   const missing=keys.filter(k=>!usd[k]&&idMap[k]);if(missing.length){try{const ids=missing.map(k=>idMap[k]).join(','),d=await json('https://api.coingecko.com/api/v3/simple/price?ids='+ids+'&vs_currencies=usd',{'x-cg-demo-api-key':settings.coingeckoKey});for(const k of missing){const v=d[idMap[k]]?.usd;if(v){usd[k]=+v;source[k]='CoinGecko Demo'}}}catch(e){console.warn('CoinGecko Demo indisponível',e)}}
  }
  const rate=settings.currency==='BRL'?await fxRate():1,prices={};
  for(const asset of assets){const key=llamaKey(asset);if(!key)continue;let value=usd[key];if(!value&&asset.estimatedPrice>0)value=asset.estimatedPrice;const saved=cache?.[key];if(!value&&saved?.usd&&now-saved.time<86400000){value=saved.usd;source[key]='Cache local'}if(value){prices[key]={usd:value,value:settings.currency==='BRL'?(rate?value*rate:null):value,currency:settings.currency,source:source[key]||'Exchange rate do indexador',time:now};if(!cache[key]||source[key]!=='Cache local')cache[key]={usd:value,time:now,source:source[key]||'DefiLlama'}}}
  return {prices,fxRate:rate};
 }
 window.CPTMarket={getPrices,llamaKey};
})();