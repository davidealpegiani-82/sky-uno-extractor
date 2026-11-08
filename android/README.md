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


## Stabilità HLS (1.1.0)

- Buffer locale Media3 ampliato per tollerare oscillazioni temporanee della rete.
- Nessun seek automatico durante il normale buffering; riallineamento al punto live predefinito soltanto quando Media3 segnala `BEHIND_LIVE_WINDOW`.
- Tentativi di recupero progressivi per errori di rete, annullati quando si cambia canale.
- Log diagnostici `SkyHLS` per caricamento manifest/segmenti ed errori, senza registrare gli URL completi o i token.
- Il flag `KEEP_SCREEN_ON` resta attivo durante la riproduzione locale; Cast continua a essere gestito da Media3.


## Buffering HLS live — build 1.1.2

- Buffer locale configurato a 30–90 secondi, con avvio a 6 secondi e ripresa dopo rebuffer a 15 secondi. La finestra è stata allineata all'offset live per evitare di chiedere al player più precaricamento di quanto la distanza dal bordo live consenta.
- Offset live obiettivo 45 secondi (limiti 30–90 secondi): privilegia una riserva di segmenti più ampia rispetto all'inseguimento del segmento più recente.
- Regolazione adattiva della velocità limitata a 0,97–1,03x per recuperare gradualmente il ritardo senza salti.
- Dopo ogni rebuffer Media3 incrementa automaticamente l'offset live di 10 secondi, adattandosi a sorgenti che pubblicano i segmenti con ritardo.
- Timeout di lettura HTTP portato a 45 secondi per non interrompere una risposta lenta dopo soli 25 secondi di inattività.
- Diagnostica in-app: stato HLS, secondi effettivamente precaricati, offset live e attività di download.
- Log di manifest e segmenti con host, byte, durata e tipo di errore; gli URL completi e i token non vengono registrati.

Queste impostazioni riducono le cause di buffering introdotte dal client, ma non possono ricreare segmenti che il server non pubblica, correggere URL scaduti o superare limiti imposti dalla sorgente.
