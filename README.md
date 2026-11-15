# 슬리핑 (Sleeping)

버튼 한 번으로 차 안을 수면 환경으로 바꾸고, 15분 뒤 원래대로 되돌리며 깨워 주는 Pleos Connect 앱

## 실행 방법

1. Pleos Connect Emulator를 실행합니다.
2. APK를 설치합니다.
   ```bash
   adb install -r "apk/_슬리핑_release.apk"
   ```
3. 앱 목록에서 **슬리핑**을 실행합니다.
4. 기어 P 상태에서 **15분 수면 시작**을 누릅니다.

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| Pleos Connect 에뮬레이터 버전 | v2.1.0 (Android 14, API 34, arm64-v8a) |
| 화면 규격 | 2560 × 1440, 160 dpi |

- 기어는 P여야 시작할 수 있습니다. 에뮬레이터 가상 콘솔(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/)에서 기어를 P로 둡니다.
- macOS에서는 AVD `config.ini`에 `hw.audioInput=no`를 지정해야 부팅 직후 에뮬레이터가 멈추지 않습니다.

## APK

[`apk/_슬리핑_release.apk`](apk/_슬리핑_release.apk)
