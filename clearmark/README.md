# ClearMark

Android-App, die eine feste Logo-Wasserzeichen-Fläche (z. B. ein Kamera- oder App-Logo unten rechts) aus
**eigenen Videos** entfernt. Kotlin, Jetpack Compose (Material 3), minSdk 26, targetSdk 36.

> **Hinweis:** ClearMark ist nur für eigene Inhalte gedacht. Entferne Wasserzeichen ausschließlich aus Videos,
> an denen du die Rechte hast. Die App zeigt diesen Hinweis beim ersten Start an.

## Ablauf

1. **Video auswählen** – über den Android Photo Picker (nur Videos).
2. **Logo markieren** – ExoPlayer (Media3) mit Play/Pause und Seekbar. Darüber liegt ein Rechteck, das sich
   verschieben und an acht Griffen skalieren lässt. Start: unten rechts, 18 % breit, 8 % hoch.
3. **Methode wählen**
   - **Delogo**: FFmpeg-Filter `delogo`, füllt den Bereich aus den Nachbarpixeln auf.
   - **Crop**: schneidet den Rand mit dem Logo ab (größtmöglicher Ausschnitt mit gleichem Seitenverhältnis)
     und skaliert wieder auf Originalgröße. Die App zeigt an, um wie viel Prozent das Bild dabei vergrößert wird.
   - **Blur**: zeichnet den Bereich mit `boxblur` weich (Ausweichlösung).
4. **Exportieren** – läuft im Hintergrund (Coroutine plus Foreground-Service), mit Fortschritt in Prozent,
   Benachrichtigung und Abbrechen-Button.
5. **Ergebnis** – Vorschau, **In Galerie speichern** (`Movies/ClearMark`) und **Teilen**.

## Bauen

Voraussetzungen:

- JDK 17 oder neuer
- Android SDK mit Platform 36 (Android Studio installiert das automatisch; sonst `ANDROID_HOME` setzen oder
  `sdk.dir` in `clearmark/local.properties` eintragen)
- Internetzugang zu `google()` und `mavenCentral()` beim ersten Build

```bash
cd clearmark
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # Unit-Tests (Koordinaten, FFmpeg-Befehle, Fehlerzuordnung)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ das Projekt `clearmark/` in Android Studio öffnen und starten.

Jeder Push mit Änderungen in `clearmark/` startet den Workflow
[`.github/workflows/clearmark.yml`](../.github/workflows/clearmark.yml). Er führt die Unit-Tests aus, baut die
Debug-APK und legt sie als Artefakt **ClearMark-debug-apk** am Workflow-Lauf ab.

## Technik

### Architektur (MVVM)

```
app/src/main/java/app/clearmark/
├── domain/   reine Kotlin-Logik ohne Android, vollständig unit-getestet
│   ├── CoordinateMapper      Ansicht ↔ normiert ↔ Videopixel (Letterboxing, Rotation, Pixel-Seitenverhältnis)
│   ├── RectEditor            Verschieben/Skalieren, Treffertest der Griffe
│   ├── FfmpegCommandBuilder  FFmpeg-Argumente für Delogo, Crop und Blur
│   └── ExportRules           Speicherschätzung, Zuordnung von FFmpeg-Fehlern
├── data/     FFmpegKit-Wrapper, Export-Manager und Foreground-Service, MediaStore, Teilen, Einstellungen
└── ui/       Compose-Screens, je Screen ein ViewModel mit StateFlow (Start, Editor, Ergebnis)
```

Abhängigkeiten werden ohne Framework über `AppContainer` bereitgestellt.

### Koordinaten

Die Markierung wird normiert (0..1) relativ zum **angezeigten** Bild gespeichert. Beim Export rechnet
`CoordinateMapper` sie in Pixel des **codierten** Bildes um:

- **Letterboxing:** Das Video wird mit erhaltenem Seitenverhältnis in die Ansicht eingepasst. Die Markierung
  bezieht sich nur auf die tatsächliche Bildfläche, schwarze Balken zählen nicht mit.
- **Rotation:** Hochkant-Handyvideos sind meist quer codiert und tragen eine Rotation von 90°. Die Markierung
  „unten rechts“ liegt dann im codierten Bild rechts oben. Unterstützt werden 0°, 90°, 180° und 270°.
- **Pixel:** Es wird nach außen gerundet. Für `delogo` bleibt rundherum mindestens 2 px Abstand zum Bildrand.
  Für Crop und Blur werden die Werte auf gerade Zahlen gebracht, wie es yuv420p verlangt.

### FFmpeg-Befehl

Beispiel (Delogo):

```
-y -hide_banner -noautorotate -i <eingabe>
-map 0:v:0 -vf delogo=x=1536:y=972:w=346:h=87
-map 0:a? -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p
-c:a copy -map_metadata 0 -movflags +faststart <ausgabe>.mp4
```

- `-noautorotate`: Die Filter arbeiten auf dem codierten Bild, und FFmpeg übernimmt die Rotations-Information
  (Display-Matrix) unverändert. Auch Blur läuft deshalb über `-vf`, einen einfachen Filtergraphen mit internen
  Labels, und nicht über `-filter_complex`, denn damit ginge die Rotation verloren.
- Der Ton wird mit `-c:a copy` unverändert übernommen. Passt die Tonspur nicht in MP4 (z. B. Vorbis aus WebM),
  wiederholt die App den Export einmal mit AAC.
- `-map_metadata 0` übernimmt Metadaten wie Aufnahmedatum und Ort.
- Die Eingabe wird über das SAF-Protokoll von FFmpegKit direkt aus der Content-URI gelesen, ohne Kopie.

### FFmpegKit

Das ursprüngliche FFmpegKit (`com.arthenica`) wurde 2025 eingestellt, und seine Binärpakete wurden von Maven
Central entfernt. ClearMark nutzt den gepflegten Fork
[`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl`](https://github.com/ffmpegkit-maintained/ffmpeg) in Version
8.1.9 (FFmpeg 8.1, Android SDK 35, 16-KB-Seitengröße). Er ist ein Drop-in-Ersatz mit derselben API
(`com.arthenica.ffmpegkit`). Vor der Auswahl wurde geprüft, dass die Bibliotheken `libx264`, `delogo` und
`boxblur` enthalten.

Die **GPL-Variante** ist nötig, weil `libx264` sowie die Filter `delogo` und `boxblur` nur in GPL-Builds von
FFmpeg verfügbar sind. Wer die App verbreitet, muss deshalb die Bedingungen der **GPL-3.0** einhalten.

### Fehlerbehandlung

Verständliche Meldungen gibt es unter anderem für:

- nicht lesbare Videos
- zu wenig Speicher (vor dem Start geschätzt und während des Exports erkannt)
- FFmpeg-Fehler (mit den letzten Logzeilen als Details)
- eine zu große Markierung für Crop
- nicht unterstützte Geräte
- fehlgeschlagenes Speichern

## Grenzen

- Native FFmpeg-Bibliotheken gibt es nur für 64-Bit-Geräte (`arm64-v8a`, `x86_64`). Die APK ist dadurch rund
  50 MB groß.
- HDR-Videos (10 Bit) werden als 8-Bit-H.264 (yuv420p) exportiert, ohne Tone-Mapping.
- Andere Spuren (Untertitel, Datenspuren) werden nicht übernommen, nur das erste Video und alle Tonspuren.
- Die App hat keine Internet-Berechtigung. Sie ist im Manifest auch für Bibliotheken ausdrücklich entfernt.
  Alles läuft lokal auf dem Gerät.
