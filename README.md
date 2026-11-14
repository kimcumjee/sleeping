# 슬리핑 (Sleeping)

차 안에서 짧은 시간이라도 깊게 자고 제때 깨어날 수 있도록, 버튼 한 번으로 시트·공조·조명·차광·화이트 노이즈를 수면 환경으로 바꿔 주는 **Pleos Connect** 앱입니다.

## 1차 개발 범위

| 기능 | 내용 |
|---|---|
| 안전 확인 | 기어 P + 주차 브레이크일 때만 시작 (주차 브레이크를 읽을 수 없으면 기어 P만 확인) |
| 원터치 수면 세팅 | 운전석 등받이 70% 눕힘·가장 뒤로, 열선 1단, 23℃·최저 풍량·발 쪽·외기 유입, 실내등·독서등 끔, 앰비언트 10%·따뜻한 색, 스마트글래스·블라인드 차광(지원 차량), 브라운 노이즈 |
| 수면 중 관리 | 15분 타이머, 배터리·연료 15% 이하 또는 기어 변경 시 즉시 기상 |
| 기상 | 알람 + 음성 안내(TTS), 수면 전 상태로 한 번에 복원, 운전석 창문 1분 환기 |

문서에는 있지만 1차 범위에서 제외: 커스텀·프리셋, 차종별 대응(지원하지 않는 기능은 건너뛰기만 함), 음성 조작(STT → 이후 Gleo 연동), 스티어링 열선.

## 사용 SDK

- Pleos Connect **Vehicle SDK** 2.3.2: Seat, HVAC, Light, Window, EvBattery, Fuel
- Pleos Connect **TextToSpeech SDK** 3.3.1 (ON_DEVICE 모드, 앱 등록 불필요)
- AAOS **CarPropertyManager**: `GEAR_SELECTION`, `PARKING_BRAKE_ON`
- **Media3** ExoPlayer: 화이트 노이즈·알람 반복 재생

## 빌드

```bash
./gradlew assembleDebug
```

- JDK 21로 빌드 (Gradle 8.13은 JDK 25 미지원 — Android Studio 최신 JBR이 25면 Gradle JDK를 21로 지정), compileSdk 36 (`android.car` 라이브러리 포함)
- 실행은 Pleos Connect Emulator(AVD)에서 합니다. [에뮬레이터 설정 가이드](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)

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

## 에뮬레이터 테스트 결과 (Pleos Connect Emulator v2.1.0, 2026-11-14)

| 항목 | 결과 |
|---|---|
| 기어 P · 잔량 읽기 | ✅ (연료 잔량은 전기차라 미지원 → 건너뜀) |
| `PARKING_BRAKE_ON` 읽기 | ✅ 읽힘 (에뮬레이터에서는 항상 false) |
| 앰비언트 10% · 색상 | ✅ 색상은 RGB int(`0xFF8C3C` → `#ff8c3c`) 확인 |
| 시트 열선 1단 · 23℃ | ✅ |
| 공조 전원 켜기 / 복원 | ✅ |
| 풍량 · 풍향 | ⚠️ 에뮬레이터가 NOT_AVAILABLE 반환 → 건너뜀 표시 |
| 등받이 · 블라인드 이동 | ⚠️ 에뮬레이터에서 위치가 변하지 않음 → 3초 무변화 시 건너뜀. 방향은 실차에서 확인 필요 |
| 스마트글래스 | 미지원 차량 → 건너뜀 |
| 기상: 알람 · 복원 · 창문 1분 환기 | ✅ 창문 열림(2) → 1분 뒤 닫힘(0) |

## 실차에서 확인할 것 (TODO)

- 시트 위치 값의 방향 (AAOS 관례 가정: 등받이 값이 작을수록 눕힘, 앞뒤 값이 작을수록 뒤)
- 블라인드 '닫힘' 방향 (max 가정)
- 풍량 · 풍향 설정 (에뮬레이터 미지원)

## 에뮬레이터 테스트 메모

- 가상 콘솔: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/
- 가상 콘솔은 주차 브레이크 **경고등**만 켤 수 있고 `PARKING_BRAKE_ON` 값은 항상 false → 디버그 빌드 홈 화면의 **[개발용] 주차 브레이크 확인 건너뛰기** 스위치로 기어 P만 확인하고 시작 (릴리스 빌드에는 스위치 없음)
- AVD 마이크 입력(`hw.audioInput`)이 켜져 있으면 macOS 마이크 권한 문제로 부팅 직후 멈춤 → `hw.audioInput=no`
