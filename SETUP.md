# RoomVibe – Setup Guide

> **Just want to use the app?** You don't need to build it — download the ready-made
> APK from the [latest release](https://github.com/ValeriuProdan/RoomVibe/releases/latest)
> and install it on your phone. The steps below are for building from source.

## 1. Open in Android Studio

File → Open → select the `RoomVibe` folder. Android Studio will sync Gradle automatically.

If it asks to upgrade the AGP version, click **Don't remind me again** (the versions in the file are intentional).

## 2. Build & run

Connect your Android phone via USB, enable Developer Options + USB Debugging, then press **Run ▶** in Android Studio.

The app targets Android 8.0+ and requires Bluetooth LE.

---

## 3. Adding sensors

1. Tap the **+** button in the bottom-right corner.
2. Grant the Bluetooth ("Nearby devices") permission when prompted.
3. The app scans for nearby sensors and lists likely ones first (with their live
   temperature); tap **Show all other Bluetooth devices** if yours isn't listed.
4. Tap **Add** next to your sensor.

---

## 4. Syncing (history download)

Open a sensor's **⋮ menu** and tap **Sync now**, or tap the **refresh icon** on the
sensor's detail screen. The app will:
1. Connect via BLE GATT
2. Read the current temperature/humidity
3. Sync the device clock
4. Download any new stored history records (with a live counter you can cancel)

### If sync connects but history comes back empty

The LYWSD03MMC **stock firmware** may require a Xiaomi account token to serve history over BLE. The easiest fix is to flash the free open-source **pvvx firmware** — it exposes full history without any account.

**Flashing pvvx (takes ~2 minutes, no tools needed):**

1. On your Android phone open Chrome (or any browser with Web Bluetooth support).
2. Go to: https://pvvx.github.io/ATC_MiThermometer/TelinkMiFlasher.html
3. Tap **Connect** and select your LYWSD03MMC device.
4. Once connected tap **Do Activation** then **Flash Firmware**.
5. Choose the latest `ATC_MiThermometer_vX.X.bin` file and flash.
6. After flashing, the device renames itself to `ATC_XXXXXX` (last 6 chars of MAC).
7. In RoomVibe, tap + and scan again — you will see the new `ATC_` name. Add it.

The pvvx firmware still shows temperature and humidity on the screen exactly as before.

---

## 5. Viewing history

Open a sensor to see its temperature and humidity charts:

- **Pinch to zoom** smoothly from hourly detail out to daily and monthly views.
- **Drag the lower part** of a chart to scroll through time.
- **Drag the upper part** (or tap) to move a marker and read the exact values at
  any point.
- **Rotate to landscape** for a single full-screen chart (switch between temperature
  and humidity with the toggle).

Lines are coloured by value — temperature from blue (cold) to red (hot), humidity
green when comfortable and red at the dry/humid extremes. Switch between °C and °F
from the home **⋮ menu**.

---

## 6. Data is stored locally

All readings are stored in a Room (SQLite) database on the phone at:
`/data/data/com.thermolog/databases/thermolog.db`

You can back it up by enabling Android backup or by using ADB:
```
adb pull /data/data/com.thermolog/databases/thermolog.db
```

---

## 7. Back up / restore to Google Drive

From the home screen tap the **⋮ menu** (top-right):

- **Back up to Drive…** — writes a `roomvibe-backup-<date>.json` file. In the
  system "Save to" dialog, choose **Google Drive** (or Files, Dropbox, etc.).
  The file contains every sensor and all stored readings.
- **Restore from Drive…** — opens the system file picker; navigate to Google
  Drive and select a backup `.json`. Restore **merges** the data: any readings
  not already on the phone are added, and nothing existing is deleted.

Because it uses Android's built-in document picker, no Google account setup or
API keys are required — any cloud provider that appears in the picker works.
