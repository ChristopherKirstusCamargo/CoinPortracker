'use strict';
// CoinPortracker Web — painel somente leitura. Todo texto entra por textContent (nunca innerHTML).
(function () {
  const $ = (id) => document.getElementById(id);
  const H = { 'X-Requested-With': 'cpt' };
  const S = { range: 'M1', tab: 'assets', alloc: 'byAsset', assets: [], allocation: null, act: { offset: 0, items: [], total: 0 }, timer: null, preview: false, offline: false };

  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function dirClass(d) { return d === 'up' ? 'up' : d === 'down' ? 'down' : ''; }
  function setText(id, t, dir) { const n = $(id); n.textContent = t == null ? '—' : t; n.classList.remove('up', 'down'); const c = dirClass(dir); if (c) n.classList.add(c); }

  async function api(path) {
    let r;
    try { r = await fetch('/api/v1/' + path, { headers: H, credentials: 'same-origin', cache: 'no-store' }); }
    catch (e) { setOffline(true); throw e; }
    setOffline(false);
    if (r.status === 401) { showPair('Sessão encerrada. Pareie de novo.'); throw new Error('401'); }
    if (r.status === 403) { showPair('Acesso bloqueado no celular.'); throw new Error('403'); }
    if (!r.ok) throw new Error(String(r.status));
    return r.json();
  }

  function setOffline(v) {
    if (S.offline === v) return; S.offline = v;
    $('offline').hidden = !v; const c = $('conn'); c.textContent = v ? 'Sem conexão' : 'Conectado'; c.classList.toggle('bad', v);
  }

  // ---------- pareamento ----------
  function showPair(msg) {
    stopPolling(); $('app').hidden = true; $('pair').hidden = false;
    if (msg) say(msg, 'err');
    $('code').focus();
  }
  function say(t, kind) { const m = $('pairMsg'); m.textContent = t || ''; m.className = 'msg' + (kind ? ' ' + kind : ''); }

  async function pair(code) {
    $('pairBtn').disabled = true; say('Conferindo…');
    try {
      const r = await fetch('/auth/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin', body: JSON.stringify({ code: code }) });
      const j = await r.json().catch(() => ({}));
      if (r.status === 200) { say('Pareado.', 'ok'); start(); return; }
      if (r.status === 202 && j.poll) { say('Aprove o acesso no celular (notificação ou tela do Web Host)…'); waitApproval(j.poll, Date.now()); return; }
      say(j.error || 'Não foi possível parear.', 'err');
      if (j.attemptsLeft != null) say((j.error || 'Código incorreto.') + ' Restam ' + j.attemptsLeft + ' tentativas.', 'err');
    } catch (e) { say('Sem conexão com o celular. Confira se ele está na mesma rede Wi-Fi.', 'err'); }
    finally { $('pairBtn').disabled = false; }
  }

  async function waitApproval(poll, since) {
    if (Date.now() - since > 125000) { say('O pedido expirou. Gere um novo código no celular.', 'err'); return; }
    try {
      const r = await fetch('/auth/status', { headers: { 'X-Pair': poll }, credentials: 'same-origin', cache: 'no-store' });
      if (r.status === 200) { say('Aprovado.', 'ok'); start(); return; }
      if (r.status !== 202) { const j = await r.json().catch(() => ({})); say(j.error || 'Pedido recusado.', 'err'); return; }
    } catch (e) { /* tenta de novo */ }
    setTimeout(() => waitApproval(poll, since), 2000);
  }

  // ---------- painel ----------
  async function start() {
    let s;
    try { s = await api('session'); } catch (e) { return; }
    S.preview = !!s.preview;
    $('logout').hidden = S.preview;
    $('pair').hidden = true; $('app').hidden = false;
    loadAll(); startPolling();
  }

  function startPolling() { stopPolling(); S.timer = setInterval(() => { if (!document.hidden) loadLive(); }, 15000); }
  function stopPolling() { if (S.timer) clearInterval(S.timer); S.timer = null; }

  function loadAll() { loadSummary(); loadChart(); loadTab(); }
  function loadLive() { loadSummary(); if (S.tab === 'assets') loadAssets(); }

  async function loadSummary() {
    let j; try { j = await api('summary'); } catch (e) { return; }
    setText('total', j.total);
    setText('chg', (j.change24h || '—') + ' (' + (j.change24hPct || '—') + ')', j.change24hDir);
    const hasReal = j.result != null;
    $('real').hidden = false;
    setText('invested', hasReal ? j.invested : 'Não informado');
    setText('result', hasReal ? j.result + ' (' + j.resultPct + ')' : '—', j.resultDir);
    setText('realized', j.realized); setText('unrealized', j.unrealized);
    $('updated').textContent = j.lastSync ? 'Atualizado ' + ago(j.lastSync, j.now) : (j.ready ? '' : 'Carregando no celular…');
  }

  function ago(t, now) {
    const s = Math.max(0, Math.round(((now || Date.now()) - t) / 1000));
    if (s < 60) return 'agora'; if (s < 3600) return 'há ' + Math.round(s / 60) + ' min';
    if (s < 86400) return 'há ' + Math.round(s / 3600) + ' h'; return 'há ' + Math.round(s / 86400) + ' d';
  }

  async function loadChart() {
    const svg = $('chart'); const msg = $('chartMsg'); msg.textContent = 'Carregando gráfico…';
    let j; try { j = await api('chart?range=' + encodeURIComponent(S.range)); } catch (e) { msg.textContent = 'Gráfico indisponível agora.'; return; }
    clear(svg);
    const p = j.points || [];
    if (p.length < 2) { msg.textContent = 'Ainda sem histórico suficiente para este período.'; return; }
    msg.textContent = j.normalized ? 'Valores ocultos: só a forma do gráfico.' : '';
    let lo = Infinity, hi = -Infinity; const t0 = p[0][0], t1 = p[p.length - 1][0];
    for (const q of p) { if (q[1] < lo) lo = q[1]; if (q[1] > hi) hi = q[1]; }
    const W = 600, Hh = 180, pad = 8, span = hi - lo || 1, tspan = t1 - t0 || 1;
    const pts = p.map((q) => [((q[0] - t0) / tspan) * W, Hh - pad - ((q[1] - lo) / span) * (Hh - 2 * pad)]);
    const d = pts.map((q, i) => (i ? 'L' : 'M') + q[0].toFixed(1) + ' ' + q[1].toFixed(1)).join(' ');
    const ns = 'http://www.w3.org/2000/svg';
    const up = p[p.length - 1][1] >= p[0][1];
    const area = document.createElementNS(ns, 'path');
    area.setAttribute('d', d + ' L' + W + ' ' + Hh + ' L0 ' + Hh + ' Z'); area.setAttribute('fill', up ? 'rgba(61,220,132,0.08)' : 'rgba(255,92,92,0.08)');
    const line = document.createElementNS(ns, 'path');
    line.setAttribute('d', d); line.setAttribute('fill', 'none'); line.setAttribute('stroke', up ? '#3ddc84' : '#ff5c5c'); line.setAttribute('stroke-width', '2'); line.setAttribute('vector-effect', 'non-scaling-stroke');
    svg.appendChild(area); svg.appendChild(line);
  }

  function loadTab() { ({ assets: loadAssets, allocation: loadAllocation, staking: loadStaking, activity: () => loadActivity(true) })[S.tab](); }

  function skeleton(list, n) { clear(list); for (let i = 0; i < n; i++) list.appendChild(el('div', 'skel')); }
  function empty(list, t) { clear(list); list.appendChild(el('div', 'empty', t)); }

  async function loadAssets() {
    const list = $('assetList'); if (!S.assets.length) skeleton(list, 5);
    try { S.assets = (await api('assets')).items || []; } catch (e) { if (!S.assets.length) empty(list, 'Não foi possível ler os ativos agora.'); return; }
    renderAssets();
  }

  function renderAssets() {
    const list = $('assetList'); const q = $('assetQ').value.trim().toLowerCase(); const sort = $('assetSort').value;
    let a = S.assets.filter((x) => !q || x.symbol.toLowerCase().includes(q) || x.name.toLowerCase().includes(q) || x.networks.some((n) => n.toLowerCase().includes(q)));
    const num = (s) => { const v = parseFloat(String(s).replace(/[^0-9,.-]/g, '').replace(/\./g, '').replace(',', '.')); return isNaN(v) ? -1e18 : v; };
    if (sort === 'value') a.sort((x, y) => y.sort - x.sort);
    else if (sort === 'change') a.sort((x, y) => num(y.change24h) - num(x.change24h));
    else if (sort === 'pnl') a.sort((x, y) => num(y.pnlPct) - num(x.pnlPct));
    else a.sort((x, y) => x.symbol.localeCompare(y.symbol));
    clear(list);
    if (!a.length) { empty(list, S.assets.length ? 'Nada encontrado com essa busca.' : 'Nenhum ativo ainda. Adicione uma carteira no app.'); return; }
    for (const x of a) {
      const it = el('div', 'item');
      it.appendChild(el('div', 'badge', x.symbol.slice(0, 3)));
      const g = el('div', 'grow'); g.appendChild(el('div', 't1', x.symbol + (x.staking ? ' · staking' : '')));
      g.appendChild(el('div', 't2', x.amount + ' · ' + x.networks.join(', '))); it.appendChild(g);
      const r = el('div', 'num'); r.appendChild(el('div', 't1', x.value));
      const c = el('div', 't2 ' + dirClass(x.change24hDir), x.change24h + ' 24 h'); r.appendChild(c);
      if (x.pnl !== '—') r.appendChild(el('div', 't2 ' + dirClass(x.pnlDir), x.pnl + ' (' + x.pnlPct + ')'));
      it.appendChild(r); list.appendChild(it);
    }
  }

  async function loadAllocation() {
    const list = $('allocList'); if (!S.allocation) skeleton(list, 4);
    try { S.allocation = await api('allocation'); } catch (e) { if (!S.allocation) empty(list, 'Não foi possível ler a alocação agora.'); return; }
    renderAllocation();
  }

  function renderAllocation() {
    const list = $('allocList'); clear(list);
    const rows = (S.allocation && S.allocation[S.alloc]) || [];
    if (!rows.length) { empty(list, 'Sem dados de alocação ainda.'); return; }
    for (const s of rows) {
      const it = el('div', 'item'); const g = el('div', 'grow');
      const top = el('div', 'row'); top.appendChild(el('div', 't1', s.label)); top.appendChild(el('div', 'num right t1', (s.share * 100).toFixed(1).replace('.', ',') + '%'));
      g.appendChild(top); const bar = el('div', 'bar'); const fill = el('i'); fill.style.width = Math.max(0.5, s.share * 100).toFixed(2) + '%'; bar.appendChild(fill); g.appendChild(bar);
      g.appendChild(el('div', 't2', s.value)); it.appendChild(g); list.appendChild(it);
    }
  }

  async function loadStaking() {
    const list = $('stakeList'); skeleton(list, 2);
    let j; try { j = await api('staking'); } catch (e) { empty(list, 'Não foi possível ler o staking agora.'); return; }
    $('stakeTotal').textContent = j.total; clear(list);
    if (!j.items.length) { empty(list, 'Nenhuma conta de stake encontrada nas suas carteiras.'); return; }
    for (const s of j.items) {
      const it = el('div', 'item'); it.appendChild(el('div', 'badge', 'SOL'));
      const g = el('div', 'grow'); g.appendChild(el('div', 't1', s.principal)); g.appendChild(el('div', 't2', 'Conta ' + s.account + (s.complete ? '' : ' · leitura parcial'))); it.appendChild(g);
      const r = el('div', 'num'); r.appendChild(el('div', 't1 up', '+' + s.rewards)); r.appendChild(el('div', 't2', s.rewardsValue + ' · APR ' + s.apr)); it.appendChild(r);
      list.appendChild(it);
    }
  }

  let actSeq = 0;
  async function loadActivity(reset) {
    const list = $('actList'); const seq = ++actSeq;
    if (reset) { S.act = { offset: 0, items: [], total: 0 }; skeleton(list, 5); }
    const q = 'activity?limit=50&offset=' + S.act.offset + '&q=' + encodeURIComponent($('actQ').value.trim()) + '&kind=' + encodeURIComponent($('actKind').value);
    let j; try { j = await api(q); } catch (e) { if (reset) empty(list, 'Não foi possível ler a atividade agora.'); return; }
    if (seq !== actSeq) return;
    S.act.items = S.act.items.concat(j.items); S.act.total = j.total; S.act.offset = S.act.items.length;
    renderActivity();
  }

  function dayLabel(t) {
    if (!t) return 'Sem data';
    const d = new Date(t); const today = new Date(); const y = new Date(); y.setDate(today.getDate() - 1);
    if (d.toDateString() === today.toDateString()) return 'Hoje'; if (d.toDateString() === y.toDateString()) return 'Ontem';
    return d.toLocaleDateString('pt-BR', { day: '2-digit', month: 'short', year: 'numeric' });
  }

  function renderActivity() {
    const list = $('actList'); clear(list);
    if (!S.act.items.length) { empty(list, ($('actQ').value || $('actKind').value) ? 'Nada encontrado com esses filtros.' : 'Nenhum movimento lido ainda. Abra a Atividade no app para ler o histórico.'); $('actMore').hidden = true; return; }
    let last = null;
    for (const a of S.act.items) {
      const dl = dayLabel(a.time); if (dl !== last) { list.appendChild(el('div', 'day', dl)); last = dl; }
      const it = el('div', 'item'); it.appendChild(el('div', 'badge', a.symbol.slice(0, 3)));
      const g = el('div', 'grow'); g.appendChild(el('div', 't1', a.label + (a.failed ? ' · falhou' : '')));
      g.appendChild(el('div', 't2', [a.network, a.wallet, a.time ? new Date(a.time).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : ''].filter(Boolean).join(' · ')));
      it.appendChild(g);
      const cls = a.kind === 'IN' || a.kind === 'REWARD' ? 'up' : a.kind === 'OUT' ? 'down' : '';
      it.appendChild(el('div', 'num t1 ' + cls, a.amount + ' ' + a.symbol)); list.appendChild(it);
    }
    $('actMore').hidden = S.act.items.length >= S.act.total;
  }

  // ---------- eventos ----------
  function wire() {
    $('pairForm').addEventListener('submit', (e) => { e.preventDefault(); const c = $('code').value.replace(/[^0-9a-z]/gi, ''); if (c.length >= 6) pair(c); else say('Digite o código de 8 caracteres.', 'err'); });
    $('ranges').addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return; S.range = b.dataset.r; for (const x of $('ranges').children) x.classList.toggle('on', x === b); loadChart(); });
    $('tabs').addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return; S.tab = b.dataset.t;
      for (const x of $('tabs').children) x.classList.toggle('on', x === b);
      for (const t of ['assets', 'allocation', 'staking', 'activity']) $('t-' + t).hidden = t !== S.tab;
      loadTab();
    });
    $('allocSeg').addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return; S.alloc = b.dataset.a; for (const x of $('allocSeg').children) x.classList.toggle('on', x === b); renderAllocation(); });
    $('assetQ').addEventListener('input', renderAssets); $('assetSort').addEventListener('change', renderAssets);
    let deb = null; $('actQ').addEventListener('input', () => { clearTimeout(deb); deb = setTimeout(() => loadActivity(true), 300); });
    $('actKind').addEventListener('change', () => loadActivity(true));
    $('actMore').addEventListener('click', () => loadActivity(false));
    $('logout').addEventListener('click', async () => {
      try { await fetch('/auth/logout', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}' }); } catch (e) { }
      showPair(); say('Você saiu. Para voltar, gere um novo código no celular.');
    });
    document.addEventListener('visibilitychange', () => { if (!document.hidden && !$('app').hidden) loadLive(); });
  }

  function boot() {
    wire();
    const m = /[#&]p=([0-9A-Za-z]{6,12})/.exec(location.hash || '');
    if (m) { history.replaceState(null, '', location.pathname); $('code').value = m[1]; }
    fetch('/api/v1/session', { headers: H, credentials: 'same-origin', cache: 'no-store' }).then((r) => {
      if (r.ok) start(); else { showPair(); if (m) pair(m[1]); }
    }).catch(() => { showPair('Sem conexão com o celular.'); });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
