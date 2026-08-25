# Bluecore GPS

This repository contains:

- An installable Frappe/ERPNext app that receives and stores GPS reports.
- A native Android app that periodically sends the PoC device location.

## Install on ERPNext

From the Frappe Bench directory:

```sh
bench get-app https://github.com/jryandechavez/erpnext-gps.git
bench --site dev.tickandterry.com install-app gps_tracker
bench --site dev.tickandterry.com migrate
bench restart
```

The receiver is:

```text
https://dev.tickandterry.com/api/method/gps_tracker.api.location
```

The saved records are available at:

```text
https://dev.tickandterry.com/app/gps-location
```

The receiver requires valid ERPNext token authentication and does not allow guest submissions.

## Android app

The Android app periodically sends the PoC device location to the configured ERPNext endpoint.

## Payload

The app sends `POST` JSON with `device_id`, `latitude`, `longitude`, `accuracy`, `altitude`, `speed`, `bearing`, and `recorded_at`. When an API key is set, it uses ERPNext token authentication:

`Authorization: token API_KEY:API_SECRET`

The default endpoint is `http://167.172.64.123/api/method/gps_tracker.api.location`. That server method must exist, accept the payload, store it, and return a 2xx response. The endpoint can be changed in the app.

## Build and install

```sh
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable Developer options and USB debugging on the Android POS device, connect it by a data-capable cable, and accept the authorization prompt before installing.

For reliable unattended tracking, exclude the app from the device manufacturer's battery optimization and grant precise location plus background location in Android Settings.
