# Sky Player Lite

App Android nativa e autonoma per la riproduzione di singoli flussi HLS ricevuti da Sky Player HTML.

- Package: `it.davidealpegiani.skylite`
- Deep link: `skylite://play?url=<URL-ENCODED>&name=<NAME-ENCODED>`
- Motore: AndroidX Media3 / ExoPlayer con HLS
- Buffer client: 30–90 secondi, avvio 6 secondi, ripresa 15 secondi
- Offset live obiettivo: 45 secondi (limiti 30–90 secondi)
- Nessun estrattore playlist, Google Cast o pulsante Trasmetti.

La build debug viene generata insieme a Sky Extractor da GitHub Actions. Dopo il merge su `main`, l'APK Lite viene anche pubblicata nella release stabile `player-lite-latest`.

Le soglie di buffer sono obiettivi del client e non possono compensare segmenti mancanti, URL scaduti o limitazioni della sorgente.
