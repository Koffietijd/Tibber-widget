# Tibber Widget

Een Android-widget op volledig scherm met al je Tibber-gegevens. Gemaakt om op een oude telefoon
aan de muur te hangen.

## Wat staat er op het scherm

- **Stroomprijs nu**: prijs per kWh, prijsniveau (zeer goedkoop … zeer duur), opbouw uit energie en belasting
- **Vandaag**: laagste, gemiddelde en hoogste prijs, en het goedkoopste uur dat nog komt
- **Live verbruik** (alleen met Tibber Pulse): vermogen nu, verbruik en kosten vandaag, min/gem/max, teruglevering
- **Prijsgrafiek vandaag en morgen**: per kwartier of per uur, met kleur per prijsniveau en een lijn bij "nu"
- **Verbruik per uur** over de laatste 24 uur, met totaal in kWh en euro
- **Totalen**: gisteren, deze maand en vorige maand (kWh en kosten)

De widget past zich aan elk formaat aan, staand en liggend. Hij ververst **elke 15 minuten**
automatisch. Tik op de widget om meteen te verversen.

## Installeren

1. Ga naar **Releases → latest** in deze repository (of naar **Actions → laatste run → Artifacts**)
   en download `TibberWidget.apk`.
2. Open het bestand op je telefoon en sta "installeren uit onbekende bronnen" toe.
3. Houd je startscherm ingedrukt → **Widgets** → **Tibber Widget** → sleep **Tibber dashboard** naar het scherm.
4. Het instellingenscherm opent vanzelf: plak je API-token en tik op **Opslaan & testen**.
5. Maak de widget zo groot als het hele scherm.

Je API-token maak je aan op <https://developer.tibber.com/settings/access-token>.
Via het app-icoon **Tibber Widget** (of door de widget lang in te drukken → instellingen) kun je het
token later **aanpassen of verwijderen**. Heb je meerdere adressen, dan kies je daar ook de woning.

## Tips voor een telefoon aan de muur

- Zet in de ontwikkelaarsopties **Stand-by blijven** aan: het scherm blijft dan aan zolang de telefoon oplaadt.
- Zet batterijoptimalisatie uit voor Tibber Widget (Instellingen → Apps → Tibber Widget → Batterij → Onbeperkt).
- Gebruik een eenvoudige launcher zonder zoekbalk of dock, zodat de widget het hele scherm kan vullen.

## Zelf bouwen

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Bij elke push bouwt GitHub Actions de APK en zet hem bij de release `latest`.

## Technisch

- Kotlin, geen extra UI-bibliotheken. Data komt van de Tibber GraphQL API (`api.tibber.com`). Live
  data komt via de Tibber websocket.
- Verversen gaat met WorkManager (elke 15 minuten, het minimum dat Android toestaat).
- Het dashboard wordt als afbeelding getekend en via een content provider aan de launcher gegeven.
  Zo blijft het scherp op elk formaat.
- Het token staat alleen op het toestel (in de privé-opslag van de app).
- `app/debug.keystore` is een standaard debug-sleutel zonder geheime waarde. Met een vaste sleutel
  kun je een nieuwe versie over de oude installeren zonder je token kwijt te raken.
