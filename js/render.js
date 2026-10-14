// Zeichnet das Foto samt Watermark auf ein Canvas.
// Alle Größen sind relativ zur kürzeren Bildseite, damit das Watermark
// auf jedem Foto (hoch/quer, 12 MP oder 200 MP) gleich aussieht.

const SANS = 'Roboto, "Helvetica Neue", "Segoe UI", Arial, sans-serif';
const SERIF = 'Georgia, "Noto Serif", "Times New Roman", serif';
const SCRIPT = '"Dancing Script", "Brush Script MT", "Segoe Script", cursive';

const BRAND_STYLES = {
  plain: { weight: 400, family: SANS, spacing: 0 },
  bold: { weight: 700, family: SANS, spacing: 0 },
  round: { weight: 800, family: SANS, spacing: -0.02 },
  wide: { weight: 700, family: SANS, spacing: 0.14 },
  italic: { weight: 700, family: SANS, spacing: 0, italic: true },
  serif: { weight: 700, family: SERIF, spacing: 0 },
};

const THEMES = {
  light: { bg: "#ffffff", title: "#111111", sub: "#8a8a8a", sep: "#d2d2d2" },
  dark: { bg: "#0e0e0e", title: "#f4f4f4", sub: "#8f8f8f", sep: "#3d3d3d" },
};

const imageCache = new Map();

/** Lädt ein (hochgeladenes) Logo, damit es beim Zeichnen bereit ist. */
export function loadBadgeImage(src) {
  if (!src) return Promise.resolve(null);
  if (imageCache.has(src)) return imageCache.get(src);
  const p = new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
  imageCache.set(src, p);
  p.then((img) => imageCache.set(src, img));
  return p;
}

function cachedImage(src) {
  const v = imageCache.get(src);
  return v instanceof HTMLImageElement ? v : null;
}

function font(ctx, size, { weight = 400, family = SANS, italic = false } = {}) {
  ctx.font = `${italic ? "italic " : ""}${weight} ${Math.max(1, size).toFixed(2)}px ${family}`;
}

function measureSpaced(ctx, text, spacing) {
  if (!spacing) return ctx.measureText(text).width;
  const chars = [...text];
  let w = 0;
  for (const c of chars) w += ctx.measureText(c).width;
  return w + spacing * Math.max(0, chars.length - 1);
}

function fillSpaced(ctx, text, x, y, spacing) {
  if (!spacing) {
    ctx.fillText(text, x, y);
    return;
  }
  for (const c of [...text]) {
    ctx.fillText(c, x, y);
    x += ctx.measureText(c).width + spacing;
  }
}

/* ---------- Textzeilen aus mehreren Teilen (z.B. "Shot on" + "vivo" + "X300 Ultra") ---------- */

function titleRuns(preset, size, color) {
  const runs = [];
  if (preset.prefix) runs.push({ text: preset.prefix, size, weight: 400, family: SANS, color });
  if (preset.brand) {
    const s = BRAND_STYLES[preset.brandStyle] || BRAND_STYLES.bold;
    runs.push({ text: preset.brand, size, ...s, spacing: s.spacing * size, color });
  }
  if (preset.model) runs.push({ text: preset.model, size, weight: 700, family: SANS, color });
  return runs;
}

function textRun(text, size, color, weight = 400) {
  return text ? [{ text, size, weight, family: SANS, color }] : [];
}

function measureRuns(ctx, runs) {
  let w = 0;
  runs.forEach((r, i) => {
    font(ctx, r.size, r);
    w += measureSpaced(ctx, r.text, r.spacing || 0);
    if (i < runs.length - 1) w += r.size * 0.28;
  });
  return w;
}

function drawRuns(ctx, runs, x, baseline) {
  runs.forEach((r) => {
    font(ctx, r.size, r);
    ctx.fillStyle = r.color;
    fillSpaced(ctx, r.text, x, baseline, r.spacing || 0);
    x += measureSpaced(ctx, r.text, r.spacing || 0) + r.size * 0.28;
  });
}

/* ---------- Partner-Logo (ZEISS, Leica, Hasselblad, eigenes Bild …) ---------- */

function badgeFontSpec(badge, h) {
  switch (badge.font) {
    case "wide":
      return { size: h * 0.5, weight: 700, family: SANS, spacing: h * 0.5 * 0.2 };
    case "serif":
      return { size: h * 0.9, weight: 400, family: SERIF, spacing: 0 };
    case "script":
      return { size: h * 0.62, weight: 700, family: SCRIPT, spacing: 0 };
    default:
      return { size: h * 0.56, weight: 700, family: SANS, spacing: 0 };
  }
}

function badgeWidth(ctx, badge, h) {
  if (!badge || badge.type === "none") return 0;
  if (badge.type === "circle") return h;
  if (badge.type === "image") {
    const img = cachedImage(badge.src);
    return img ? (h * img.naturalWidth) / Math.max(1, img.naturalHeight) : 0;
  }
  if (!badge.text) return 0;
  if (badge.type === "box") {
    const f = { size: h * 0.46, weight: 700, family: SANS };
    font(ctx, f.size, f);
    return ctx.measureText(badge.text).width + h * 0.7;
  }
  const f = badgeFontSpec(badge, h);
  font(ctx, f.size, f);
  return measureSpaced(ctx, badge.text, f.spacing);
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function drawBadge(ctx, badge, x, cy, h, autoColor) {
  const w = badgeWidth(ctx, badge, h);
  if (!w) return 0;
  const top = cy - h / 2;
  ctx.save();
  ctx.textBaseline = "alphabetic";
  if (badge.type === "image") {
    ctx.drawImage(cachedImage(badge.src), x, top, w, h);
  } else if (badge.type === "box") {
    ctx.fillStyle = badge.bg || "#1d4e9e";
    roundRect(ctx, x, top, w, h, h * 0.1);
    ctx.fill();
    const size = h * 0.46;
    font(ctx, size, { weight: 700 });
    ctx.fillStyle = badge.fg || "#ffffff";
    ctx.textAlign = "center";
    ctx.fillText(badge.text, x + w / 2, cy + size * 0.36);
  } else if (badge.type === "circle") {
    ctx.fillStyle = badge.bg || "#e20612";
    ctx.beginPath();
    ctx.arc(x + h / 2, cy, h / 2, 0, Math.PI * 2);
    ctx.fill();
    const spec = badgeFontSpec({ font: badge.font || "script" }, h);
    let size = h * 0.42;
    font(ctx, size, spec);
    const tw = ctx.measureText(badge.text || "").width;
    if (tw > h * 0.8) size *= (h * 0.8) / tw;
    font(ctx, size, spec);
    ctx.fillStyle = badge.fg || "#ffffff";
    ctx.textAlign = "center";
    ctx.fillText(badge.text || "", x + h / 2, cy + size * 0.33);
  } else {
    const f = badgeFontSpec(badge, h);
    font(ctx, f.size, f);
    ctx.fillStyle = !badge.fg || badge.fg === "auto" ? autoColor : badge.fg;
    ctx.textAlign = "left";
    fillSpaced(ctx, badge.text, x, cy + f.size * 0.36, f.spacing);
  }
  ctx.restore();
  return w;
}

/* ---------- Layouts ---------- */

export function formatDate(d) {
  if (!d) return "";
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Zeile mit Kameradaten / Datum je nach Vorlage. */
export function infoLines(preset, info) {
  let cam = "";
  if (preset.exifMode === "photo") cam = info.cameraData || preset.exifText || "";
  else if (preset.exifMode === "custom") cam = preset.exifText || "";
  const date = preset.showDate ? formatDate(info.date) : "";
  return { cam, date, subtitle: preset.subtitle || "" };
}

/**
 * Größe des fertigen Bildes für ein Foto der Größe w × h.
 */
export function outputSize(preset, w, h) {
  const u = Math.min(w, h) / 100;
  if (preset.style === "overlay") return { width: w, height: h };
  const b = preset.border ? Math.round(2.4 * u) : 0;
  const bar = Math.round((preset.style === "frame-center" ? 13 : 10.5) * u);
  return { width: w + 2 * b, height: h + b + bar };
}

/** Bereich des fertigen Bildes, in dem das Watermark sitzt (für die Vorlagen-Kacheln). */
export function watermarkArea(preset, w, h) {
  const { width, height } = outputSize(preset, w, h);
  const u = Math.min(w, h) / 100;
  if (preset.style === "overlay") {
    const ch = Math.round(16 * u);
    return { x: 0, y: height - ch, w: width, h: ch };
  }
  const b = preset.border ? Math.round(2.4 * u) : 0;
  const top = Math.max(0, Math.round(b + h - 6 * u));
  return { x: 0, y: top, w: width, h: height - top };
}

/**
 * Zeichnet das Foto mit Watermark.
 * @param source  ImageBitmap/Canvas/Bild
 * @param w,h     Zielgröße des Fotos (bereits skaliert)
 * @param info    { cameraData, date }
 * @returns HTMLCanvasElement
 */
export function renderWatermark(source, w, h, preset, info, canvas = document.createElement("canvas")) {
  const { width, height } = outputSize(preset, w, h);
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingQuality = "high";
  ctx.textBaseline = "alphabetic";
  ctx.textAlign = "left";

  const theme = THEMES[preset.theme] || THEMES.light;
  const lines = infoLines(preset, info);
  const u = Math.min(w, h) / 100;

  if (preset.style === "overlay") {
    ctx.drawImage(source, 0, 0, w, h);
    drawOverlay(ctx, preset, lines, w, h, u);
    return canvas;
  }

  const b = preset.border ? Math.round(2.4 * u) : 0;
  ctx.fillStyle = theme.bg;
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(source, b, b, w, h);

  const bar = { x: 0, y: b + h, w: width, h: height - b - h };
  if (preset.style === "frame-center") drawFrameCenter(ctx, preset, lines, theme, bar, u);
  else drawFrameSplit(ctx, preset, lines, theme, bar, u, b);
  return canvas;
}

function twoLineBaselines(cy, t, s, gap) {
  // Zentriert einen Block aus Titel (Größe t) und Unterzeile (Größe s) um cy.
  const y1 = (2 * cy + 0.72 * t - gap - 0.72 * s) / 2;
  return [y1, y1 + gap + 0.72 * s];
}

function drawFrameSplit(ctx, preset, lines, theme, bar, u, border) {
  const padX = border ? border + 1.6 * u : 4.2 * u;
  const cy = bar.y + bar.h / 2;
  const avail = bar.w - 2 * padX;

  const layout = (k) => {
    const T = 3.0 * u * k,
      S = 2.0 * u * k,
      C = 2.5 * u * k,
      gap = 1.1 * u * k,
      bh = (preset.badge.type === "circle" ? 5.6 : preset.badge.type === "box" ? 4.4 : 4.8) * u * k,
      sepGap = 1.8 * u * k;
    const left = { title: titleRuns(preset, T, theme.title), sub: textRun(lines.subtitle, S, theme.sub) };
    const right = { top: textRun(lines.cam, C, theme.title, 600), sub: textRun(lines.date, S, theme.sub) };
    if (!right.top.length && right.sub.length) {
      right.top = textRun(lines.date, C, theme.title, 600);
      right.sub = [];
    }
    const leftW = Math.max(measureRuns(ctx, left.title), measureRuns(ctx, left.sub));
    const textW = Math.max(measureRuns(ctx, right.top), measureRuns(ctx, right.sub));
    const bw = badgeWidth(ctx, preset.badge, bh);
    const rightW = textW + (bw ? bw + (textW ? 2 * sepGap : 0) : 0);
    return { T, S, C, gap, bh, sepGap, left, right, leftW, textW, bw, rightW };
  };

  let L = layout(1);
  const need = L.leftW + L.rightW + 3 * u;
  if (need > avail) L = layout(Math.max(0.35, avail / need));

  // Links: Hersteller + Modell, darunter optionale Unterzeile
  if (L.left.sub.length) {
    const [y1, y2] = twoLineBaselines(cy, L.T, L.S, L.gap);
    drawRuns(ctx, L.left.title, padX, y1);
    drawRuns(ctx, L.left.sub, padX, y2);
  } else {
    drawRuns(ctx, L.left.title, padX, cy + 0.36 * L.T);
  }

  // Rechts: Logo | Kameradaten + Datum
  const xRight = bar.w - padX;
  const textX = xRight - L.textW;
  if (L.right.sub.length) {
    const [y1, y2] = twoLineBaselines(cy, L.C, L.S, L.gap);
    drawRuns(ctx, L.right.top, textX, y1);
    drawRuns(ctx, L.right.sub, textX, y2);
  } else if (L.right.top.length) {
    drawRuns(ctx, L.right.top, textX, cy + 0.36 * L.C);
  }
  if (L.bw) {
    let bx = xRight - L.bw;
    if (L.textW) {
      const sepX = textX - L.sepGap;
      ctx.fillStyle = theme.sep;
      const sh = Math.max(L.bh, 5.4 * u) * 1.0;
      ctx.fillRect(sepX - 0.08 * u, cy - sh / 2, Math.max(1, 0.16 * u), sh);
      bx = sepX - L.sepGap - L.bw;
    }
    drawBadge(ctx, preset.badge, bx, cy, L.bh, theme.title);
  }
}

function drawFrameCenter(ctx, preset, lines, theme, bar, u) {
  const avail = bar.w - 8 * u;
  const layout = (k) => {
    const T = 3.2 * u * k,
      S = 2.0 * u * k,
      bh = (preset.badge.type === "circle" ? 5 : 3.9) * u * k,
      g = 1.8 * u * k;
    const title = titleRuns(preset, T, theme.title);
    const info = [lines.subtitle, lines.cam, lines.date].filter(Boolean).join("     ");
    const sub = textRun(info, S, theme.sub);
    const tw = measureRuns(ctx, title);
    const bw = badgeWidth(ctx, preset.badge, bh);
    const row1 = tw + (bw ? bw + (tw ? 2 * g + 0.16 * u : 0) : 0);
    const row2 = measureRuns(ctx, sub);
    return { T, S, bh, g, title, sub, tw, bw, row1, row2 };
  };
  let L = layout(1);
  const widest = Math.max(L.row1, L.row2);
  if (widest > avail) L = layout(Math.max(0.35, avail / widest));

  const hasSub = L.sub.length > 0;
  const cy1 = hasSub ? bar.y + bar.h * 0.4 : bar.y + bar.h / 2;
  let x = (bar.w - L.row1) / 2;
  if (L.bw) {
    drawBadge(ctx, preset.badge, x, cy1, L.bh, theme.title);
    x += L.bw;
    if (L.tw) {
      x += L.g;
      ctx.fillStyle = theme.sep;
      const sh = Math.max(L.bh, L.T * 1.1);
      ctx.fillRect(x, cy1 - sh / 2, Math.max(1, 0.16 * u), sh);
      x += 0.16 * u + L.g;
    }
  }
  drawRuns(ctx, L.title, x, cy1 + 0.36 * L.T);
  if (hasSub) drawRuns(ctx, L.sub, (bar.w - L.row2) / 2, bar.y + bar.h * 0.72 + 0.36 * L.S);
}

function drawOverlay(ctx, preset, lines, w, h, u) {
  // "Hell" = weiße Schrift mit Schatten, "Dunkel" = schwarze Schrift.
  const light = preset.theme !== "dark";
  const color = light ? "#ffffff" : "#111111";
  const sub = light ? "rgba(255,255,255,0.86)" : "rgba(0,0,0,0.72)";
  const margin = 4.2 * u;
  const avail = w - 2 * margin;

  const layout = (k) => {
    const T = 3.1 * u * k,
      S = 1.9 * u * k,
      gap = 1.0 * u * k,
      bh = (preset.badge.type === "circle" ? 5.6 : 4.4) * u * k,
      g = 1.6 * u * k;
    const title = titleRuns(preset, T, color);
    const info = [lines.subtitle, lines.cam, lines.date].filter(Boolean).join("   ");
    const subRuns = textRun(info, S, sub);
    const tw = Math.max(measureRuns(ctx, title), measureRuns(ctx, subRuns));
    const bw = badgeWidth(ctx, preset.badge, bh);
    const total = tw + (bw ? bw + 2 * g : 0);
    return { T, S, gap, bh, g, title, subRuns, tw, bw, total };
  };
  let L = layout(1);
  if (L.total > avail) L = layout(Math.max(0.35, avail / L.total));

  ctx.save();
  if (light) {
    ctx.shadowColor = "rgba(0,0,0,0.45)";
    ctx.shadowBlur = 0.8 * u;
    ctx.shadowOffsetY = 0.12 * u;
  }
  const blockH = L.subRuns.length ? 0.72 * L.T + L.gap + 0.72 * L.S : 0.72 * L.T;
  const cy = h - margin - blockH / 2;
  let x = margin;
  if (L.bw) {
    drawBadge(ctx, preset.badge, x, cy, L.bh, color);
    x += L.bw + L.g;
    ctx.fillStyle = sub;
    const sh = Math.max(L.bh, blockH * 1.1);
    ctx.fillRect(x - 0.08 * u, cy - sh / 2, Math.max(1, 0.16 * u), sh);
    x += L.g;
  }
  if (L.subRuns.length) {
    const [y1, y2] = twoLineBaselines(cy, L.T, L.S, L.gap);
    drawRuns(ctx, L.title, x, y1);
    drawRuns(ctx, L.subRuns, x, y2);
  } else {
    drawRuns(ctx, L.title, x, cy + 0.36 * L.T);
  }
  ctx.restore();
}
