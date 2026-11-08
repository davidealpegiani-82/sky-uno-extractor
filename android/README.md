# Sky Uno Extractor — Android

Progetto Android nativo in Kotlin. L'app conserva le funzioni principali dell'estrattore web:
- importazione multipla di file HTML, HTM, TXT, M3U, M3U8 e JSON tramite selettore Android;
- riconoscimento dei canali e rimozione dei duplicati;
- selezione dei canali da esportare;
- riproduzione HLS integrata con AndroidX Media3 / ExoPlayer;
- esportazione di un player HTML autonomo;
- creazione e condivisione di link HTTPS tramite l'endpoint già usato dall'estrattore web.

## Build APK

Il workflow GitHub Actions **Build Sky Uno Extractor APK** compila l'APK debug a ogni modifica della cartella `android/` e può essere avviato anche manualmente da Actions. L'APK viene pubblicato come artifact della build.

## Nota sui flussi HTTP

L'app abilita il traffico HTTP in chiaro perché alcune playlist forniscono URL HTTP e gli host possono cambiare. Questo permette a ExoPlayer di tentare la riproduzione senza il blocco mixed-content di una pagina HTTPS, ma non rende funzionanti stream offline, URL scaduti o server che rifiutano la riproduzione. Il traffico HTTP non è cifrato e può essere intercettato: usare solo sorgenti di cui ci si fida.

Package: `it.davidealpegiani.skyunoextractor`
