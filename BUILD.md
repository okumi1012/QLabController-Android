# 빌드 가이드

## 로컬 빌드 방법

### 요구사항
- JDK 17 이상
- Android SDK (API 34)
- Git

### 디버그 빌드
```bash
./gradlew assembleDebug
```

빌드된 APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

### 릴리즈 빌드
```bash
./gradlew assembleRelease
```

빌드된 APK 위치: `app/build/outputs/apk/release/app-release.apk`

> **참고**: 릴리즈 빌드는 keystore로 자동 서명됩니다.

## GitHub Actions를 통한 자동 빌드

### 릴리즈 생성 방법

1. 버전 태그 생성:
```bash
git tag v1.0.0
git push origin v1.0.0
```

2. GitHub Actions가 자동으로:
   - 릴리즈 APK 빌드
   - GitHub Release 생성
   - APK 파일 첨부

### 수동 워크플로우 실행

GitHub 저장소의 **Actions** 탭에서 "Android Release Build" 워크플로우를 수동으로 실행할 수 있습니다.

## Keystore 정보

- **위치**: `app/keystore/qlab-release.jks`
- **Store Password**: `android123`
- **Key Alias**: `qlab-controller`
- **Key Password**: `android123`

> ⚠️ **보안 주의**: 프로덕션 환경에서는 반드시 안전한 비밀번호로 변경하고, keystore 파일을 안전하게 보관하세요.

## 버전 업데이트

`app/build.gradle.kts` 파일에서 버전을 업데이트:

```kotlin
defaultConfig {
    versionCode = 2  // 정수 증가
    versionName = "1.1"  // 버전 문자열
}
```

## ProGuard/R8

릴리즈 빌드는 자동으로 코드 최적화 및 난독화를 수행합니다:
- 코드 축소 (minification)
- 리소스 축소 (resource shrinking)
- 난독화 (obfuscation)

설정 파일: `app/proguard-rules.pro`
