# 슬리핑 (Sleeping)

> 버튼 한 번으로 차 안을 수면 환경으로 바꾸고, 15분 뒤 원래대로 되돌리며 깨워 주는 Pleos Connect 앱

시트·공조·조명·차광·화이트 노이즈를 한 번에 수면용으로 세팅하고, 시간이 되면 알람과 음성 안내로 깨운 뒤 차량 상태를 수면 전으로 복원합니다. 기어 P(+ 주차 브레이크)일 때만 시작하고, 배터리·연료가 15% 이하로 떨어지거나 기어가 바뀌면 바로 깨웁니다.

## APK

- 위치: [`apk/_슬리핑_release.apk`](apk/_슬리핑_release.apk) (release 빌드, 서명됨)
- 설치: `adb install -r "apk/_슬리핑_release.apk"`

## 실행 방법

1. Pleos Connect Emulator를 실행합니다 (아래 테스트 환경 참고).
2. APK를 설치합니다: `adb install -r "apk/_슬리핑_release.apk"`
3. 앱 목록에서 **슬리핑**을 실행합니다.
4. 기어 P 상태에서 **수면 시작**을 누르면 수면 세팅이 적용되고 15분 타이머가 시작됩니다.
5. 15분이 지나면 알람·음성 안내와 함께 수면 전 상태로 복원되고, 운전석 창문이 1분 동안 열렸다 닫힙니다. **지금 일어나기**로 바로 깰 수도 있습니다.

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (`system-images;android-34;pleos_car_emulator;arm64-v8a`, Android 14 / API 34) |
| 화면 규격 | **2560 × 1440**, 160 dpi |
| 호스트 | macOS (Apple Silicon) |
| 차량 SDK 권한 | 별도 앱 등록 불필요 (Vehicle SDK, TTS ON_DEVICE 모드) |

- **주차 브레이크:** 에뮬레이터 가상 콘솔로는 `PARKING_BRAKE_ON` 값을 켤 수 없어 항상 false입니다. 에뮬레이터에서 수면을 시작하려면 디버그 빌드(`./gradlew installDebug`)를 설치하고 홈 화면의 **[개발용] 주차 브레이크 확인 건너뛰기** 스위치를 켜 주세요. release 빌드에는 이 스위치가 없습니다.
- **기어 P 설정:** 가상 콘솔(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/)에서 기어를 P로 둡니다.
- **AVD 마이크 입력:** macOS에서 `hw.audioInput=yes`이면 부팅 직후 에뮬레이터가 멈출 수 있습니다. AVD `config.ini`에 `hw.audioInput=no`를 지정하세요.
- **에뮬레이터 제약:** 시트 등받이·블라인드 모터와 풍량·풍향은 에뮬레이터에서 동작하지 않아 "적용되지 않은 기능"으로 건너뜁니다. 실차 차량 기능을 대상으로 구현했습니다.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 플랫폼 | Android Automotive OS (Pleos Connect), minSdk 26 · targetSdk 36 |
| 언어 · UI | Kotlin 2.0.21, Jetpack Compose (Material 3) |
| 비동기 | Kotlin Coroutines · Flow |
| 차량 제어 | Pleos Connect **Vehicle SDK** 2.3.2 (Seat, HVAC, Light, Window, EvBattery, Fuel) |
| 차량 상태 | AAOS **CarPropertyManager** (`GEAR_SELECTION`, `PARKING_BRAKE_ON`) |
| 음성 안내 | Pleos Connect **TextToSpeech SDK** 3.3.1 (ON_DEVICE) |
| 오디오 | AndroidX **Media3** ExoPlayer 1.5.1 (화이트 노이즈 · 알람 반복 재생) |
| 백그라운드 | Foreground Service (LifecycleService) |
| 빌드 | Gradle 8.13, AGP 8.13.2, JDK 21 |

## 빌드

```bash
./gradlew assembleDebug     # 개발용 (주차 브레이크 건너뛰기 스위치 포함)
./gradlew assembleRelease   # 제출용
```

- JDK 21로 빌드 (Gradle 8.13은 JDK 25 미지원 — Android Studio 최신 JBR이 25면 Gradle JDK를 21로 지정), compileSdk 36 (`android.car` 라이브러리 포함)
- release 서명: 루트에 `keystore.properties`(storeFile·storePassword·keyAlias·keyPassword)를 두면 `./gradlew assembleRelease`로 서명된 APK가 만들어집니다 (키와 설정 파일은 저장소에 포함하지 않음).
- 에뮬레이터 설정: [에뮬레이터 설정 가이드](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)

## 구조

```
app/src/main/java/com/sleeping/app/
├── SleepConfig.kt            1차 고정값 (15분, 23℃, 70% 등)
├── vehicle/
│   ├── VehicleController.kt  Vehicle SDK: 상태 저장 → 적용 → 복원, 창문 환기, 잔량 감시
│   ├── ParkingMonitor.kt     AAOS 기어·주차 브레이크 감시
│   └── Await.kt              SDK 콜백 → suspend 변환 (실패·타임아웃은 건너뛰기)
├── session/
│   ├── SleepService.kt       포그라운드 서비스: 수면 세션 전체 흐름
│   └── SleepSession.kt       화면과 공유하는 세션 상태
├── audio/                    Media3 반복 재생, Pleos TTS 기상 안내
└── ui/                       Compose 화면 (시작 · 수면 중 · 기상)
tools/generate_audio.py       브라운 노이즈·알람 음원 생성 (직접 생성, 저작권 문제 없음)
```
