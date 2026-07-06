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

1. Tap **+** in the top-right corner.
2. Grant the Bluetooth permission when prompted.
3. The app scans for devices named `LYWSD03MMC` or `ATC_*`.
4. Tap **Add** next to your sensor.

---

## 4. Syncing (history download)

Tap **Sync now** on a sensor card. The app will:
1. Connect via BLE GATT
2. Read the current temperature/humidity
3. Sync the device clock
4. Download all stored history records

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

## 5. Using the GATT Explorer

If sync returns unexpected results, tap the **wrench icon ⚙** on the sensor detail screen.

Tap **Connect & List Services** — it will list every GATT service and characteristic UUID the sensor exposes. This helps identify the exact history characteristic if the protocol has changed.

---

## 6. Viewing history

| Tab   | What you see                                              |
|-------|-----------------------------------------------------------|
| Day   | Temperature + humidity line chart, every reading that day |
| Month | Min / Max temperature per day for the selected month      |
| Year  | Min / Max temperature per month for the selected year     |

Use **< >** arrows to navigate between days / months / years.

---

## 7. Data is stored locally

All readings are stored in a Room (SQLite) database on the phone at:
`/data/data/com.thermolog/databases/thermolog.db`

You can back it up by enabling Android backup or by using ADB:
```
adb pull /data/data/com.thermolog/databases/thermolog.db
```

---

## 8. Back up / restore to Google Drive

From the home screen tap the **⋮ menu** (top-right):

- **Back up to Drive…** — writes a `roomvibe-backup-<date>.json` file. In the
  system "Save to" dialog, choose **Google Drive** (or Files, Dropbox, etc.).
  The file contains every sensor and all stored readings.
- **Restore from Drive…** — opens the system file picker; navigate to Google
  Drive and select a backup `.json`. Restore **merges** the data: any readings
  not already on the phone are added, and nothing existing is deleted.

Because it uses Android's built-in document picker, no Google account setup or
API keys are required — any cloud provider that appears in the picker works.
