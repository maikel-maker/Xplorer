// Graphiques en ligne (SVG, sans librairie) avec points d'achat/vente et lecture au doigt.
let uid = 0;

// opts: { series:[{points:[{t,v}], color, fill}], markers:[{t,v,kind:'buy'|'sell', label}],
//         lines:[{v,color,label}], height, fmt }
export function lineChart(opts) {
  const W = 360, H = opts.height || 200, padT = 14, padB = 22, padL = 4, padR = 4;
  const all = opts.series.flatMap(s => s.points);
  if (all.length < 2) return `<div class="chart-empty">Pas encore assez de données pour tracer la courbe.</div>`;
  const t0 = Math.min(...all.map(p => p.t)), t1 = Math.max(...all.map(p => p.t));
  const vals = [...all.map(p => p.v), ...(opts.lines || []).map(l => l.v), ...(opts.markers || []).map(m => m.v)];
  let v0 = Math.min(...vals), v1 = Math.max(...vals);
  const pad = (v1 - v0) * 0.08 || v1 * 0.05 || 1; v0 -= pad; v1 += pad;
  const x = t => padL + (W - padL - padR) * (t1 === t0 ? 0.5 : (t - t0) / (t1 - t0));
  const y = v => padT + (H - padT - padB) * (1 - (v - v0) / (v1 - v0));
  const id = `c${++uid}`;

  const lines = (opts.lines || []).filter(l => l.v > v0 && l.v < v1).map(l => `
    <line x1="${padL}" x2="${W - padR}" y1="${y(l.v)}" y2="${y(l.v)}" stroke="${l.color}" stroke-width="1" stroke-dasharray="3 4" opacity=".7"/>
    <text x="${W - padR - 2}" y="${y(l.v) - 4}" text-anchor="end" class="ch-lbl" fill="${l.color}">${l.label}</text>`).join('');

  const paths = opts.series.map((s, i) => {
    const pts = s.points;
    const d = pts.map((p, j) => `${j ? 'L' : 'M'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join('');
    const area = s.fill ? `<path d="${d}L${x(pts[pts.length - 1].t).toFixed(1)},${H - padB}L${x(pts[0].t).toFixed(1)},${H - padB}Z" fill="url(#${id}g${i})"/>
      <defs><linearGradient id="${id}g${i}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${s.color}" stop-opacity=".28"/><stop offset="1" stop-color="${s.color}" stop-opacity="0"/></linearGradient></defs>` : '';
    return `${area}<path d="${d}" fill="none" stroke="${s.color}" stroke-width="${s.width || 2}" stroke-linejoin="round" stroke-linecap="round"/>`;
  }).join('');

  const markers = (opts.markers || []).filter(m => m.t >= t0 && m.t <= t1).map(m => `
    <circle cx="${x(m.t)}" cy="${y(m.v)}" r="5" fill="${m.kind === 'buy' ? 'var(--green)' : 'var(--red)'}" stroke="var(--bg)" stroke-width="2"/>`).join('');

  const span = t1 - t0;
  const fmtD = t => span < 2 * 864e5 ? new Date(t).toLocaleTimeString('fr-BE', { hour: '2-digit', minute: '2-digit' })
    : new Date(t).toLocaleDateString('fr-BE', { day: 'numeric', month: 'short', year: span > 300 * 864e5 ? '2-digit' : undefined });
  const axis = `<text x="${padL}" y="${H - 6}" class="ch-axis">${fmtD(t0)}</text><text x="${W - padR}" y="${H - 6}" text-anchor="end" class="ch-axis">${fmtD(t1)}</text>`;

  const data = encodeURIComponent(JSON.stringify({ W, t0, t1, padL, padR, series: opts.series.map(s => ({ c: s.color, n: s.name || '', p: s.points.map(p => [p.t, p.v]) })) }));
  return `<div class="chart" data-chart="${data}">
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${opts.label || 'Graphique'}">
      ${lines}${paths}${markers}${axis}
      <line class="ch-cursor" x1="0" x2="0" y1="${padT}" y2="${H - padB}" stroke="var(--muted)" stroke-width="1" opacity="0"/>
    </svg>
    <div class="ch-tip" hidden></div>
  </div>`;
}

// Mini-courbe pour la liste
export function sparkline(values, color) {
  if (!values || values.length < 2) return '<svg class="spark"></svg>';
  const W = 54, H = 26, min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const d = values.map((v, i) => `${i ? 'L' : 'M'}${(i / (values.length - 1) * W).toFixed(1)},${(H - 2 - (v - min) / span * (H - 4)).toFixed(1)}`).join('');
  return `<svg class="spark" viewBox="0 0 ${W} ${H}" aria-hidden="true"><path d="${d}" fill="none" stroke="${color}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
}

// Baromètre demi-cercle, score de -1 (négatif) à +1 (positif)
export function gauge(score) {
  const s = Math.max(-1, Math.min(1, score || 0));
  const cx = 110, cy = 104, r = 86;
  const pt = (a, rr = r) => [cx + rr * Math.cos(Math.PI * (1 - a)), cy - rr * Math.sin(Math.PI * (1 - a))];
  const a = (s + 1) / 2;
  const [nx, ny] = pt(a, r - 16);
  const ticks = [0, .25, .5, .75, 1].map(t => { const [x1, y1] = pt(t, r + 8); const [x2, y2] = pt(t, r + 13); return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="var(--edge-2)" stroke-width="2" stroke-linecap="round"/>`; }).join('');
  const [sx, sy] = pt(0), [ex, ey] = pt(1);
  return `<svg viewBox="0 0 220 120" role="img" aria-label="Baromètre technique">
    <defs><linearGradient id="gg" x1="0" x2="1"><stop offset="0" stop-color="var(--loss)"/><stop offset=".5" stop-color="var(--slate)"/><stop offset="1" stop-color="var(--gain)"/></linearGradient></defs>
    <path d="M${sx},${sy} A${r},${r} 0 0 1 ${ex},${ey}" fill="none" stroke="rgba(7,11,22,.7)" stroke-width="14" stroke-linecap="round"/>
    <path d="M${sx},${sy} A${r},${r} 0 0 1 ${ex},${ey}" fill="none" stroke="url(#gg)" stroke-width="10" stroke-linecap="round" opacity=".9"/>
    ${ticks}
    <line x1="${cx}" y1="${cy}" x2="${nx}" y2="${ny}" stroke="var(--gold)" stroke-width="3" stroke-linecap="round"/>
    <circle cx="${cx}" cy="${cy}" r="6" fill="var(--gold)"/>
  </svg>`;
}

// Lecture au doigt : affiche date + valeurs
export function wireCharts(root, fmt) {
  root.querySelectorAll('.chart[data-chart]').forEach(el => {
    const d = JSON.parse(decodeURIComponent(el.dataset.chart));
    const svg = el.querySelector('svg'), tip = el.querySelector('.ch-tip'), cur = el.querySelector('.ch-cursor');
    const move = ev => {
      const r = svg.getBoundingClientRect();
      const px = ((ev.touches ? ev.touches[0].clientX : ev.clientX) - r.left) / r.width * d.W;
      const t = d.t0 + (d.t1 - d.t0) * Math.min(1, Math.max(0, (px - d.padL) / (d.W - d.padL - d.padR)));
      const rows = d.series.map(s => {
        let best = s.p[0];
        for (const p of s.p) if (Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p;
        return { ...s, v: best[1], t: best[0] };
      });
      cur.setAttribute('x1', px); cur.setAttribute('x2', px); cur.setAttribute('opacity', '.6');
      tip.hidden = false;
      const dt = new Date(rows[0].t);
      const when = d.t1 - d.t0 < 8 * 864e5 ? dt.toLocaleString('fr-BE', { weekday: 'short', hour: '2-digit', minute: '2-digit' }) : dt.toLocaleDateString('fr-BE', { day: 'numeric', month: 'short', year: 'numeric' });
      tip.innerHTML = `<b>${when}</b>` +
        rows.map(s => `<span><i style="background:${s.c}"></i>${s.n ? s.n + ' ' : ''}${fmt(s.v)}</span>`).join('');
    };
    const end = () => { tip.hidden = true; cur.setAttribute('opacity', '0'); };
    svg.addEventListener('pointermove', move);
    svg.addEventListener('touchmove', move, { passive: true });
    svg.addEventListener('pointerleave', end);
    svg.addEventListener('touchend', end);
  });
}
