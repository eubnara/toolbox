# ClipFlood

삼성 One UI 클립보드 기록 자동 정리 앱

패키지: `works.eub.clipflood`

## 배경

삼성 One UI는 사용자가 복사한 텍스트·이미지·링크 등을 **암호화 없이 일반 텍스트로 기기에 영구 저장**합니다. 자동 삭제 메커니즘이 없어, 비밀번호·계좌번호 등 민감 정보가 클립보드 기록에 계속 남아 보안 위험이 됩니다.

- **기기 분실/도난** 시 잠금 해제된 상태에서 모든 클립보드 기록 노출 가능
- **악성코드** (StilachiRAT 등)가 클립보드 데이터를 실시간 탈취 가능
- 타사 키보드(Gboard 등)의 자동 삭제 설정을 삼성 시스템 레벨 클립보드가 무력화

삼성도 자사 커뮤니티에서 이 문제를 **보안 위험**으로 인정했으나, 현재까지 공식 해결책이 나오지 않은 상태입니다.

> 참고 기사: [삼성 스마트폰 One UI, 치명적 보안 결함.. 사용자 데이터 '무방비 노출'](https://www.securityfact.co.kr/6246) - 시큐리티팩트, 2025.04.23
> (본 앱은 해당 기사에서 제기된 문제의 실용적 해결책으로 제작되었습니다.)

## 작동 원리

Samsung의 클립보드 기록은 제한된 수의 항목을 유지합니다. 이 앱은 N개의 더미 클립(`.0`, `.1`, `.2`, ...)을 연속으로 주입(flood)하여 기존 기록을 밀어내는 방식으로 동작합니다. (기본값 70개, 앱 내에서 조정 가능)

1. `ClipboardManager.setPrimaryClip()`을 N회 호출 (기본 70회)
2. 각 호출 시 유니크한 짧은 문자열(`.$i`)로 기존 클립을 교체
3. 기존 기록이 모두 밀려나고 더미 클립만 남음
4. 마지막에 `.` 하나만 남기고 종료

Edge panel에 고정(pin)된 클립은 영향을 받지 않습니다.

> **클립보드 저장 개수 참고**
> - 삼성 공식 가이드: One UI 3.0+ 기준 **최대 40개** ([삼성전자서비스](https://www.samsungsvc.co.kr/solution/354911))
> - One UI 6.1 사용자 제보: 60개 이상으로도 계속 저장되어 **고정된 상한이 없는 것으로 보임** ([Samsung Members](https://r1.community.samsung.com/t5/galaxy-s/oneui6-1-samsung-is-keeping-even-more-passwords/td-p/26302649))
> - 본 앱은 기본 70개를 플러드하며, 개수를 직접 조정할 수 있습니다.

## 기능

- **수동 플러드**: 버튼 한 번으로 N개 더미 클립 즉시 주입 (기본 70개, 앱 내에서 개수 조정 가능)
- **스케줄 자동 실행**: 원하는 시간에 매일 자동 실행 (기본 02:00)
  - `AlarmManager.setAlarmClock()` 사용 → Doze 모드에서도 정확히 실행
  - `ForegroundService`로 백그라운드에서 안정적 동작
  - 부팅 시 `BootReceiver`가 스케줄 자동 재등록

## 요구사항

- 삼성 갤럭시 One UI 기기 (Android 9+)
- Samsung Keyboard 또는 Samsung clipboard edge panel 사용 환경

## 빌드

```bash
cd clipflood
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

릴리즈 AAB 빌드:
```bash
./gradlew bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

## 권한

| 권한 | 용도 | 부여 방식 |
|------|------|----------|
| `RECEIVE_BOOT_COMPLETED` | 부팅 후 스케줄 재등록 | 설치 시 자동 |
| `POST_NOTIFICATIONS` | ForegroundService 알림 표시 (Android 13+) | **런타임 요청** |
| `FOREGROUND_SERVICE` | 백그라운드 플러드 실행 | 설치 시 자동 |
| `FOREGROUND_SERVICE_DATA_SYNC` | foregroundServiceType 지정 (Android 14+) | 설치 시 자동 |
| `SCHEDULE_EXACT_ALARM` | 정확한 시간에 알람 실행 (Android 12+) | 설치 시 자동 |
| `USE_EXACT_ALARM` | 정확한 시간에 알람 실행 (Android 14+) | 설치 시 자동 |

## 보안

소스 코드 공개 리뷰 결과:
- 개인정보(이메일, IP, API 키, 토큰) 없음
- 모든 컴포넌트 `exported="false"` — 외부 앱 호출 차단
- `PendingIntent.FLAG_IMMUTABLE` — 인텐트 하이재킹 방지
- `allowBackup="false"` — ADB 클립보드 백업 방지
- 네트워크 미사용, 타앱 데이터 읽지 않음

## 참고

- **SDK 26+ (Android 8.0+)**: `AlarmManager.setAlarmClock()` + `ForegroundService` 사용
- **의존성 최소화**: AndroidX, AppCompat 외 추가 라이브러리 없음
- ContentProvider 방식 (`content://com.samsung.android.clipboardprovider/clipboard`)은 OneUI 5+에서 차단되어 사용 불가
