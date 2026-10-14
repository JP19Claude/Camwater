# Camwater 📷

Camwater setzt Kamera-Watermarks anderer Hersteller auf deine Fotos. Ein Beispiel: Du fotografierst mit deinem Samsung, und unten auf dem
Foto steht **„vivo X300 Ultra | ZEISS“** mit Brennweite, Blende, Belichtungszeit, ISO und Datum, so wie bei einem echten vivo-Handy.

Die App läuft im Browser (Chrome oder Samsung Internet) und lässt sich wie eine normale App auf den Startbildschirm legen. Deine Fotos werden
nirgendwo hochgeladen, alles passiert direkt auf deinem Handy.

## So funktioniert's

1. **Schalter oben auf „AN“** stellen.
2. **Watermark wählen**, z.B. *vivo X300 Ultra*.
3. **„Foto aufnehmen“** antippen. Die Samsung-Kamera geht auf, du machst dein Foto, und es wird **automatisch mit Watermark gespeichert**
   (Ordner „Download“, in der Galerie unter *Alben → Download*).
4. Alternativ **„Aus Galerie“**: mehrere Fotos auf einmal auswählen, alle bekommen das Watermark.

Ist der Schalter auf **AUS**, bekommen deine Fotos kein Watermark.

### Direkt aus der Samsung-Galerie

Wenn Camwater als App installiert ist (siehe unten), kannst du in der Galerie ein oder mehrere Fotos auswählen und auf **Teilen → Camwater**
tippen. Die Fotos werden dann sofort mit Watermark gespeichert.

## Mitgelieferte Watermarks

| Hersteller | Modell | Partner-Logo |
| --- | --- | --- |
| vivo | X300 Ultra, X300 Pro | ZEISS |
| Xiaomi | 17 Ultra | Leica |
| OPPO | Find X9 Ultra | HASSELBLAD |
| HUAWEI | Pura 80 Ultra | XMAGE |
| HONOR | Magic8 Pro | – |
| Samsung | Galaxy S26 Ultra | – |
| Apple | iPhone 17 Pro Max | – |
| Google | Pixel 10 Pro | – |
| OnePlus | 15 | – |
| Leica | M11 | Leica |
| Sony | α7 IV | α |
| Fujifilm | X100VI | – |

## Eigene Hersteller hinzufügen

Unter **„+ Eigene Marke / eigenes Handy hinzufügen“** kannst du jedes beliebige Watermark selbst bauen:

- **Marke & Modell** (z.B. „NOTHING“ und „Phone (3)“) und die Schriftart der Marke
- **Text davor**, z.B. „Shot on“
- **Stil**: Leiste mit Logo rechts (Leica/ZEISS-Stil), Leiste mittig (Hasselblad-Stil) oder Schrift direkt im Bild
- **Hell oder dunkel**, optional mit **Rand rundherum**
- **Partner-Logo**: Schriftfeld (wie ZEISS), Kreis (wie Leica), nur Schriftzug (wie HASSELBLAD) oder **ein eigenes Logo-Bild hochladen**
- **Kameradaten**: aus dem Foto übernehmen, eigener Text oder aus
- **Eigene Unterzeile**, z.B. dein Name

Auch die mitgelieferten Vorlagen lassen sich mit **„Bearbeiten“** ändern und wieder **zurücksetzen**. Unter *Einstellungen → Vorlagen
sichern / teilen* kannst du deine Vorlagen als Datei exportieren und auf einem anderen Handy importieren.

## Auf dem Handy öffnen & installieren

Die App ist eine statische Webseite. Am einfachsten machst du sie mit **GitHub Pages** online:

1. Auf GitHub im Repository auf **Settings → Pages** gehen.
2. Bei *Build and deployment* unter **Source** „Deploy from a branch“ wählen, dann den Branch mit dem Code und den Ordner **/ (root)**, und auf **Save**.
3. Nach 1–2 Minuten ist die App unter `https://<dein-github-name>.github.io/Camwater/` erreichbar.
4. Die Adresse auf dem Samsung in Chrome öffnen, dann **⋮ → Zum Startbildschirm hinzufügen / App installieren**.

Danach funktioniert Camwater auch offline.

Zum Ausprobieren am Computer reicht ein lokaler Webserver im Projektordner, z.B. `npx serve .` oder `python3 -m http.server`, und dann
`http://localhost:...` öffnen. Ein direkt per Doppelklick geöffnetes `index.html` geht nicht, weil der Browser die JavaScript-Module so blockiert.

## Einstellungen

- **Automatisch speichern**: Neue Fotos werden sofort gespeichert, wenn der Schalter an ist. Ist das aus, tippst du selbst auf *Speichern* oder *Teilen*.
- **Fotodaten behalten**: Datum und Kameradaten (EXIF) bleiben in der Datei, damit das Foto in der Galerie an der richtigen Stelle einsortiert wird.
  Die Daten zeigen weiterhin dein echtes Handy; nur das sichtbare Watermark ändert sich.
- **JPEG-Qualität** und **maximale Größe** (12 MP bis Original).

## Gut zu wissen

- **HEIC-Fotos** kann der Browser meist nicht öffnen. Stell in der Samsung-Kamera unter *Einstellungen → Bildformate* auf **JPEG**.
- Eine Web-App kann nicht im Hintergrund jedes Foto der Samsung-Kamera-App verändern. Deshalb fotografierst du über den Knopf
  „Foto aufnehmen“ in Camwater oder schickst Fotos per *Teilen* an Camwater.
- Die Logos werden als Schrift und einfache Formen nachgebaut, es sind keine Original-Logodateien enthalten. Marken- und Logonamen gehören
  ihren jeweiligen Inhabern; Camwater hat nichts mit diesen Firmen zu tun. Bitte gib Fotos mit fremdem Watermark nicht als Beweis aus, dass sie
  mit diesem Handy gemacht wurden (z.B. bei Fotowettbewerben).

## Aufbau

```
index.html            Oberfläche
css/style.css         Design
js/app.js             Ablauf: Fotos laden, Vorschau, Speichern/Teilen, Editor
js/render.js          Zeichnet Foto + Watermark (Leiste, mittig, im Bild)
js/presets.js         Mitgelieferte Vorlagen und Speicherung eigener Vorlagen
js/exif.js            Liest Kameradaten aus dem JPEG und übernimmt sie ins neue Bild
sw.js                 Offline-Modus und „Teilen → Camwater“
manifest.webmanifest  App-Name, Icons, Teilen-Ziel
```
