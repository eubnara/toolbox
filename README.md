# toolbox

직접 만들어 쓰는 잡다한 유틸 모음. 주로 Linux 데스크탑 / 노트북 환경 보강용.

각 도구는 자체 디렉터리에 자기 README 와 설치 스크립트를 가진 독립된 단위로 두고, 한 줄짜리 단발 스크립트는 최상위 `bin/` 에 평평하게 모은다.

## 도구 목록

| 도구 | 설명 |
|---|---|
| [charger-watts](./charger-watts) | 연결된 USB-C PD 충전기의 협상 와트수를 표시 (CLI + KDE Plasma 6 패널 위젯). ThinkPad EC 의 `HWAT` 필드 직접 읽기. |
| [pr-keepalive](./pr-keepalive) | 장기 GitHub PR 에 주기적으로 짧은 코멘트를 달아 stale-bot 의 자동 close 를 막음. systemd user timer 로 부팅 + 매일 1회. |

## 디렉터리 구조

```
toolbox/
├── README.md
├── bin/                 # 한 파일짜리 스크립트 (PATH 에 두고 바로 실행)
└── <tool>/              # 다중 파일 프로젝트
    ├── README.md
    └── setup.sh
```

## 새 도구 추가할 때

- 파일이 두 개 이상이면 `<tool>/` 디렉터리로 분리하고 자체 README · `setup.sh` 를 둔다.
- 한 파일짜리면 `bin/` 에 그대로 넣고 `chmod +x` 한 뒤 최상위 README 의 "도구 목록" 표에 한 줄 추가한다.
