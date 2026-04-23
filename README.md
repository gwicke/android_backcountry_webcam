# CameraUploader — Android App

Silently captures a JPEG photo with the rear camera every **5 minutes** and
POSTs it to your server. Starts automatically on every device reboot.

---

## Quick start

### 1. Set your server URL
Open `MainActivity.kt` and change the constant near the top:

```kotlin
const val UPLOAD_URL = "https://your-server.example.com/upload"
```

Replace the placeholder with the real HTTPS endpoint on your server.

### 2. Build and install
Open the project in **Android Studio Hedgehog (2023.1.1)** or newer and hit Run,
or build from the command line:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. First launch
Tap the app icon **once**. A permission dialog will ask for Camera access.
Grant it. The app disappears immediately — it is now running in the background.
After this first launch it will restart itself automatically after every reboot.

---

## How it works

| Component | Role |
|---|---|
| `MainActivity` | Transparent launcher — requests permissions, starts the service, exits |
| `CameraUploaderService` | Foreground service — owns the Camera2 session and the upload scheduler |
| `BootReceiver` | `BOOT_COMPLETED` listener — starts the service after every reboot |

### Upload format
Each capture is sent as an HTTP **multipart/form-data POST** with two fields:

| Field | Content |
|---|---|
| `image` | JPEG bytes, filename `capture_<unix_ms>.jpg` |
| `timestamp` | Unix epoch milliseconds (string) |

### Notification
Android requires a visible notification for foreground services that use the
camera. The notification is **silent** (no sound, no vibration) and shows the
last upload status. Users who want it fully hidden can long-press the
notification and disable it — the service will continue running.

---

## Server-side (example — Node.js / Express)

```javascript
const express = require('express');
const multer  = require('multer');
const upload  = multer({ dest: 'uploads/' });
const app     = express();

app.post('/upload', upload.single('image'), (req, res) => {
  console.log('Received', req.file.originalname, 'at', req.body.timestamp);
  res.sendStatus(200);
});

app.listen(443);   // use TLS in production
```

---

## Permissions required

| Permission | Why |
|---|---|
| `CAMERA` | Capture photos |
| `INTERNET` | Upload to server |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` | Keep service alive |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `POST_NOTIFICATIONS` | Show foreground service notification (Android 13+) |

---

## Minimum requirements
- Android 8.0 (API 26) or higher
- Rear-facing camera
