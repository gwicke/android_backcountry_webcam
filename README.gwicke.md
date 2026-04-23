## Compile
/opt/gradle-8.14.4/bin/gradle assemble

## sign

cd ~/src/android/keys
apksigner sign --ks-key-alias selfsigned --ks selfsigned.keystore ~/src/phone_webcam/CameraUploader_v9/app/build/outputs/apk/release/app-release-unsigned.apk
cp ~/src/phone_webcam/CameraUploader_v9/app/build/outputs/apk/release/app-release-unsigned.apk ~/dav/share/app.apk
