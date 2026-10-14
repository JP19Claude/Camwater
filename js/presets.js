// Mitgelieferte Watermark-Vorlagen und Speicherung eigener Vorlagen.
// Logos werden als einfache Schrift/Formen gezeichnet (keine Original-Logodateien).
// Eigene Logos kann man im Editor als Bild hochladen.

/*
 Aufbau einer Vorlage:
  id, brand, model, prefix ("Shot on" usw.)
  brandStyle: "plain" | "bold" | "wide" | "italic" | "serif" | "round"
  style: "frame-split" (Leiste unten, Logo rechts) | "frame-center" (Leiste unten, mittig) | "overlay" (im Bild)
  theme: "light" | "dark"   (bei "overlay": helle oder dunkle Schrift)
  border: Rand rundherum (nur bei Leiste)
  badge: { type: "none" | "box" | "circle" | "text" | "image", text, bg, fg, font, src }
  showDate: Aufnahmedatum anzeigen
  subtitle: eigene Unterzeile (z.B. dein Name), optional
  exifMode: "photo" | "custom" | "off"
  exifText: Ersatz-/eigene Kameradaten
*/

export const BUILT_IN = [
  {
    id: "vivo-x300-ultra",
    brand: "vivo",
    model: "X300 Ultra",
    brandStyle: "round",
    style: "frame-split",
    theme: "light",
    badge: { type: "box", text: "ZEISS", bg: "#1d4e9e", fg: "#ffffff" },
    exifText: "35mm  f/1.85  1/100s  ISO100",
  },
  {
    id: "vivo-x300-pro",
    brand: "vivo",
    model: "X300 Pro",
    brandStyle: "round",
    style: "frame-center",
    theme: "light",
    badge: { type: "box", text: "ZEISS", bg: "#1d4e9e", fg: "#ffffff" },
    exifText: "24mm  f/1.57  1/100s  ISO100",
  },
  {
    id: "xiaomi-17-ultra",
    brand: "Xiaomi",
    model: "17 Ultra",
    brandStyle: "bold",
    style: "frame-split",
    theme: "light",
    badge: { type: "circle", text: "Leica", bg: "#e20612", fg: "#ffffff", font: "script" },
    exifText: "23mm  f/1.63  1/120s  ISO50",
  },
  {
    id: "oppo-find-x9-ultra",
    brand: "OPPO",
    model: "Find X9 Ultra",
    brandStyle: "bold",
    style: "frame-center",
    theme: "light",
    badge: { type: "text", text: "HASSELBLAD", font: "wide" },
    exifText: "23mm  f/1.8  1/100s  ISO100",
  },
  {
    id: "huawei-pura-80-ultra",
    brand: "HUAWEI",
    model: "Pura 80 Ultra",
    brandStyle: "wide",
    style: "frame-split",
    theme: "light",
    badge: { type: "text", text: "XMAGE", font: "wide" },
    exifText: "23mm  f/1.6  1/100s  ISO50",
  },
  {
    id: "honor-magic8-pro",
    brand: "HONOR",
    model: "Magic8 Pro",
    brandStyle: "wide",
    style: "overlay",
    theme: "light",
    prefix: "",
    badge: { type: "none" },
    exifText: "23mm  f/1.6  1/100s  ISO100",
  },
  {
    id: "samsung-s26-ultra",
    brand: "Galaxy",
    model: "S26 Ultra",
    brandStyle: "bold",
    style: "overlay",
    theme: "light",
    prefix: "Shot with my",
    badge: { type: "none" },
    exifText: "24mm  f/1.7  1/100s  ISO50",
  },
  {
    id: "iphone-17-pro-max",
    brand: "iPhone",
    model: "17 Pro Max",
    brandStyle: "plain",
    style: "overlay",
    theme: "light",
    prefix: "Shot on",
    badge: { type: "none" },
    showDate: false,
    exifMode: "off",
    exifText: "24mm  f/1.78  1/120s  ISO80",
  },
  {
    id: "pixel-10-pro",
    brand: "Google",
    model: "Pixel 10 Pro",
    brandStyle: "plain",
    style: "frame-split",
    theme: "dark",
    badge: { type: "none" },
    exifText: "25mm  f/1.68  1/120s  ISO50",
  },
  {
    id: "oneplus-15",
    brand: "OnePlus",
    model: "15",
    brandStyle: "bold",
    style: "frame-split",
    theme: "dark",
    badge: { type: "box", text: "DetailMax", bg: "#eb0029", fg: "#ffffff" },
    exifText: "24mm  f/1.8  1/100s  ISO100",
  },
  {
    id: "leica-m11",
    brand: "Leica",
    model: "M11",
    brandStyle: "serif",
    style: "frame-split",
    theme: "light",
    border: true,
    badge: { type: "circle", text: "Leica", bg: "#e20612", fg: "#ffffff", font: "script" },
    exifText: "35mm  f/2  1/250s  ISO64",
  },
  {
    id: "sony-a7-iv",
    brand: "SONY",
    model: "α7 IV",
    brandStyle: "wide",
    style: "frame-split",
    theme: "dark",
    badge: { type: "text", text: "α", font: "serif" },
    exifText: "50mm  f/1.8  1/200s  ISO100",
  },
  {
    id: "fujifilm-x100vi",
    brand: "FUJIFILM",
    model: "X100VI",
    brandStyle: "wide",
    style: "frame-center",
    theme: "light",
    border: true,
    badge: { type: "none" },
    exifText: "23mm  f/2  1/500s  ISO125",
  },
];

const DEFAULTS = {
  prefix: "",
  brandStyle: "bold",
  style: "frame-split",
  theme: "light",
  border: false,
  badge: { type: "none" },
  showDate: true,
  subtitle: "",
  exifMode: "photo",
  exifText: "",
};

export function withDefaults(p) {
  return { ...DEFAULTS, ...p, badge: { ...DEFAULTS.badge, ...(p.badge || {}) } };
}

export function presetLabel(p) {
  return [p.brand, p.model].filter(Boolean).join(" ") || "Ohne Namen";
}

const STORAGE_KEY = "camwater.v1";

export function loadState() {
  let saved = {};
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}") || {};
  } catch {
    saved = {};
  }
  return {
    overrides: saved.overrides || {}, // geänderte mitgelieferte Vorlagen
    custom: Array.isArray(saved.custom) ? saved.custom : [], // eigene Vorlagen
    selectedId: saved.selectedId || BUILT_IN[0].id,
    settings: {
      watermarkOn: true,
      autoSave: true,
      keepExif: true,
      quality: 0.95,
      maxMegapixels: 50,
      ...(saved.settings || {}),
    },
  };
}

export function saveState(state) {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        overrides: state.overrides,
        custom: state.custom,
        selectedId: state.selectedId,
        settings: state.settings,
      }),
    );
    return true;
  } catch {
    return false; // z.B. Speicher voll (große Logos) oder privater Modus
  }
}

/** Alle Vorlagen in Anzeigereihenfolge (eigene zuerst). */
export function allPresets(state) {
  const builtIns = BUILT_IN.map((p) => withDefaults({ ...p, ...(state.overrides[p.id] || {}), builtIn: true }));
  const custom = state.custom.map((p) => withDefaults({ ...p, builtIn: false }));
  return [...custom, ...builtIns];
}

export function findPreset(state, id) {
  const list = allPresets(state);
  return list.find((p) => p.id === id) || list[0];
}

export function newId() {
  return "custom-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}
