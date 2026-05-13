## Compile
/opt/gradle-8.14.4/bin/gradle assemble

## sign

cd ~/src/android/keys
apksigner sign --ks-key-alias selfsigned --ks selfsigned.keystore /path/to/app/build/outputs/apk/release/app-release-unsigned.apk
cp /path/to/app/build/outputs/apk/release/app-release-unsigned.apk /path/to/out/app.apk
