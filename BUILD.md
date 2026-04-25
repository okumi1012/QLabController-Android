# Build Guide

## Requirements
- JDK 17 or newer
- Android SDK API 34
- Git

## Debug Build
```bash
./gradlew assembleDebug
```

Built APK: `app/build/outputs/apk/debug/app-debug.apk`

## Release Build
Release signing information must be provided through Gradle properties or environment variables. Do not commit keystore files or passwords.

Required keys:
- `QLAB_RELEASE_STORE_FILE`
- `QLAB_RELEASE_STORE_PASSWORD`
- `QLAB_RELEASE_KEY_ALIAS`
- `QLAB_RELEASE_KEY_PASSWORD`

Example local `~/.gradle/gradle.properties`:
```properties
QLAB_RELEASE_STORE_FILE=/absolute/path/to/qlab-release.jks
QLAB_RELEASE_STORE_PASSWORD=change-me
QLAB_RELEASE_KEY_ALIAS=qlab-controller
QLAB_RELEASE_KEY_PASSWORD=change-me
```

Build:
```bash
./gradlew assembleRelease
```

Built APK: `app/build/outputs/apk/release/app-release.apk`

If these values are missing, Gradle can still build an unsigned release variant, but it will not be ready for distribution.

## GitHub Release APKs
The GitHub Actions release workflow runs when a `v*` tag is pushed, or when it is started manually from the Actions tab.

It always builds and uploads an installable debug APK:
- `qlab-controller-debug.apk`

If release signing secrets are configured, it also uploads a signed release APK:
- `qlab-controller-release.apk`

Required GitHub repository secrets for signed release APKs:
- `QLAB_RELEASE_KEYSTORE_BASE64`
- `QLAB_RELEASE_STORE_PASSWORD`
- `QLAB_RELEASE_KEY_ALIAS`
- `QLAB_RELEASE_KEY_PASSWORD`

Create `QLAB_RELEASE_KEYSTORE_BASE64` from the keystore file:
```bash
base64 -w 0 qlab-release.jks
```

On Windows PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("qlab-release.jks"))
```

## Create a New Keystore
```bash
keytool -genkeypair \
  -v \
  -keystore qlab-release.jks \
  -alias qlab-controller \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Store the keystore outside the repository and back it up securely.

## Version Updates
Update `app/build.gradle.kts`:
```kotlin
defaultConfig {
    versionCode = 2
    versionName = "1.1"
}
```

## ProGuard/R8
Release builds use the project rules in `app/proguard-rules.pro` with the default Android optimized rules.
