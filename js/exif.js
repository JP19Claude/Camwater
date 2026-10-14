// Minimaler EXIF-Leser/-Schreiber für JPEG-Dateien.
// Liest die Kameradaten (Brennweite, Blende, Belichtungszeit, ISO, Datum)
// und kann den originalen EXIF-Block in das neue Bild übernehmen.

const TAG = {
  MAKE: 0x010f,
  MODEL: 0x0110,
  ORIENTATION: 0x0112,
  DATETIME: 0x0132,
  EXIF_IFD: 0x8769,
  EXPOSURE_TIME: 0x829a,
  F_NUMBER: 0x829d,
  ISO: 0x8827,
  DATETIME_ORIGINAL: 0x9003,
  FOCAL_LENGTH: 0x920a,
  PIXEL_X: 0xa002,
  PIXEL_Y: 0xa003,
  FOCAL_LENGTH_35MM: 0xa405,
};

const TYPE_SIZE = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8 };

/** Sucht das APP1-Exif-Segment in einem JPEG. */
function findExifSegment(bytes) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  let pos = 2;
  while (pos + 4 <= bytes.length) {
    if (bytes[pos] !== 0xff) return null;
    const marker = bytes[pos + 1];
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      pos += 2;
      continue;
    }
    if (marker === 0xda || marker === 0xd9) return null; // Bilddaten beginnen
    const len = (bytes[pos + 2] << 8) | bytes[pos + 3];
    if (len < 2) return null;
    if (
      marker === 0xe1 &&
      len >= 8 &&
      bytes[pos + 4] === 0x45 && // E
      bytes[pos + 5] === 0x78 && // x
      bytes[pos + 6] === 0x69 && // i
      bytes[pos + 7] === 0x66 && // f
      bytes[pos + 8] === 0 &&
      bytes[pos + 9] === 0
    ) {
      return { start: pos, length: len + 2 };
    }
    pos += 2 + len;
  }
  return null;
}

function readIfd(view, tiff, offset, little) {
  const entries = [];
  if (offset + 2 > view.byteLength - tiff) return { entries, nextOffsetPos: -1 };
  const count = view.getUint16(tiff + offset, little);
  for (let i = 0; i < count; i++) {
    const p = tiff + offset + 2 + i * 12;
    if (p + 12 > view.byteLength) break;
    const tag = view.getUint16(p, little);
    const type = view.getUint16(p + 2, little);
    const num = view.getUint32(p + 4, little);
    const size = (TYPE_SIZE[type] || 1) * num;
    const valuePos = size <= 4 ? p + 8 : tiff + view.getUint32(p + 8, little);
    entries.push({ tag, type, num, valuePos });
  }
  const nextOffsetPos = tiff + offset + 2 + count * 12;
  return { entries, nextOffsetPos };
}

function readValue(view, e, little) {
  const { type, num, valuePos } = e;
  if (valuePos < 0 || valuePos >= view.byteLength) return undefined;
  try {
    switch (type) {
      case 2: {
        let s = "";
        for (let i = 0; i < num && valuePos + i < view.byteLength; i++) {
          const c = view.getUint8(valuePos + i);
          if (c === 0) break;
          s += String.fromCharCode(c);
        }
        return s.trim();
      }
      case 3:
        return view.getUint16(valuePos, little);
      case 4:
        return view.getUint32(valuePos, little);
      case 5: {
        const n = view.getUint32(valuePos, little);
        const d = view.getUint32(valuePos + 4, little);
        return d ? n / d : undefined;
      }
      case 10: {
        const n = view.getInt32(valuePos, little);
        const d = view.getInt32(valuePos + 4, little);
        return d ? n / d : undefined;
      }
      default:
        return undefined;
    }
  } catch {
    return undefined;
  }
}

/**
 * Liest EXIF-Daten aus einem JPEG (ArrayBuffer).
 * Gibt null zurück, wenn keine EXIF-Daten vorhanden sind.
 */
export function parseExif(buffer) {
  const bytes = new Uint8Array(buffer);
  const seg = findExifSegment(bytes);
  if (!seg) return null;

  const view = new DataView(buffer, seg.start, seg.length);
  const tiff = 10; // FFE1 + Länge(2) + "Exif\0\0"
  if (view.byteLength < tiff + 8) return null;
  const order = view.getUint16(tiff);
  const little = order === 0x4949;
  if (!little && order !== 0x4d4d) return null;

  const data = {};
  const patch = { orientationPos: -1, nextIfdPos: -1, pixelX: null, pixelY: null };

  const ifd0Offset = view.getUint32(tiff + 4, little);
  const ifd0 = readIfd(view, tiff, ifd0Offset, little);
  patch.nextIfdPos = ifd0.nextOffsetPos;
  let exifOffset = null;

  for (const e of ifd0.entries) {
    if (e.tag === TAG.MAKE) data.make = readValue(view, e, little);
    else if (e.tag === TAG.MODEL) data.model = readValue(view, e, little);
    else if (e.tag === TAG.DATETIME) data.dateTime = readValue(view, e, little);
    else if (e.tag === TAG.ORIENTATION) {
      data.orientation = readValue(view, e, little);
      patch.orientationPos = e.valuePos;
    } else if (e.tag === TAG.EXIF_IFD) exifOffset = readValue(view, e, little);
  }

  if (exifOffset) {
    const ifd = readIfd(view, tiff, exifOffset, little);
    for (const e of ifd.entries) {
      if (e.tag === TAG.EXPOSURE_TIME) data.exposureTime = readValue(view, e, little);
      else if (e.tag === TAG.F_NUMBER) data.fNumber = readValue(view, e, little);
      else if (e.tag === TAG.ISO) data.iso = readValue(view, e, little);
      else if (e.tag === TAG.DATETIME_ORIGINAL) data.dateTimeOriginal = readValue(view, e, little);
      else if (e.tag === TAG.FOCAL_LENGTH) data.focalLength = readValue(view, e, little);
      else if (e.tag === TAG.FOCAL_LENGTH_35MM) data.focalLength35 = readValue(view, e, little);
      else if (e.tag === TAG.PIXEL_X) patch.pixelX = { pos: e.valuePos, type: e.type };
      else if (e.tag === TAG.PIXEL_Y) patch.pixelY = { pos: e.valuePos, type: e.type };
    }
  }

  return {
    data,
    segment: bytes.slice(seg.start, seg.start + seg.length),
    little,
    patch,
  };
}

/** "2026:10:14 18:39:22" -> Date */
export function exifDate(str) {
  if (!str) return null;
  const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(str);
  if (!m) return null;
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  return isNaN(d) ? null : d;
}

function trimNumber(n, digits) {
  return String(Number(n.toFixed(digits)));
}

/** Baut den Kameradaten-Text, z.B. "23mm  f/1.8  1/120s  ISO100". */
export function formatCameraData(data) {
  if (!data) return "";
  const parts = [];
  const focal = data.focalLength35 || data.focalLength;
  if (focal) parts.push(`${Math.round(focal)}mm`);
  if (data.fNumber) parts.push(`f/${trimNumber(data.fNumber, 2)}`);
  if (data.exposureTime) {
    const t = data.exposureTime;
    parts.push(t >= 1 ? `${trimNumber(t, 1)}s` : `1/${Math.round(1 / t)}s`);
  }
  if (data.iso) parts.push(`ISO${data.iso}`);
  return parts.join("  ");
}

/**
 * Übernimmt den originalen EXIF-Block in ein neues JPEG.
 * Die Ausrichtung wird auf "normal" gesetzt (das Bild ist bereits gedreht),
 * das alte Vorschaubild wird entfernt und die Pixelgröße aktualisiert.
 */
export async function copyExif(exif, jpegBlob, width, height) {
  if (!exif || !exif.segment) return jpegBlob;
  const seg = exif.segment.slice();
  const view = new DataView(seg.buffer);
  const { little, patch } = exif;
  const inRange = (pos, size) => pos >= 0 && pos + size <= seg.length;

  if (inRange(patch.orientationPos, 2)) view.setUint16(patch.orientationPos, 1, little);
  if (inRange(patch.nextIfdPos, 4)) view.setUint32(patch.nextIfdPos, 0, little);
  for (const [p, value] of [
    [patch.pixelX, width],
    [patch.pixelY, height],
  ]) {
    if (!p) continue;
    if (p.type === 3 && inRange(p.pos, 2) && value <= 0xffff) view.setUint16(p.pos, value, little);
    else if (p.type === 4 && inRange(p.pos, 4)) view.setUint32(p.pos, value, little);
  }

  const out = new Uint8Array(await jpegBlob.arrayBuffer());
  if (out[0] !== 0xff || out[1] !== 0xd8) return jpegBlob;
  let rest = 2;
  // JFIF-Header (APP0) des Browsers entfernen, EXIF gehört direkt hinter SOI.
  if (out[2] === 0xff && out[3] === 0xe0) rest = 4 + ((out[4] << 8) | out[5]);
  return new Blob([out.subarray(0, 2), seg, out.subarray(rest)], { type: "image/jpeg" });
}
