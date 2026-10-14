import { parseExif, exifDate, formatCameraData, copyExif } from "./exif.js";
import { allPresets, findPreset, loadState, saveState, presetLabel, newId, withDefaults, BUILT_IN } from "./presets.js";
import { renderWatermark, loadBadgeImage, watermarkArea } from "./render.js";

const $ = (sel) => document.querySelector(sel);
const state = loadState();

let current = null; // aktuelles Foto
let lastExport = null; // zuletzt erzeugte Datei für das aktuelle Foto
let demo = null; // Beispielbild für die Vorschau
let demoThumb = null; // Beispielbild (hochkant) für die Vorlagen-Kacheln
const thumbScratch = document.createElement("canvas");
const results = []; // fertige Fotos dieser Sitzung

/* ======================= Hilfsfunktionen ======================= */

let toastTimer;
function toast(msg, ms = 2800) {
  const el = $("#toast");
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), ms);
}

function setBusy(on, text = "Wird bearbeitet …") {
  $("#busy").hidden = !on;
  $("#busyText").textContent = text;
}

function persist() {
  if (!saveState(state)) toast("Konnte Einstellungen nicht speichern (Speicher voll?)");
}

function selectedPreset() {
  return findPreset(state, state.selectedId);
}

async function ensureBadge(preset) {
  if (preset.badge.type === "image" && preset.badge.src) await loadBadgeImage(preset.badge.src);
}

function slug(text) {
  return (
    text
      .normalize("NFKD")
      .replace(/[^\w\s-]/g, "")
      .trim()
      .replace(/\s+/g, "-") || "watermark"
  );
}

function baseName(name) {
  return (name || "Foto").replace(/\.[^.]+$/, "") || "Foto";
}

function scaledSize(w, h, maxPixels) {
  const s = Math.min(1, Math.sqrt(maxPixels / (w * h)));
  return [Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))];
}

function canvasToBlob(canvas, type, quality) {
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Das Bild ist zu groß für dieses Gerät."))), type, quality),
  );
}

function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function shareItems(items) {
  const files = items.map((i) => new File([i.blob], i.name, { type: "image/jpeg" }));
  if (!navigator.canShare || !navigator.canShare({ files })) {
    toast("Teilen geht in diesem Browser nicht – nutze „Speichern“.");
    return;
  }
  try {
    await navigator.share({ files });
  } catch (e) {
    if (e.name === "NotAllowedError") toast("Bitte noch einmal auf „Teilen“ tippen.");
    else if (e.name !== "AbortError") toast("Teilen hat nicht geklappt.");
  }
}

/* ======================= Beispielbild ======================= */

function drawDemo(w, h) {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const g = c.getContext("2d");
  const horizon = h * 0.64;

  const sky = g.createLinearGradient(0, 0, 0, horizon);
  sky.addColorStop(0, "#1c2a5e");
  sky.addColorStop(0.45, "#b9557c");
  sky.addColorStop(0.82, "#f3a45c");
  sky.addColorStop(1, "#ffd9a0");
  g.fillStyle = sky;
  g.fillRect(0, 0, w, horizon + 2);

  const sx = w * 0.63,
    sy = horizon - h * 0.07,
    sr = Math.min(w, h) * 0.085;
  const glow = g.createRadialGradient(sx, sy, sr * 0.2, sx, sy, sr * 5);
  glow.addColorStop(0, "rgba(255,240,200,0.9)");
  glow.addColorStop(1, "rgba(255,200,140,0)");
  g.fillStyle = glow;
  g.fillRect(0, 0, w, horizon);
  g.fillStyle = "#fff4d6";
  g.beginPath();
  g.arc(sx, sy, sr, 0, Math.PI * 2);
  g.fill();

  const ridge = (base, amp, color, f1, f2, ph) => {
    g.fillStyle = color;
    g.beginPath();
    g.moveTo(0, horizon);
    for (let x = 0; x <= w; x += w / 160) {
      const t = x / w;
      const y = base - amp * (0.55 * Math.sin(t * f1 + ph) + 0.35 * Math.sin(t * f2 + ph * 2.1) + 0.1 * Math.sin(t * 37 + ph));
      g.lineTo(x, y);
    }
    g.lineTo(w, horizon);
    g.closePath();
    g.fill();
  };
  ridge(horizon - h * 0.14, h * 0.1, "#7b4a78", 5.1, 11.3, 0.4);
  ridge(horizon - h * 0.06, h * 0.08, "#4b2c55", 7.3, 15.7, 1.7);
  ridge(horizon, h * 0.05, "#2a1a36", 9.1, 21.3, 2.9);

  const lake = g.createLinearGradient(0, horizon, 0, h);
  lake.addColorStop(0, "#e59a6a");
  lake.addColorStop(0.35, "#7d4a6e");
  lake.addColorStop(1, "#1b1730");
  g.fillStyle = lake;
  g.fillRect(0, horizon, w, h - horizon);
  g.fillStyle = "rgba(255,236,200,0.55)";
  for (let i = 0; i < 9; i++) {
    const y = horizon + (h - horizon) * 0.55 * (i / 9) ** 1.6 + 2;
    const len = sr * (2.2 - i * 0.12);
    g.fillRect(sx - len / 2, y, len, Math.max(1, h * 0.003));
  }
  return c;
}

function makeDemo(w, h) {
  return { c: drawDemo(w, h), pw: w, ph: h, info: { cameraData: "", date: new Date() } };
}

/* ======================= Fotos laden & exportieren ======================= */

async function decodeImage(file) {
  try {
    return await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    // Fallback für ältere Browser
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    const c = document.createElement("canvas");
    c.width = img.naturalWidth;
    c.height = img.naturalHeight;
    c.getContext("2d").drawImage(img, 0, 0);
    return c;
  } finally {
    URL.revokeObjectURL(url);
  }
}

async function loadPhoto(file) {
  const buf = await file.arrayBuffer();
  const exif = parseExif(buf);
  let source;
  try {
    source = await decodeImage(file);
  } catch {
    if (/hei[cf]/i.test(file.type) || /\.hei[cf]$/i.test(file.name)) {
      throw new Error("HEIC-Fotos gehen hier nicht. Stell in der Samsung-Kamera das Format auf JPEG um.");
    }
    throw new Error(`„${file.name}“ kann nicht geöffnet werden.`);
  }
  const d = exif?.data || {};
  const date = exifDate(d.dateTimeOriginal || d.dateTime) || new Date(file.lastModified || Date.now());
  const photo = {
    file,
    name: baseName(file.name),
    source,
    w: source.width,
    h: source.height,
    exif,
    info: { cameraData: formatCameraData(d), date },
  };
  // verkleinerte Kopie für eine schnelle Live-Vorschau
  const [pw, ph] = scaledSize(photo.w, photo.h, 1600 * 1200);
  const c = document.createElement("canvas");
  c.width = pw;
  c.height = ph;
  const ctx = c.getContext("2d");
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(source, 0, 0, pw, ph);
  photo.preview = { c, pw, ph, info: photo.info };
  return photo;
}

function setCurrent(photo) {
  if (current && current !== photo && current.source.close) current.source.close();
  current = photo;
  lastExport = null;
}

function exportKey(photo, preset) {
  return JSON.stringify([photo.name, photo.w, preset, state.settings]);
}

async function exportPhoto(photo, preset = selectedPreset()) {
  await ensureBadge(preset);
  const [w, h] = scaledSize(photo.w, photo.h, state.settings.maxMegapixels * 1e6);
  const canvas = renderWatermark(photo.source, w, h, preset, photo.info);
  try {
    let blob = await canvasToBlob(canvas, "image/jpeg", state.settings.quality);
    if (state.settings.keepExif && photo.exif) blob = await copyExif(photo.exif, blob, canvas.width, canvas.height);
    return { blob, name: `${photo.name}_${slug(presetLabel(preset))}.jpg` };
  } finally {
    canvas.width = canvas.height = 0; // Speicher freigeben
  }
}

async function exportCurrent() {
  const preset = selectedPreset();
  const key = exportKey(current, preset);
  if (lastExport && lastExport.key === key) return lastExport;
  const out = await exportPhoto(current, preset);
  lastExport = { ...out, key };
  return lastExport;
}

async function thumbnailFor(photo, preset) {
  const [w, h] = scaledSize(photo.preview.pw, photo.preview.ph, 320 * 240);
  const canvas = renderWatermark(photo.preview.c, w, h, preset, photo.info);
  const blob = await canvasToBlob(canvas, "image/jpeg", 0.8);
  return URL.createObjectURL(blob);
}

/* ======================= Fotos verarbeiten ======================= */

async function handleFiles(fileList) {
  const files = [...fileList].filter((f) => f && (f.type.startsWith("image/") || /\.(jpe?g|png|webp|avif|hei[cf])$/i.test(f.name)));
  if (!files.length) return;
  const on = state.settings.watermarkOn;
  const autoSave = on && state.settings.autoSave;
  const batch = files.length > 1;
  let saved = 0;
  const errors = [];

  for (let i = 0; i < files.length; i++) {
    setBusy(true, batch ? `Foto ${i + 1} von ${files.length} …` : "Wird bearbeitet …");
    try {
      const photo = await loadPhoto(files[i]);
      setCurrent(photo);
      await renderPreview();
      if (on && (autoSave || batch)) {
        const preset = selectedPreset();
        const out = await exportPhoto(photo, preset);
        lastExport = { ...out, key: exportKey(photo, preset) };
        if (autoSave) {
          download(out.blob, out.name);
          saved++;
        }
        addResult({ ...out, thumb: await thumbnailFor(photo, preset), saved: autoSave });
      }
    } catch (e) {
      console.error(e);
      errors.push(e.message || String(e));
    }
  }
  setBusy(false);
  updateButtons();

  if (errors.length) toast(errors[0], 5000);
  else if (saved > 1) toast(`${saved} Fotos mit Watermark gespeichert ✓`);
  else if (saved === 1) toast("Mit Watermark gespeichert ✓ (Ordner „Download“)");
  else if (!on) toast("Watermark ist aus – schalte es oben ein.");
}

function addResult(item) {
  results.unshift(item);
  renderResults();
}

function renderResults() {
  const list = $("#resultList");
  list.replaceChildren();
  $("#results").hidden = results.length === 0;
  results.forEach((r) => {
    const li = document.createElement("li");
    const img = document.createElement("img");
    img.src = r.thumb;
    img.alt = r.name;
    img.loading = "lazy";
    const label = document.createElement("span");
    label.className = "state";
    label.textContent = r.saved ? "✓ gespeichert" : "Tippen zum Speichern";
    const btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("aria-label", `${r.name} speichern`);
    btn.addEventListener("click", () => {
      download(r.blob, r.name);
      r.saved = true;
      label.textContent = "✓ gespeichert";
    });
    li.append(img, label, btn);
    list.append(li);
  });
}

/* ======================= Vorschau ======================= */

let renderToken = 0;
async function renderPreview() {
  const token = ++renderToken;
  const preset = selectedPreset();
  await ensureBadge(preset);
  if (token !== renderToken) return;
  const src = current ? current.preview : demo;
  const canvas = $("#previewCanvas");
  if (state.settings.watermarkOn) {
    renderWatermark(src.c, src.pw, src.ph, preset, src.info, canvas);
  } else {
    canvas.width = src.pw;
    canvas.height = src.ph;
    canvas.getContext("2d").drawImage(src.c, 0, 0);
  }
  $("#stageNote").hidden = !!current;
  $("#stageOff").hidden = state.settings.watermarkOn;
  updateButtons();
}

function updateButtons() {
  const ready = !!current && state.settings.watermarkOn;
  $("#saveBtn").disabled = !ready;
  $("#shareBtn").disabled = !ready;
}

/* ======================= Vorlagen-Auswahl ======================= */

function presetTag(p) {
  if (!p.builtIn) return "Eigene";
  return state.overrides[p.id] ? "Angepasst" : "";
}

// Die Kachel zeigt nur den unteren Bildausschnitt, damit das Watermark gut lesbar ist.
async function drawPresetThumb(canvas, preset) {
  await ensureBadge(preset);
  const { c, pw, ph, info } = demoThumb;
  renderWatermark(c, pw, ph, preset, info, thumbScratch);
  const a = watermarkArea(preset, pw, ph);
  canvas.width = a.w;
  canvas.height = a.h;
  canvas.getContext("2d").drawImage(thumbScratch, a.x, a.y, a.w, a.h, 0, 0, a.w, a.h);
}

function renderPresetGrid() {
  const grid = $("#presetGrid");
  grid.replaceChildren();
  for (const p of allPresets(state)) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "preset";
    btn.setAttribute("role", "option");
    btn.setAttribute("aria-selected", String(p.id === state.selectedId));
    btn.dataset.id = p.id;
    const canvas = document.createElement("canvas");
    const name = document.createElement("span");
    name.className = "preset-name";
    name.textContent = presetLabel(p);
    btn.append(canvas, name);
    const tag = presetTag(p);
    if (tag) {
      const t = document.createElement("span");
      t.className = "preset-tag";
      t.textContent = tag;
      btn.append(t);
    }
    btn.addEventListener("click", () => selectPreset(p.id));
    btn.addEventListener("dblclick", () => openEditor(findPreset(state, p.id)));
    grid.append(btn);
    drawPresetThumb(canvas, p);
  }
}

function selectPreset(id) {
  state.selectedId = id;
  persist();
  document.querySelectorAll(".preset").forEach((el) => el.setAttribute("aria-selected", String(el.dataset.id === id)));
  lastExport = null;
  renderPreview();
}

/* ======================= Editor ======================= */

const editor = $("#editor");
const form = $("#editorForm");
let draft = null;
let draftIsNew = false;

function fillForm(p) {
  const f = form.elements;
  f.brand.value = p.brand || "";
  f.brandStyle.value = p.brandStyle;
  f.model.value = p.model || "";
  f.prefix.value = p.prefix || "";
  f.style.value = p.style;
  f.theme.value = p.theme;
  f.border.checked = !!p.border;
  f.showDate.checked = !!p.showDate;
  f.badgeType.value = p.badge.type || "none";
  f.badgeText.value = p.badge.text || "";
  f.badgeFont.value = p.badge.font || "";
  f.badgeBg.value = /^#[0-9a-f]{6}$/i.test(p.badge.bg || "") ? p.badge.bg : "#1d4e9e";
  f.badgeFg.value = /^#[0-9a-f]{6}$/i.test(p.badge.fg || "") ? p.badge.fg : "#ffffff";
  f.exifMode.value = p.exifMode;
  f.exifText.value = p.exifText || "";
  f.subtitle.value = p.subtitle || "";
  syncEditorVisibility();
}

function readForm() {
  const f = form.elements;
  const type = f.badgeType.value;
  const badge = { type };
  if (type === "box" || type === "circle" || type === "text") {
    badge.text = f.badgeText.value.trim();
    if (f.badgeFont.value && type !== "box") badge.font = f.badgeFont.value;
  }
  if (type === "box" || type === "circle") {
    badge.bg = f.badgeBg.value;
    badge.fg = f.badgeFg.value;
  }
  if (type === "image") badge.src = draft.badge.src || "";
  return withDefaults({
    id: draft.id,
    brand: f.brand.value.trim(),
    brandStyle: f.brandStyle.value,
    model: f.model.value.trim(),
    prefix: f.prefix.value.trim(),
    style: f.style.value,
    theme: f.theme.value,
    border: f.border.checked,
    showDate: f.showDate.checked,
    badge,
    exifMode: f.exifMode.value,
    exifText: f.exifText.value.trim(),
    subtitle: f.subtitle.value.trim(),
    builtIn: draft.builtIn,
  });
}

function syncEditorVisibility() {
  const type = form.elements.badgeType.value;
  form.querySelectorAll("[data-badge]").forEach((el) => {
    el.hidden = !el.dataset.badge.split(" ").includes(type);
  });
  const mode = form.elements.exifMode.value;
  form.elements.exifText.closest(".field").hidden = mode === "off";
  $("#exifTextLabel").textContent = mode === "custom" ? "Kameradaten-Text" : "Ersatz, falls das Foto keine Daten hat";
  const borderOk = form.elements.style.value !== "overlay";
  form.elements.border.closest("label").hidden = !borderOk;
}

// Zeigt nur den Watermark-Ausschnitt, damit die Vorschau beim Bearbeiten klein bleibt.
async function renderEditorPreview() {
  const p = readForm();
  await ensureBadge(p);
  const src = current ? current.preview : demoThumb;
  renderWatermark(src.c, src.pw, src.ph, p, src.info, thumbScratch);
  const a = watermarkArea(p, src.pw, src.ph);
  const canvas = $("#editorPreview");
  canvas.width = a.w;
  canvas.height = a.h;
  canvas.getContext("2d").drawImage(thumbScratch, a.x, a.y, a.w, a.h, 0, 0, a.w, a.h);
}

function openEditor(preset, isNew = false) {
  draft = structuredClone(preset);
  draftIsNew = isNew;
  $("#editorTitle").textContent = isNew ? "Eigene Marke hinzufügen" : `${presetLabel(preset)} bearbeiten`;
  const del = $("#deleteBtn");
  if (isNew) del.hidden = true;
  else if (preset.builtIn) {
    del.hidden = !state.overrides[preset.id];
    del.textContent = "Zurücksetzen";
  } else {
    del.hidden = false;
    del.textContent = "Löschen";
  }
  $("#duplicateBtn").hidden = isNew;
  fillForm(draft);
  editor.returnValue = "";
  editor.showModal();
  renderEditorPreview();
}

function saveDraft() {
  const p = readForm();
  const { builtIn, ...data } = p;
  if (draftIsNew) {
    state.custom.unshift(data);
  } else if (builtIn) {
    const { id, ...rest } = data;
    state.overrides[id] = rest;
  } else {
    const i = state.custom.findIndex((c) => c.id === p.id);
    if (i >= 0) state.custom[i] = data;
    else state.custom.unshift(data);
  }
  state.selectedId = p.id;
  persist();
  renderPresetGrid();
  lastExport = null;
  renderPreview();
  toast(`„${presetLabel(p)}“ gespeichert`);
}

function newCustomPreset() {
  return withDefaults({
    id: newId(),
    brand: "",
    model: "",
    brandStyle: "bold",
    style: "frame-split",
    theme: "light",
    badge: { type: "none" },
    exifText: "24mm  f/1.8  1/100s  ISO100",
    builtIn: false,
  });
}

async function readLogo(file) {
  const img = await decodeImage(file);
  const max = 600;
  const s = Math.min(1, max / Math.max(img.width, img.height));
  const c = document.createElement("canvas");
  c.width = Math.round(img.width * s);
  c.height = Math.round(img.height * s);
  c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
  return c.toDataURL("image/png");
}

/* ======================= Vorlagen exportieren / importieren ======================= */

function exportPresets() {
  const data = { app: "camwater", version: 1, custom: state.custom, overrides: state.overrides };
  download(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }), "camwater-vorlagen.json");
}

async function importPresets(file) {
  try {
    const data = JSON.parse(await file.text());
    const custom = Array.isArray(data.custom) ? data.custom : Array.isArray(data) ? data : [];
    const ids = new Set(state.custom.map((c) => c.id));
    let added = 0;
    for (const p of custom) {
      if (!p || typeof p !== "object") continue;
      const copy = { ...p };
      if (!copy.id || ids.has(copy.id) || BUILT_IN.some((b) => b.id === copy.id)) copy.id = newId();
      ids.add(copy.id);
      state.custom.push(copy);
      added++;
    }
    if (data.overrides && typeof data.overrides === "object") {
      for (const [id, o] of Object.entries(data.overrides)) {
        if (BUILT_IN.some((b) => b.id === id) && o && typeof o === "object") state.overrides[id] = o;
      }
    }
    persist();
    renderPresetGrid();
    renderPreview();
    toast(`${added} Vorlage(n) importiert`);
  } catch {
    toast("Diese Datei ist keine gültige Camwater-Vorlage.");
  }
}

/* ======================= Geteilte Fotos (Teilen → Camwater) ======================= */

async function takeSharedFiles() {
  const params = new URLSearchParams(location.search);
  if (!params.has("shared") || !("caches" in window)) return;
  history.replaceState(null, "", location.pathname);
  try {
    const cache = await caches.open("camwater-share");
    const keys = await cache.keys();
    const files = [];
    for (const req of keys) {
      const res = await cache.match(req);
      const blob = await res.blob();
      const name = decodeURIComponent(res.headers.get("x-filename") || "Foto.jpg");
      files.push(new File([blob], name, { type: blob.type || "image/jpeg", lastModified: Number(res.headers.get("x-modified")) || Date.now() }));
      await cache.delete(req);
    }
    if (files.length) await handleFiles(files);
  } catch (e) {
    console.error(e);
    toast("Geteilte Fotos konnten nicht geladen werden.");
  }
}

/* ======================= Einstellungen & Events ======================= */

function syncSettingsUI() {
  const s = state.settings;
  $("#wmToggle").checked = s.watermarkOn;
  $("#wmState").textContent = s.watermarkOn ? "AN" : "AUS";
  $("#setAutoSave").checked = s.autoSave;
  $("#setKeepExif").checked = s.keepExif;
  $("#setQuality").value = s.quality;
  $("#qualityOut").textContent = `${Math.round(s.quality * 100)} %`;
  const mp = $("#setMaxMp");
  mp.value = String(s.maxMegapixels);
  if (mp.value !== String(s.maxMegapixels)) mp.value = "50";
}

function bindEvents() {
  $("#wmToggle").addEventListener("change", (e) => {
    state.settings.watermarkOn = e.target.checked;
    persist();
    syncSettingsUI();
    renderPreview();
    toast(e.target.checked ? "Watermark ist AN – neue Fotos bekommen dein Watermark" : "Watermark ist AUS");
  });

  for (const id of ["#cameraInput", "#galleryInput"]) {
    $(id).addEventListener("change", async (e) => {
      const files = [...e.target.files];
      e.target.value = "";
      await handleFiles(files);
    });
  }

  $("#saveBtn").addEventListener("click", async () => {
    if (!current) return;
    setBusy(true, "Wird gespeichert …");
    try {
      const out = await exportCurrent();
      download(out.blob, out.name);
      toast("Gespeichert ✓ (Ordner „Download“)");
    } catch (e) {
      toast(e.message || "Speichern hat nicht geklappt.");
    } finally {
      setBusy(false);
    }
  });

  $("#shareBtn").addEventListener("click", async () => {
    if (!current) return;
    setBusy(true, "Wird vorbereitet …");
    let out;
    try {
      out = await exportCurrent();
    } catch (e) {
      toast(e.message || "Das hat nicht geklappt.");
      return;
    } finally {
      setBusy(false);
    }
    await shareItems([out]);
  });

  $("#saveAllBtn").addEventListener("click", () => {
    results.forEach((r) => download(r.blob, r.name));
    results.forEach((r) => (r.saved = true));
    renderResults();
  });
  $("#shareAllBtn").addEventListener("click", () => shareItems(results));

  $("#editBtn").addEventListener("click", () => openEditor(selectedPreset()));
  $("#addBtn").addEventListener("click", () => openEditor(newCustomPreset(), true));

  form.addEventListener("input", () => {
    syncEditorVisibility();
    renderEditorPreview();
  });
  form.addEventListener("change", () => {
    syncEditorVisibility();
    renderEditorPreview();
  });
  editor.querySelector(".icon-btn").addEventListener("click", (e) => {
    e.preventDefault();
    editor.close("cancel");
  });
  editor.addEventListener("close", () => {
    if (editor.returnValue === "save") saveDraft();
  });

  $("#deleteBtn").addEventListener("click", () => {
    if (draft.builtIn) {
      delete state.overrides[draft.id];
      toast(`„${presetLabel(draft)}“ zurückgesetzt`);
    } else {
      if (!confirm(`„${presetLabel(draft)}“ löschen?`)) return;
      state.custom = state.custom.filter((c) => c.id !== draft.id);
      if (state.selectedId === draft.id) state.selectedId = BUILT_IN[0].id;
      toast("Vorlage gelöscht");
    }
    persist();
    editor.close("cancel");
    renderPresetGrid();
    lastExport = null;
    renderPreview();
  });

  $("#duplicateBtn").addEventListener("click", () => {
    const p = readForm();
    editor.close("cancel");
    openEditor({ ...p, id: newId(), builtIn: false }, true);
  });

  $("#logoInput").addEventListener("change", async (e) => {
    const file = e.target.files[0];
    e.target.value = "";
    if (!file) return;
    try {
      draft.badge = { ...draft.badge, type: "image", src: await readLogo(file) };
      form.elements.badgeType.value = "image";
      syncEditorVisibility();
      renderEditorPreview();
    } catch {
      toast("Dieses Logo-Bild kann nicht geöffnet werden.");
    }
  });

  $("#setAutoSave").addEventListener("change", (e) => {
    state.settings.autoSave = e.target.checked;
    persist();
  });
  $("#setKeepExif").addEventListener("change", (e) => {
    state.settings.keepExif = e.target.checked;
    lastExport = null;
    persist();
  });
  $("#setQuality").addEventListener("input", (e) => {
    state.settings.quality = Number(e.target.value);
    $("#qualityOut").textContent = `${Math.round(state.settings.quality * 100)} %`;
    lastExport = null;
    persist();
  });
  $("#setMaxMp").addEventListener("change", (e) => {
    state.settings.maxMegapixels = Number(e.target.value);
    lastExport = null;
    persist();
  });

  $("#exportBtn").addEventListener("click", exportPresets);
  $("#importInput").addEventListener("change", (e) => {
    const file = e.target.files[0];
    e.target.value = "";
    if (file) importPresets(file);
  });

  let installEvent = null;
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    installEvent = e;
    $("#installBtn").hidden = false;
  });
  $("#installBtn").addEventListener("click", async () => {
    if (!installEvent) return;
    installEvent.prompt();
    await installEvent.userChoice.catch(() => {});
    installEvent = null;
    $("#installBtn").hidden = true;
  });

  // Fotos per Drag & Drop (am Computer)
  document.addEventListener("dragover", (e) => e.preventDefault());
  document.addEventListener("drop", (e) => {
    e.preventDefault();
    if (e.dataTransfer?.files?.length) handleFiles(e.dataTransfer.files);
  });
}

/* ======================= Start ======================= */

function init() {
  demo = makeDemo(1600, 1200);
  demoThumb = makeDemo(900, 1200);
  if (!allPresets(state).some((p) => p.id === state.selectedId)) state.selectedId = BUILT_IN[0].id;
  syncSettingsUI();
  bindEvents();
  renderPresetGrid();
  renderPreview();
  takeSharedFiles();

  if ("serviceWorker" in navigator && location.protocol !== "file:") {
    navigator.serviceWorker.register("sw.js").catch(() => {});
  }
}

init();
