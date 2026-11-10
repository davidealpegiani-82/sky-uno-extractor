# Sky Cloud Sync

Backend leggero per sincronizzare la sola configurazione dei canali tra Sky Extractor e Player autorizzati. Non riceve, inoltra o memorizza segmenti video HLS.

## Stato

Questa è la base del servizio (API + schema D1). Non è ancora pubblicata su Cloudflare e non è ancora collegata alle interfacce Android/HTML. Il deploy richiede un account Cloudflare e una configurazione iniziale eseguita dal proprietario.

## Requisiti

- Account Cloudflare con Workers e D1 abilitati.
- Node.js e Wrangler CLI.
- Piano Free: nessun upgrade automatico; rispettare le quote gratuite correnti.

## Deploy iniziale

Dalla cartella `cloud-sync/`:

1. Crea un database D1:
   `npx wrangler d1 create sky-cloud-sync`
2. Copia il `database_id` restituito in `wrangler.toml`.
3. Crea le tabelle:
   `npx wrangler d1 execute sky-cloud-sync --remote --file=./schema.sql`
4. Genera una chiave amministratore lunga e casuale (almeno 32 byte) e configurala come secret:
   `npx wrangler secret put SKY_ADMIN_KEY`
5. Pubblica:
   `npx wrangler deploy`

Non inserire mai la chiave amministratore nel repository, nell'HTML pubblico o in una APK distribuita.

## Flusso di associazione

1. Un Player genera localmente un segreto casuale (almeno 32 byte) e un ID installazione.
2. `POST /v1/pair/start` riceve il segreto, l'ID, il nome e la piattaforma. Il backend salva soltanto SHA-256 del segreto e restituisce un codice di associazione valido 10 minuti.
3. Il proprietario inserisce il codice in Extractor e invia `POST /v1/admin/pair/approve` usando la chiave amministratore.
4. Il Player controlla `GET /v1/pair/status?pairingId=...` con il proprio segreto Bearer.
5. Una volta approvato, il Player può richiedere la configurazione. Il segreto è individuale e può essere revocato.

## API

- `GET /health`: stato servizio.
- `POST /v1/pair/start`: avvia associazione.
- `GET /v1/pair/status?pairingId=...`: verifica approvazione.
- `POST /v1/admin/pair/approve`: approva codice (header `X-Sky-Admin-Key`).
- `POST /v1/admin/config/publish`: pubblica la lista (header admin).
- `GET /v1/config?sinceVersion=<versione-locale>`: controlla la versione; se è già aggiornata restituisce soltanto metadati, altrimenti scarica la configurazione (header `Authorization: Bearer <device-secret>`).
- `GET /v1/admin/devices`: elenco dispositivi (header admin).
- `POST /v1/admin/devices/revoke`: revoca dispositivo (header admin).

## Contratto di pubblicazione

```json
{
  "schemaVersion": 1,
  "updateId": "uuid-generato-dall-extractor",
  "channels": [
    {
      "id": "channel-id",
      "name": "Nome canale",
      "url": "https://host.example/live/index.m3u8",
      "logo": "https://host.example/logo.png",
      "category": "Intrattenimento",
      "headers": {},
      "expiry": null
    }
  ]
}
```

I campi opzionali non disponibili vanno omessi. Gli URL sono conservati come metadati e il Player li usa per collegarsi direttamente alla sorgente HLS. Il servizio non effettua proxy, download o caching dei video.

## Sicurezza e limiti

- HTTPS è obbligatorio in produzione.
- Le credenziali dei Player sono individuali; nel database viene conservato il loro hash.
- I codici di associazione scadono dopo 10 minuti e non sono credenziali permanenti.
- La chiave amministratore è un Cloudflare secret e non deve mai essere distribuita ai Player.
- I dati di configurazione sono conservati nel database D1: non caricare liste che non si è autorizzati a condividere.
- Le quote gratuite Cloudflare possono cambiare. Il progetto non abilita billing o upgrade automatici; in caso di quota esaurita la sincronizzazione può fermarsi, mentre ogni Player conserva l'ultima lista valida.
- Prima di distribuire la funzione a utenti reali vanno aggiunti test end-to-end, rate limiting anti-abuso e verifica del flusso di revoca su dispositivi reali.
