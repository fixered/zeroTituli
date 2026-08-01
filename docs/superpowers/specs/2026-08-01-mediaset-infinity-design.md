# Plugin Mediaset Infinity — progetto

Plugin CloudStream per <https://mediasetinfinity.mediaset.it/>: catalogo on demand
diviso per categorie, dirette TV, schede complete con copertine e informazioni,
serie a più stagioni navigabili, e casting dove la protezione del contenuto lo
permette.

## Vincolo che decide il progetto

Il catalogo on demand di Mediaset è protetto con **Widevine**. Non esistono
varianti in chiaro: l'HLS è SAMPLE-AES/FairPlay, un DASH è PlayReady, l'altro è
Widevine. Verificato su episodi, extra e film.

CloudStream, quando manda al Chromecast, passa al televisore solo l'indirizzo
(`CastHelper`: `MediaInfo.Builder(link.url)` più il `Content-Type`) e usa il
receiver predefinito di Google. Nessuna informazione di licenza arriva al
televisore, quindi **il VOD non è castabile**. Nemmeno passando dal proxy locale:
il proxy inoltra byte, non decifra — e decifrare Widevine è aggirare la
protezione, fuori da questo lavoro.

Sul telefono invece il VOD si vede: CloudStream espone `newDrmExtractorLink` con
`licenseUrl` e UUID Widevine, ed ExoPlayer usa il CDM del dispositivo. È
riproduzione normale.

Le **dirette TV** sono un altro caso: `nowNext` offre varianti DASH senza
`protectionScheme`, in chiaro, con il token di autorizzazione già dentro
l'indirizzo. Quelle si vedono e si castano.

Il plugin quindi copre tutto il catalogo, casta le dirette, e sul VOD dichiara nel
nome del link che vale solo sul telefono.

## Fonti dati

Tutte verificate il 2026-08-01, tutte raggiungibili senza account.

### Catalogo

`https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2`

Un feed solo, con `form=cjson`. Filtri che rispondono:

| Filtro | Uso |
| --- | --- |
| `byTags=category\|Fiction` | contenuti di una categoria |
| `byCustomValue={brandId}{...}` | tutti gli episodi di un programma |
| `byCustomValue={brandId}{...},{subBrandId}{...}` | una stagione |
| `byTitle=<titolo>` | ricerca esatta |
| `q=<testo>` | ricerca libera |
| `sort=mediasetprogram$publishInfo_lastPublished\|desc` | novità |
| `byProgramType=episode\|movie\|extra` | tipo di contenuto |
| `range=1-40`, `count=true` | pagine |

Campi utili per voce: `guid`, `title`, `description`, `longDescription`,
`programType`, `year`, `runtime`, `thumbnails` (29 varianti), `credits`,
`ratings`, `tags` (schemi `category`, `genre`, `channelCategory`, `typology`),
`tvSeasonNumber`, `tvSeasonEpisodeNumber`, `tvSeasonId`, `seriesId`,
`mediasetprogram$brandId`, `$brandTitle`, `$subBrandId`, `$subBrandTitle`,
`$duration`, `$genres`, `$editorialType`, `$channelsRights`, `media[].publicUrl`,
`media[].pid`.

I feed `mediaset-prod-all-brands` e `mediaset-prod-all-stations`, usati dai vecchi
addon Kodi, sono **spenti** (`BadParameterException: There is no enabled feed`).
Marchi e canali si ricostruiscono da altre fonti, come sotto.

### Categorie e indice alfabetico

`https://static3.mediasetplay.mediaset.it/cataloglisting/azListing.json`

Dà le categorie (`Calcio e Sport`, `Cinema`, `Documentari`, `Fiction`, `Kids`,
`Programmi Tv`) e, per ciascuna, le lettere iniziali che hanno contenuti. L'elenco
dei programmi per lettera arrivava da `api-ott-prod-fe.mediaset.net/PROD/play/rec/azlisting/v1.0`,
che ora risponde `Internal server error`: si ricava aggregando `programs-v2` per
`brandId` con `byTags=category|X` e filtro sull'iniziale del `brandTitle`.

### Sezioni editoriali

Le pagine sezione del sito (`/fiction`, `/kids`, `/documentari`, `/cinema`,
`/programmitv`, e le collezioni tipo `/action-zone`, `/tv-detective`) sono Next.js
con dati resi lato server: le righe e gli identificativi degli elementi
(`SE0000000…`, slug dei marchi) stanno nel markup della pagina.

L'API che le alimenta (`ares-be.internal.prod-bd-eks-cluster.bd-cloud.mediaset.net`)
non è raggiungibile da fuori: resta la lettura del markup, con **ripiego sul feed
per categoria** se il markup cambia. In quel caso la sezione resta al suo posto e
cambia solo l'ordine delle voci — non sparisce una riga.

### Dirette TV

`https://api-ott-prod-fe.mediaset.net/PROD/play/alive/nownext/v1.0?channelId=<callSign>`

Senza autenticazione. Serve un `channelId` valido: senza parametro risponde
`AG015 Channel id not found`, quindi la lista dei `callSign` sta nel codice, una
chiamata per canale, in parallelo. `C5` (Canale 5) è verificato; gli altri
(Italia 1, Rete 4, Iris, La5, 20, Focus, TGCOM24, Cine34, Mediaset Extra, Boing,
Cartoonito) vanno confermati provandoli uno per uno all'inizio dei lavori — i
canali che non rispondono restano fuori dalla lista invece di comparire rotti.
Restituisce:

- `response.tuningInstruction["urn:theplatform:tv:location:any"]`: le varianti del
  flusso, ognuna con `format`, `protectionScheme`, `publicUrls`. Le voci con
  `protectionScheme` vuoto sono in chiaro.
- `response.currentListing` / `nextListing`: programma in onda e successivo.
- `response.stations`: nome del canale, `callSign`, logo, diritti.

### Riproduzione

Tre passaggi.

1. **Login anonimo** — `POST https://api-ott-prod-fe.mediaset.net/PROD/play/idm/anonymous/login/v2.0`
   con corpo `{"client_id": "<uuid generato>", "appName": "web//mediasetplay-web/1.3.2-e49d465"}`.
   Risponde `{"response": {"beToken": "...", "sid": "..."}}`. L'`appName` è la
   versione del sito, scritta nel suo HTML: va messa come costante nel plugin e
   aggiornata se un giorno il login inizia a rispondere `AG005 VALIDATION_ERROR`.
2. **Controllo di riproduzione** — `POST .../PROD/play/playback/check/v2.0`, header
   `Authorization: Bearer <beToken>` e `sid: <sid>`, corpo
   `{"contentId": "<guid>", "streamType": "VOD"}`. Risponde con `mediaSelector`
   (indirizzo theplatform del contenuto), `channelsRights` e `channelsRightsUser`.
   Se manca `mediaSelector`, il contenuto non è disponibile per un utente anonimo.
3. **Risoluzione del flusso** — `GET <mediaSelector.url>` con
   `format=SMIL`, `formats=mpeg-dash`, `assetTypes=HR,widevine,geoIT|geoNo`,
   `auth=<beToken>`. Il SMIL contiene `<video src="…/dashrcenc/hr_wv_mpl.mpd">`.

Sull'`assetTypes` serve una catena: `HR,widevine,geoIT|geoNo` →
`SD,widevine,geoIT|geoNo` → `SS,widevine,geoIT|geoNo`. Un `assetTypes` che non
combacia dà `NoAssetTypeFormatMatches`, non un errore di rete.

Per le dirette il passaggio 2 non serve: il `publicUrls` di `nowNext` si risolve
in SMIL senza `auth` e restituisce un `.mpd` in chiaro con token `hdnts` in query.

## Struttura del modulo

Modulo nuovo `MediasetInfinity/`, come gli altri del repo: `build.gradle.kts` con
il blocco `cloudstream`, manifest, e sorgenti in `it.zeroTituli`. Da aggiungere a
`settings.gradle.kts`.

| File | Responsabilità | Dipende da |
| --- | --- | --- |
| `MediasetInfinity.kt` | `MainAPI`: `getMainPage`, `search`, `load`, `loadLinks` | tutti gli altri |
| `MediasetApi.kt` | HTTP: login anonimo con token in cache, `playbackCheck`, risoluzione SMIL, query ai feed | `MediasetDTOs` |
| `MediasetDTOs.kt` | data class delle risposte | — |
| `MediasetCatalog.kt` | composizione delle righe: sezioni, generi, A-Z; scelta delle immagini | `MediasetApi`, `MediasetDTOs` |
| `MediasetLive.kt` | canali, `nowNext`, flusso in chiaro | `MediasetApi` |
| `MediasetPlugin.kt` | registrazione del plugin | `MediasetInfinity` |

Ogni file sta sotto le 300 righe. `MediasetApi` è l'unico che parla in rete:
gli altri ricevono dati già letti, quindi si ragiona su un pezzo alla volta.

## Home

Quattro gruppi di righe, in quest'ordine.

1. **Dirette TV** — una voce per canale, con il programma in onda adesso come
   sottotitolo. Castabili.
2. **Sezioni** — Fiction, Cinema, Serie TV, Programmi TV, Kids, Documentari,
   Sport: dal markup della pagina sezione, con ripiego sul feed per categoria.
3. **Generi** — dai tag `genre` del feed.
4. **A-Z** — per categoria e lettera iniziale.

Le righe che tornano vuote non compaiono.

## Serie con più stagioni

La scheda è il **marchio** (`brandId`), non la singola stagione. Gli episodi
arrivano con una query per `brandId` e vengono raggruppati per
`tvSeasonNumber`, ordinati per stagione e poi per `tvSeasonEpisodeNumber`.

Mediaset a volte spezza le stagioni in `subBrandId` diversi. Vengono unite sotto
lo stesso `brandId`, così le stagioni finiscono nel selettore di CloudStream
invece di diventare schede separate nel catalogo.

Quando `tvSeasonNumber` manca (extra, speciali), l'episodio va in una stagione
dedicata in fondo, così non si mescola con la numerazione vera.

Ogni episodio porta numero, stagione, titolo, trama, durata e fotogramma.

## Copertine e informazioni

Il feed dà 29 varianti d'immagine per voce. Scelta:

- **poster** della scheda e della ricerca: `image_vertical-*`, la risoluzione più
  alta disponibile;
- **sfondo** della scheda: `image_header_poster-*`, altrimenti
  `image_horizontal_cover-*`;
- **fotogramma** dell'episodio: `image_horizontal_cover-*`;
- **canale**: `logo_horizontal-*`.

Per ogni ruolo la scelta scende di variante fino a trovarne una: mai una scheda
senza immagine se il feed ne ha almeno una.

Informazioni sulla scheda: trama da `longDescription` (`description` come
ripiego), anno, durata, generi come tag, cast da `credits`, classificazione da
`ratings` (`verde`/`giallo`/`rosso` mappati a un'età), consigliati dallo stesso
marchio.

## Riproduzione e cast

**Dirette.** Da `nowNext` si prende la variante DASH con `protectionScheme`
vuoto, si risolve il SMIL e si restituisce un `ExtractorLink` di tipo DASH.
L'indirizzo porta già il token `hdnts`, quindi il Chromecast lo apre da solo:
nessun proxy, nessun header da rimettere.

**VOD.** Catena di `assetTypes` come sopra, poi `newDrmExtractorLink` con
`uuid = WIDEVINE_DRM_UUID` e `licenseUrl`. ExoPlayer usa il CDM del dispositivo.

Il servizio licenze è

```
https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense
  ?form=json&schema=1.0
  &token=<beToken>
  &account=http://access.auth.theplatform.eu/data/Account/2702976343
  &releasePid=<pid>
```

dove `pid` è `media[].pid` del feed. Verificato per quanto si può senza un CDM:
con un `token` falso risponde `401 The provided authentication token is invalid`,
con un `account` falso `Invalid account URI`, mentre con il `beToken` anonimo e
l'account giusto arriva fino a valutare il corpo della richiesta. Cioè **il token
anonimo basta per chiedere la licenza**: non serve un account Mediaset.

L'ultimo passo — la licenza vera in risposta a una richiesta prodotta dal CDM —
si può provare solo sul dispositivo, ed è il primo controllo da fare appena il
plugin si installa.

Quando `loadLinks` riceve `isCasting = true`, il link Widevine si chiama
`"Widevine — solo sul telefono"`: si legge nell'elenco delle sorgenti invece di
finire in un errore muto sul televisore.

Contenuti da abbonamento o noleggio: `channelsRights` senza `AVOD`, oppure
`playbackCheck` senza `mediaSelector`. Marcati nella scheda con un tag, così si
sa prima di aprirli.

`hasChromecastSupport = true`: le dirette lo usano davvero, e per il VOD il nome
del link dice come stanno le cose.

## Errori

- **Token scaduto** — il `beToken` vale poche ore. Alla prima risposta di
  autorizzazione negata si rifà il login e si riprova **una volta sola**, poi si
  lascia cadere: niente cicli.
- **Fuori area** — il CDN risponde con `cortesia/GEOLOCK-DEF_2.mp4`, cioè un
  video di cortesia con esito positivo. Va riconosciuto dall'indirizzo e
  trasformato in "non disponibile in questa zona", altrimenti si guarda un
  cartello invece di un film.
- **`NoAssetTypeFormatMatches`** — `assetTypes` sbagliato: si passa al successivo
  della catena. Esauriti tutti, il contenuto non è disponibile.
- **Feed vuoto o markup cambiato** — la riga non compare; per le sezioni si passa
  al ripiego sul feed. Nessun crash.
- Ogni chiamata in `runCatching`, come negli altri plugin del repo.

## Verifica

Il repo non ha test: sono plugin CloudStream, si provano sul dispositivo. La
verifica è in tre passi.

1. `./gradlew MediasetInfinity:make` compila e produce il `.cs3`.
2. Uno script di ricognizione fuori dal plugin (in `scripts/`) ricontrolla i
   quattro punti di ingresso — feed catalogo, `azListing`, `nowNext`, catena
   login → `playbackCheck` → SMIL — e stampa cosa risolve. Quando Mediaset cambia
   qualcosa si vede subito quale pezzo è caduto, senza aprire l'app.
3. Prova sul dispositivo: home completa, una serie a più stagioni, un film, una
   diretta sul Chromecast.

## Fuori portata

- Login con account Mediaset. Solo anonimo, quindi solo il gratuito AVOD; i
  contenuti da abbonamento e noleggio vengono segnalati, non sbloccati.
- Cast del VOD. È Widevine: si può solo dichiararlo, non aggirarlo.
- Ripresa da dove si era rimasti lato Mediaset, preferiti sul loro account,
  download.
