# Voice Memo (음성 메모)

운전 중 떠오르는 생각을 **아무것도 손대지 않고** 음성으로 바로바로 메모하고, 주차 후에 정리하는 앱.

패키지: `works.eub.voicememo`

## 배경

운전 중 「이거 나중에 꼭 해야지」「아 그거 사야 하는데」 같은 생각이 스쳐 지나갈 때가 있다. 핸드폰을 잡고 메모하면 위험하고, 그냥 지나치면 도착할 때쯤이면 잊어버린다.

기존 음성 메모 앱들은 Android Auto를 지원하지 않는다. Android Auto의 음성 입력은 Google Assistant 전용이라, 운전자가 원하는 시점에 임의의 메모를 남길 수 있는 방법이 없다.

이 앱은 **Android Auto 화면에서 바로 녹음 버튼을 누르고 생각을 말한 뒤, 저장 버튼 하나로 끝내는** 흐름을 만든다. 텍스트로 변환된 메모는 도착 후 폰에서 편집·삭제하거나, 향후 Google Tasks로 보낼 수 있다.

## 동작 흐름

```
  주행 중                          주차 후
  ──────                          ──────
  1. "녹음" 버튼 누름               1. 저장된 메모 목록 확인
  2. 생각을 말함 (텍스트로 변환)     2. 불필요한 것 스와이프로 삭제
  3. "저장" 버튼 누름               3. 수정할 것 탭해서 편집
  4. 다음 메모로                    4. (향후) 오른쪽 스와이프로
                                    Google Tasks로 전송
```

핵심은 **주행 중에는 빠르게 남기고, 주차 후에만 정리한다**는 것이다. 운전자가 메모 목록을 스크롤하거나 편집하는 일은 없다.

## 기능

### Android Auto (차량)

- **원버튼 녹음**: "녹음" → 말하기 → "저장"으로 끝. 차량 화면에 최소한의 정보만 표시
- **일시정지/재개**: 생각이 끊길 때 일시정지 후 이어서 말하기
- **메모 상세 보기**: 저장된 메모의 전체 텍스트 확인 + 삭제

### 폰 UI

- **전체 텍스트 편집**: 탭하면 편집 다이얼로그에서 텍스트 수정
- **스와이프 삭제**: 왼쪽으로 스와이프하면 삭제 + UNDO 스ackbar (취소 가능)
- **일괄 삭제**: 길게 눌러 선택 모드 진입 → 전체 선택/해제 → 한 번에 삭제
- **오른쪽 스와이프 (예정)**: Google Tasks로 전송

### 음성 인식

- Android `SpeechRecognizer` API 사용 (별도 서버 없음, 오프라인 동작 가능)
- 한국어(`ko-KR`) 기본 설정
- 일정 시간 말이 없으면 자동 일시정지 (운전 중 무음 상태 대응)

## 아키텍처

```
voice-memo/
├── app/                        # 폰 UI (Jetpack Compose)
│   └── ui/VoiceMemoScreen.kt   # 메모 목록 + 녹음 + 편집 + 삭제 + 선택
├── common/
│   ├── data/                   # Room DB, Entity, Repository
│   └── car-app-service/        # Android Auto Car App Service
│       └── screen/
│           ├── MemoListScreen.kt    # 차량용 메모 목록 + 녹음
│           └── MemoDetailScreen.kt  # 차량용 메모 상세 + 삭제
└── gradle/libs.versions.toml  # 의존성 관리
```

모듈 분리 이유: `common/data`와 `common/car-app-service`는 폰 앱과 별도 프로세스의 Car App Service에서 모두 사용된다. Room DB는 `VoiceMemoApplication`을 통해 Car 프로세스에서 직접 접근한다.

## 빌드

```bash
cd voice-memo
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

릴리즈 AAB:
```bash
./gradlew bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

## Android Auto 테스트

USB 연결 후 Android Auto 개발자 모드에서 테스트:

```bash
# 음성 인식 권한 부여 (DHU 환경)
adb shell pm grant works.eub.voicememo android.permission.RECORD_AUDIO

# Android Auto 개발자 모드 활성화
# 설정 → 앱 → Android Auto → 개발자 설정 → 개발자 모드 활성화
# → "알 수 없는 소스 허용" 켜기
```

DHU (Desktop Head Unit) 실행: Android Auto 개발자 설정에서 "Desktop head unit" 선택.

## 권한

| 권한 | 용도 | 부여 방식 |
|------|------|----------|
| `RECORD_AUDIO` | 음성 인식 | **런타임 요청** (폰) / `adb shell pm grant` (DHU) |
| `INTERNET` | (향후 Google Tasks 동기화용) | 설치 시 자동 |

## 데이터 구조

Room DB `voice_memos` 테이블:

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long (자동생성) | 기본키 |
| `title` | String | 내용 앞 30자 + "..." |
| `content` | String | 전체 텍스트 |
| `createdAt` | Long | 생성 시각 (epoch millis) |
| `syncedToTasks` | Boolean | Google Tasks 동기화 여부 (예약) |
| `tasksId` | String? | Google Tasks 항목 ID (예약) |

## 향후 계획

- **Google Tasks 연동**: 오른쪽 스와이프로 현재 메모를 Google Tasks 항목으로 전송 (단방향, 완전 동기화 아님)
- **음성 파일 저장**: 변환된 텍스트와 함께 원본 음성 녹음 파일 보관
- **녹음 시간 표시**: 각 메모의 녹음 시간 표시

## 참고

- **SDK 28+ (Android 9 Pie)**: `SpeechRecognizer` API 사용
- **의존성**: Jetpack Compose, Room, AndroidX Car App 1.7.0
- Car App Service는 `minCarApiLevel: 1` (DHU 포함 모든 환경 호환)
