# TapShare

Invio di file tra due telefoni Android: **tocco NFC** per accoppiarsi, poi il file viaggia su **Wi-Fi diretto** (senza internet).

## Come funziona

1. **Ricevitore** apre «Ricevi»: crea un gruppo Wi-Fi Direct con nome e password casuali e un token monouso, e li pubblica via NFC (emulazione di carta, `TapHostApduService`).
2. **Mittente** sceglie il file in «Invia» (o lo condivide da un'altra app) e avvicina il telefono: l'NFC in modalità lettore legge i dati di connessione.
3. Il mittente si collega alla rete del ricevitore (`WifiNetworkSpecifier`) e invia il file su un socket TCP (porta 8988). Il ricevitore scarta chi non conosce il token e salva in `Download/TapShare`.

## Compilare l'APK

**Android Studio:** File → Open → cartella `TapShare`, attendi la sincronizzazione Gradle, poi Run (o Build → Build APK).

**Da terminale** (serve l'Android SDK, `ANDROID_HOME` impostato e JDK 17+):

```
./gradlew assembleDebug
```

L'APK esce in `app/build/outputs/apk/debug/app-debug.apk`.

## Requisiti per provarla

- Due telefoni **reali** con Android 10 o superiore, NFC con emulazione di carta (HCE) e Wi-Fi Direct. Non funziona sugli emulatori.
- NFC e Wi-Fi attivi su entrambi; schermo acceso e sbloccato durante il tocco.
- Alla prima connessione Android può chiedere al mittente di confermare il collegamento alla rete.

## Limiti noti

- Il file è inviato in chiaro sul Wi-Fi Direct (la rete è protetta WPA2 con password casuale, ma il socket non è cifrato in più).
- Il trasferimento gira nell'app in primo piano (schermo tenuto acceso): per file molto grandi conviene aggiungere un foreground service.
- Un file alla volta; l'indirizzo del ricevitore è quello predefinito di Wi-Fi Direct (`192.168.49.1`).
- Il codice non è stato ancora compilato né provato su dispositivi reali.
