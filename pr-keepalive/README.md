# pr-keepalive

장기 GitHub PR 이 stale-bot 에 의해 자동 close 되지 않도록, **사용자 컨펌을 받아** 짧은 코멘트를 한 번씩 달아주는 도구.

Apache 같은 프로젝트는 [`actions/stale`](https://github.com/actions/stale) 로 N일(보통 100일) 동안 활동 없는 PR 을 자동으로 닫는다. 코멘트 한 줄이면 inactivity 타이머가 리셋되므로(force-push / CI 재실행 불필요), 임계치에 가까워질 때 한 번씩만 코멘트를 달면 된다.

## 동작

1. systemd user timer 가 부팅 후 + 매 24h 마다 `pr-keepalive` 실행
2. 도구는 각 PR 의 마지막 활동 시점 확인 (`gh` CLI 사용)
3. 임계치(`threshold_days`, 기본 80일) 넘은 PR 은 **state.json 에 pending 으로 마킹** + 데스크탑 알림 한 번
4. 사용자가 알림 보고 `pr-keepalive --confirm` 실행
5. 각 pending PR 마다 미리보기 보여주고 `[y/N/q]` 물어봄 → `y` 하면 코멘트 발송

자동 포스팅은 **하지 않는다.** 항상 사용자 OK 거친다.

## 동작이 보수적인 이유

- `threshold_days=80`, Apache 의 100일 stale window → PR 당 연 4~5회 코멘트가 한도
- 임계치 도달 알림은 **PR 이 처음 pending 으로 넘어갈 때만** (이미 pending 인 건 추가 알림 없음)
- 코멘트 본문은 짧은 한 줄 — 리뷰어 시간 빼앗지 않음

## 설치

```sh
git clone https://github.com/eubnara/toolbox.git
cd toolbox/pr-keepalive
bash setup.sh
```

설치 후 PR 목록 편집:

```sh
$EDITOR ~/.config/pr-keepalive/config.toml
```

`setup.sh` 는 idempotent — 코드 업데이트 후 다시 돌려도 사용자 config 는 건드리지 않는다.

## 설정

`~/.config/pr-keepalive/config.toml`:

```toml
prs = [
  "apache/hadoop#8458",
  "apache/spark#42",
]
threshold_days = 80
comment = "Friendly bump — still active, please keep open."
```

전체 형식은 [`config.example.toml`](./config.example.toml) 참고.

## 사용법

```
pr-keepalive               # 체크 + pending 마킹 + 알림 (timer 가 호출)
pr-keepalive --confirm     # pending 인 것들 인터랙티브로 검토 후 포스팅
pr-keepalive --dry-run     # 체크만, 마킹/포스팅 안 함
pr-keepalive --quiet       # skipped 라인 숨김 (cron 로그용)
pr-keepalive --no-notify   # 알림 끄기
```

상태 확인:

```sh
systemctl --user list-timers pr-keepalive.timer
journalctl --user -u pr-keepalive.service --since '7 days ago'
cat ~/.local/state/pr-keepalive/state.json
```

## `--confirm` 흐름 예시

```
$ pr-keepalive --confirm
1 PR(s) pending confirmation:

──────────────────────────────────────────────
  apache/hadoop#8458 — HADOOP-17861. improve YARN Registry DNS Server qps
  https://github.com/apache/hadoop/pull/8458
  Last activity: 2026-07-15 12:34 UTC (82d ago)
  Pending for:   2d
  Comment to post:
    │ Friendly bump — still active, please keep open.
  Post comment? [y/N/q] y
  -> posted ✓

Done. posted=1 cleared=0 deferred=0
```

옵션:
- `y` → 코멘트 포스팅, pending 해제
- `N` (또는 엔터) → 그냥 두기 (다음에 다시 물어봄)
- `q` → 중단, 남은 PR 은 pending 유지

`--confirm` 은 포스팅 직전에 PR 상태를 한번 더 확인한다 — 누가 그 사이에 코멘트를 달았거나 PR 이 닫혔으면 자동으로 pending 해제하고 포스팅 안 함.

## 구조

추후 Tauri UI 등 다른 frontend 가 같은 로직을 재사용할 수 있도록 Cargo workspace 로 분리:

```
pr-keepalive/
├── core/         # lib: PR 체크, gh CLI 래핑, state I/O
├── cli/          # bin: systemd timer 가 호출하는 헤드리스 도구
└── (tauri/)      # placeholder — 향후 트레이 UI (각 PR 의 next_bump_at, pending 표시)
```

`gh` 호출은 전부 `core` 에만 있고, `cli` 와 미래의 Tauri 는 동일한 lib 를 import 한다. State 는 단일 JSON 파일이라, UI 는 GitHub 에 다시 안 물어보고도 "다음 bump 예정일", "pending 여부" 를 그대로 보여줄 수 있다.

## Roadmap

- [ ] Tauri 트레이 UI: 각 PR 의 `state` / `last_updated_at` / `next_bump_at` / `pending_confirmation` 표시. pending 인 항목은 클릭으로 confirm.
- [ ] PR 별 `threshold_days` 오버라이드 (저장소마다 stale window 가 다른 경우)
- [ ] pending 이 N일 이상 방치되면 알림 재전송

## 요구사항

- `cargo` (Rust 1.74+)
- `gh` CLI, `repo` 스코프로 인증 (`gh auth login`)
- `systemd --user`
- (선택) `notify-send` (libnotify-bin 패키지) — 알림용

## 제거

```sh
systemctl --user disable --now pr-keepalive.timer
rm ~/.config/systemd/user/pr-keepalive.{service,timer}
rm ~/bin/pr-keepalive
# 사용자 데이터까지 지우려면:
# rm -r ~/.config/pr-keepalive ~/.local/state/pr-keepalive
systemctl --user daemon-reload
```
