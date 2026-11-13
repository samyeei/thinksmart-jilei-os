# ThinkSmart View · Jile OS V5

[简体中文](README.md)

Jile OS V5 is a learning launcher for compatible Android 8.1 firmware (API 27, ARMv7). Parents can approve apps and websites, view foreground usage, and customize the home screen. The on-device parent panel shows the local management address.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK directory. Install Gradle 9.8.2, Android SDK 37.2 and Build Tools 36.0.0. Run `python3 build.py` to produce `dist/jile-os-v5.apk` using a local test key. Set `JILE_GRADLE` if Gradle is outside PATH.

Verify the device and recovery materials before using `adb -s <serial> install -t dist/jile-os-v5.apk`. Use `-r` for updates signed with the same key. On first setup, enter the setup code `123456` on the device and create your own six-digit parent PIN. Enable the accessibility service for the in-app Home button, notification access for media controls, and usage access for foreground reports. Check the device management and recovery path before enabling Android Lock Task.

The parent web interface uses HTTP on a trusted home network. Screen capture requires the user's awareness and system authorization. The user supplies compatible firmware, Google Play services, and other apps. The package excludes private device data, third-party APKs, the third-party learning graph, and the legacy website compatibility bundle.

Copyright © 2026 Samyeei. Project source is available under the [PolyForm Noncommercial 1.0.0](LICENSE) license for permitted noncommercial uses. Third-party dependencies retain their own licenses.

## OTA updates

With device management enabled, choose Check and update in Parent Settings on the device or in the web dashboard. The app checks the built-in GitHub feed only when requested by a parent. Run `python3 scripts/prepare_ota.py <apk> <output-directory>` and host the manifest and APK in the same HTTPS directory. The current client requires direct HTTP 200 responses. Updates require the same signing key and a higher versionCode.

[V5 GitHub update manifest](https://raw.githubusercontent.com/samyeei/thinksmart-view-jile-os-v5/refs/heads/ota/release.json)
