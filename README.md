# ThriftyCam - Android App

Power and bandwidth efficient remote timelapse camera app. Takes a photo every
N seconds / minutes / hours (configurable), and uploads it to a plain WebDAV /
PUT enabled server. Other object / file storage APIs might be added in the
future.

Aimed at remote and often solar powered timelapse cameras with limited /
expensive cellular bandwidth.

Formats supported:
* AV1 bitstream: Super low bandwidth option, exploits the similarity between
  stationary webcam images for compression efficiency. Typical compression is
  5-10x better than JPEGs at 85% quality. Can be played back by concatenating
  individual frames. A browser based viewer with client side re-muxing is a
  TODO.
* Plain JPEGs: Simple standby, not very bandwidth efficient.

## Permissions required

| Permission | Why |
|---|---|
| `CAMERA` | Capture photos |
| `INTERNET` | Upload to server |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` | Keep service alive |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `POST_NOTIFICATIONS` | Show foreground service notification (Android 13+) |


## Minimum requirements
- Android 8.0 (API 26) or higher
- Rear-facing camera
