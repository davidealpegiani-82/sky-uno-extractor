# DAVIX — Android

Progetto Android nativo in Kotlin. L'app conserva le funzioni principali dell'estrattore web:
- importazione multipla di file HTML, HTM, TXT, M3U, M3U8 e JSON tramite selettore Android;
- riconoscimento dei canali e rimozione dei duplicati;
- selezione dei canali da esportare;
- riproduzione HLS integrata con AndroidX Media3 / ExoPlayer;
- esportazione di un player HTML autonomo;
- creazione e condivisione di link HTTPS tramite l'endpoint già usato dall'estrattore web.

## Build APK

Il workflow GitHub Actions **Build DAVIX Extractor APK** compila l'APK debug a ogni modifica della cartella `android/` e può essere avviato anche manualmente da Actions. L'APK viene pubblicato come artifact della build.

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


## Revisione comparativa buffer — build 1.1.3

Le configurazioni delle revisioni precedenti sono state confrontate separando il player Android nativo dal player HTML:

- **Android / Media3 (mantenuta):** buffer 30–90 s, avvio 6 s, ripresa 15 s; offset live obiettivo 45 s, intervallo 30–90 s; velocità adattiva 0,97–1,03x e incremento dell'offset di 10 s dopo un rebuffer. Timeout connessione 15 s, lettura 45 s e policy errori Media3 con 6 tentativi.
- **HTML / HLS.js (mantenuta):** sincronizzazione a 6 segmenti, latenza massima 12 segmenti, obiettivo buffer 45 s e tetto 60 s. Il player mostra il buffer effettivo e la distanza dal live.
- **Configurazione HTML 60/120 s e 120 MB (non adottata):** aumentava molto il limite teorico senza garantire più segmenti disponibili e poteva aumentare memoria e ritardo; inoltre manteneva una sincronizzazione live più aggressiva (3/6 segmenti).
- **Recupero:** niente seek durante il normale buffering; il riallineamento al live avviene solo per perdita esplicita della finestra HLS. Retry progressivi e invalidazione dei retry al cambio canale.

La build 1.1.3 incorpora anche il player HTML con video fisso in alto e lista canali a scorrimento verticale. Le soglie sono obiettivi del client, non garanzie: manifest, segmenti, rete e server della sorgente restano determinanti.


## DAVIX Cloud Sync — prima integrazione

La branch `feature/sky-cloud-sync-foundation` introduce l'integrazione applicativa con il backend Cloudflare Worker + D1:

- **Extractor Android:** configurazione endpoint HTTPS e chiave amministratore; pubblicazione dei canali selezionati; approvazione dei codici di pairing; elenco e revoca dei dispositivi.
- **Credenziale amministratore:** cifrata localmente con AES-GCM e chiave generata da Android Keystore.
- **Player:** pairing individuale, credenziale dispositivo cifrata con Android Keystore, controllo della versione all'avvio, download solo quando la versione cambia e cache locale dell'ultima configurazione valida.
- **Android TV / Fire TV:** Player dichiara il supporto opzionale Leanback e non richiede touchscreen; la navigazione dei canali usa controlli focusabili.
- **Player HTML esportato:** riceve l'endpoint Cloud Sync configurato nell'Extractor, consente il pairing e controlla la versione all'apertura. La lista sincronizzata viene conservata nel local storage del browser; se la sincronizzazione fallisce resta disponibile la lista salvata o incorporata.

### Attivazione e primo utilizzo

Il backend è pubblicato all'indirizzo `https://sky-cloud-sync.davide-alpegiani.workers.dev` e il database D1 è collegato. L'endpoint è già precompilato nelle nuove build di Extractor e Player.

1. In DAVIX Extractor aprire **Configura DAVIX Cloud Sync**.
2. Verificare l'indirizzo precompilato e inserire la stessa chiave amministratore configurata nel secret Cloudflare `SKY_ADMIN_KEY` (conservarla in un password manager; non inviarla in chat e non inserirla nell'HTML).
3. Selezionare i canali e scegliere **Pubblica aggiornamento remoto**.
4. Sul Player o nell'HTML esportato avviare **Collega Player** e comunicare il codice temporaneo a Extractor.
5. In Extractor scegliere **Autorizza Player** e inserire il codice. Dopo l'approvazione, il Player scarica la lista e conserva una copia locale.

Le build Android aggiornate vengono pubblicate dal workflow **Build DAVIX Android APKs**. Il link stabile di Player è `https://github.com/davidealpegiani-82/sky-uno-extractor/releases/download/player-lite-latest/DAVIX-Player.apk`.

Se Cloud Sync non è raggiungibile, Player e HTML mantengono la configurazione locale già salvata.

### Limiti di sicurezza da conoscere

- La chiave amministratore e i token nativi sono cifrati localmente con Android Keystore; l'HTML usa invece il local storage del browser, la cui protezione dipende dal browser e dal dispositivo.
- Il Worker conserva attualmente la configurazione JSON in D1: gli URL dei flussi e gli eventuali parametri/token contenuti negli URL non sono ancora protetti con cifratura end-to-end. Il controllo di accesso impedisce ai Player non autorizzati di scaricare la lista, ma il provider cloud elabora e conserva il contenuto.
- Prima di inserire configurazioni con credenziali streaming particolarmente sensibili, va completata una fase di cifratura end-to-end della configurazione.
- La sincronizzazione trasferisce esclusivamente metadati/configurazione. Ogni Player si collega direttamente alla sorgente HLS e gestisce il proprio buffer Media3 o HLS.js.
- Le quote gratuite del provider sono soggette a limiti e possono cambiare. Non configurare billing o upgrade automatici; in caso di quota esaurita, il Player deve continuare a usare la cache locale.
