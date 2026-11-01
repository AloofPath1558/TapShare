# TapShare

Send files between devices without accounts or uploads. There are two ways to pair:

- **Tap**: two Android phones touch (NFC), then the file travels over direct Wi-Fi. No internet needed.
- **Code**: one device shows a six-character code, the other types it (or scans the QR code). Works between the **website**, the **app's Web mode** and any modern browser, including iPhone and computers.

The Android app does both. The website in `docs/` does the code mode.

## Project layout

```
app/    Android app (Kotlin)
docs/   The website, served by GitHub Pages and also bundled inside the app
  index.html        how it works
  web/              the web version (code mode)
  assets/           shared styles and logo
```

The app and the site share the same colors and type (`docs/assets/style.css` and `app/src/main/res/values/colors.xml`). Web mode in the app loads the `docs/` files from the APK, so the app and the site can't drift apart.

## Publish the website (GitHub Pages)

1. Push the repository to GitHub (the `docs/` folder must be included).
2. Settings → Pages → Build and deployment → Source: **Deploy from a branch**, Branch: `main`, Folder: `/docs`.
3. The site appears at `https://<user>.github.io/<repo>/`. The web version is under `/web/`.

If your address isn't `aloofpath1558.github.io/TapShare`, change it in `docs/web/config.js` (`publicUrl`) and in the `<data>` element of `WebActivity` in `app/src/main/AndroidManifest.xml`.

## How tap mode works

1. **Receiver** opens "Receive by tap": the app creates a Wi-Fi Direct group with a random name and password plus a one-time token, and publishes them over NFC (card emulation, `TapHostApduService`).
2. **Sender** picks a file in "Send by tap" (or shares one from another app) and holds the phones together: NFC in reader mode reads the connection details.
3. The sender joins the receiver's network (`WifiNetworkSpecifier`) and sends the file over a TCP socket (port 8988). The receiver drops anyone who doesn't know the token and saves the file to `Downloads/TapShare`.

## How code mode works

1. The receiver opens Receive and gets a code (and a QR code with a link like `.../web/?join=ABC123`).
2. The sender enters the code. A public PeerJS pairing service introduces the two browsers, then WebRTC connects them directly. The file doesn't go through the service.
3. The receiver sees the file name and size and chooses **Accept** or **Decline**. Nothing is saved before that.

In the app, Web mode saves the file straight into `Downloads/TapShare` as it arrives (through the `TapShareNative` bridge in `WebActivity`). In a browser, the file is kept in memory and offered as a download when it is complete.

To use your own pairing service, set `peer` in `docs/web/config.js`.

## Build the APK

**Android Studio:** File → Open → the `TapShare` folder, wait for the Gradle sync, then Run (or Build → Build APK).

**Command line** (needs the Android SDK, `ANDROID_HOME` set and JDK 17+):

```
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions:** a workflow that runs `./gradlew assembleDebug` and uploads the APK as an artifact goes in `.github/workflows/build.yml`.

## Requirements

- **Tap:** two **real** phones running Android 10 or newer, with NFC card emulation (HCE) and Wi-Fi Direct. It does not work on emulators. NFC and Wi-Fi on for both, screens on and unlocked during the tap. The first time, Android may ask the sender to confirm joining the network.
- **Code:** a modern browser (or the app) on both devices, and an internet connection to pair.

## Known limits

- Tap: the file is sent unencrypted over the Wi-Fi Direct link (the network itself is WPA2-protected with a random password, but the socket adds no encryption of its own).
- Tap: the transfer runs while the app is in the foreground (the screen is kept on). For very large files, add a foreground service.
- Tap: one file at a time; the receiver's address is the Wi-Fi Direct default (`192.168.49.1`).
- Code: the pairing service and the PeerJS and QR libraries are loaded from public CDNs, so pairing needs internet. If a direct connection can't be made, WebRTC may relay the (encrypted) traffic.
- Code: a browser keeps the received file in memory, so very large files can fail. The app's Web mode writes to storage as it goes.
- Web mode in the app exposes the file-saving bridge to the page it loads. The page only loads the bundled site, but its scripts come from public CDNs; pin them with Subresource Integrity hashes if that matters to you.
- A browser can't act as an NFC card or create a Wi-Fi Direct network, so a tap never pairs with the website. Use a code for that.
- This has not been compiled or tested on real devices here. The web version was tested in Chrome with a stand-in for the pairing service.
