#!/usr/bin/env bash
# Ricognizione degli endpoint di Mediaset Infinity.
#
# Quando il plugin smette di funzionare, questo dice quale pezzo è caduto senza
# aprire l'app. Serve `curl`; niente altro: tutto quello che serve leggere è
# JSON o SMIL abbastanza semplice da bastare grep/sed.
#
# Le costanti qui sotto sono ripetute (non importate) di proposito: questo
# script deve girare anche quando il modulo Kotlin non compila. Vanno tenute
# identiche, carattere per carattere, a MediasetUrls.kt.
#
# Niente `set -o pipefail`: ogni check guarda se il valore estratto dopo la
# pipe è vuoto, non lo stato d'uscita della pipe stessa, quindi un fallimento
# a metà pipe si vede comunque come CADUTO. Chi aggiunge un check nuovo e si
# affida invece allo stato d'uscita della pipe deve aggiungerlo esplicitamente.
set -u

FEED="https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"
PLAY="https://api-ott-prod-fe.mediaset.net/PROD/play"
SITE="https://mediasetinfinity.mediaset.it"
APP_NAME="web//mediasetplay-web/1.3.2-e49d465"

ok()   { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mCADUTO\033[0m %s\n' "$1"; FAILED=1; }
FAILED=0

echo "1. Feed del catalogo"
if curl -sf -m 20 "$FEED?form=cjson&range=1-1" | grep -q '"entries"'; then
  ok "mediaset-prod-all-programs-v2 risponde"
else
  fail "il feed del catalogo non risponde: senza questo non c'è catalogo"
fi

echo "2. Categorie e lettere"
if curl -sf -m 20 "https://static3.mediasetplay.mediaset.it/cataloglisting/azListing.json" \
   | grep -q '"categories"'; then
  ok "azListing.json risponde"
else
  fail "azListing.json non risponde: le categorie vanno prese dai tag del feed"
fi

echo "3. Dirette"
for ch in C5 I1 R4; do
  body=$(curl -sf -m 20 "$PLAY/alive/nownext/v1.0?channelId=$ch")
  if printf '%s' "$body" | grep -q 'tuningInstruction'; then
    if printf '%s' "$body" | grep -q '"protectionScheme": *""'; then
      ok "$ch: c'è una variante in chiaro (castabile)"
    else
      fail "$ch: risponde ma nessuna variante in chiaro: il cast delle dirette cade"
    fi
  else
    fail "$ch: nowNext non risponde"
  fi
done

echo "4. Sessione anonima"
LOGIN=$(curl -sf -m 20 -X POST "$PLAY/idm/anonymous/login/v2.0" \
  -H 'Content-Type: application/json' \
  -H "Origin: $SITE" \
  -d "{\"client_id\":\"$(uuidgen 2>/dev/null || echo 11111111-2222-3333-4444-555555555555)\",\"appName\":\"$APP_NAME\"}")
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"beToken": *"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  ok "login anonimo: token ottenuto"
else
  # Verificato a mano: un appName sbagliato torna HTTP 400 con code AG005
  # "APPNAME_INVALID" (non "VALIDATION_ERROR" come si potrebbe pensare).
  fail "login anonimo fallito: se l'errore è AG005/APPNAME_INVALID, l'appName è da aggiornare (MediasetUrls.APP_NAME)"
fi

echo "5. Riproduzione di un contenuto gratuito"
GUID=$(curl -sf -m 20 "$FEED?form=cjson&range=1-1&byProgramType=episode&sort=mediasetprogram\$publishInfo_lastPublished|desc" \
  | sed -n 's/.*"guid": *"\([^"]*\)".*/\1/p' | head -1)
if [ -z "$GUID" ] || [ -z "$TOKEN" ]; then
  fail "salto la prova: manca il guid o il token"
else
  MEDIA=$(curl -sf -m 20 -X POST "$PLAY/playback/check/v2.0" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"contentId\":\"$GUID\",\"streamType\":\"VOD\"}" \
    | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p' | head -1)
  if [ -z "$MEDIA" ]; then
    fail "playbackCheck non dà mediaSelector per $GUID"
  else
    ok "playbackCheck risponde per $GUID"
    # auto/tracking replicano esattamente MediasetUrls.smil(): senza,
    # non sarebbe la stessa richiesta che fa il plugin.
    SMIL=$(curl -sf -m 25 -G "$MEDIA" \
      --data-urlencode 'format=SMIL' \
      --data-urlencode 'formats=mpeg-dash' \
      --data-urlencode 'assetTypes=HR,widevine,geoIT|geoNo' \
      --data-urlencode 'auto=true' \
      --data-urlencode 'tracking=false' \
      --data-urlencode "auth=$TOKEN")
    case "$SMIL" in
      *cortesia*|*GEOLOCK*) fail "SMIL: blocco geografico, serve una rete italiana" ;;
      *NoAssetTypeFormatMatches*) fail "SMIL: nessuna copia per questi assetTypes, la catena è da rivedere" ;;
      *.mpd*) ok "SMIL: manifest DASH risolto" ;;
      *) fail "SMIL: risposta inattesa" ;;
    esac
  fi
fi

echo "6. Licenza Widevine"
if [ -n "$TOKEN" ]; then
  # Senza un CDM non si ottiene una licenza vera: qui si controlla solo che il
  # servizio accetti token e conto. Un 401 vuol dire token rifiutato (verificato
  # a mano con un token farlocco); un 422 vuol dire che token e conto sono
  # stati accettati e solo la challenge Widevine finta è stata respinta.
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 20 -X POST \
    "https://widevine.entitlement.eu.theplatform.com/wv/web/ModularDrm/getRawWidevineLicense?form=json&schema=1.0&token=$TOKEN&account=http%3A%2F%2Faccess.auth.theplatform.eu%2Fdata%2FAccount%2F2702976343&releasePid=UXvEsmsZ1AvC" \
    -H 'Content-Type: application/octet-stream' --data-binary 'sonda')
  case "$CODE" in
    401) fail "licenza: token rifiutato" ;;
    422) ok  "licenza: token e conto accettati (422 atteso senza CDM)" ;;
    *)   fail "licenza: risposta $CODE inattesa, da guardare a mano se il VOD non parte" ;;
  esac
else
  fail "salto la prova della licenza: manca il token"
fi

echo "7. Markup delle sezioni"
# Ancorato al tag <ul>: il selettore del plugin è `ul.ulCarousel`
# (MediasetSections.kt), quindi una `ulCarousel` sopravvissuta solo altrove
# nella pagina (CSS, bundle JS, regola Tailwind) non deve dare falso verde.
if curl -sf -m 30 "$SITE/fiction" | grep -qE '<ul[^>]*class="[^"]*ulCarousel'; then
  ok "le pagine sezione hanno ancora i caroselli nel markup"
else
  fail "markup delle sezioni cambiato: la home usa il ripiego sul feed per categoria"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Tutto in piedi."
else
  echo "Qualcosa è caduto: vedi sopra."
fi
exit "$FAILED"
