# QLab Controller for Android

QLab과 연결하여 기본적인 쇼 컨트롤 기능을 제공하는 Android 리모트 앱입니다.

## 기능

- QLab과 OSC(Open Sound Control) 프로토콜로 연결
- **GO** - 다음 큐 실행
- **PANIC** - 모든 큐 중지

## 사용 방법

1. QLab에서 OSC 설정 활성화 (기본 포트: 53000)
2. 앱에서 QLab이 실행 중인 컴퓨터의 IP 주소 입력
3. Connect 버튼으로 연결
4. GO 또는 PANIC 버튼으로 쇼 제어

## 기술 스택

- Kotlin
- Android SDK (최소 API 24)
- JavaOSC - OSC 통신 라이브러리
- Material Design Components

## 빌드 방법

```bash
./gradlew assembleDebug
```

## 요구사항

- Android 7.0 (API 24) 이상
- QLab 4 또는 5
- 같은 네트워크에 연결된 기기