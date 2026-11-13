# DAVIX Cloud Sync

Backend Cloudflare Worker + D1 per la distribuzione autorizzata delle configurazioni di canali. Il servizio non inoltra i flussi video.

## API v2 e autorizzazioni

- L'Extractor genera un codice casuale monouso tramite `POST /v2/admin/activation-codes` (header `X-Sky-Admin-Key`). Il codice viene mostrato una sola volta, scade dopo 10 minuti e viene conservato nel database soltanto come hash.
- Il Player riscatta il codice tramite `POST /v2/activate`, associandolo al proprio install ID e a un segreto casuale. Il consumo del codice avviene con un singolo UPDATE condizionale e `RETURNING`: un codice non può attivare due installazioni.
- Il Player autorizzato riceve una licenza offline di 72 ore. Ogni richiesta online valida rinnova la scadenza. Alla scadenza senza verifica online il Player deve bloccare i canali; una revoca viene applicata alla successiva verifica online.
- `GET /v1/config` richiede un dispositivo attivo con licenza valida.
- `GET /v1/admin/devices` restituisce sia `authorizedDevices` sia `revokedDevices`.
- `POST /v2/admin/devices/rename` modifica il nome visualizzato.
- `POST /v1/admin/devices/revoke` revoca il dispositivo e registra la data.
- Le vecchie API di pairing sono disabilitate (HTTP 410).

## Deploy / migrazione D1

La pipeline GitHub applica automaticamente le migrazioni versionate con `wrangler d1 migrations apply sky-cloud-sync --remote` prima del deploy. Per un'applicazione manuale, dalla cartella `cloud-sync/` esegui:

```sh
npx wrangler d1 migrations apply sky-cloud-sync --remote
npx wrangler deploy
```

La migrazione `0001_init.sql` crea le tabelle base in modo compatibile anche con il database già esistente; `0002_device_activation.sql` aggiunge i campi e la tabella per il nuovo sistema. Non eseguire prima lo snapshot `schema.sql` e poi le migrazioni, perché lo snapshot contiene già lo schema aggiornato.

Configurare `SKY_ADMIN_KEY` come secret Cloudflare, mai nel repository o nelle APK.

## Limiti offline

La revoca non può raggiungere istantaneamente un dispositivo completamente offline. Con questa configurazione un dispositivo già attivato può continuare a usare la copia locale fino alla scadenza della licenza offline (72 ore); appena torna online la revoca viene rilevata e la configurazione locale deve essere cancellata/resa inaccessibile.
