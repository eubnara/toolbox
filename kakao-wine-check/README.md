# kakao-wine-check

KakaoTalk 을 Wine 으로 돌릴 때 쓰는 로컬 Wine 패치를 언제 걷어내도 되는지 알려주는 월간 알림.

## 배경

Wine 11.0 에서 모니터를 뽑거나 USB-C 모니터가 잠금 화면에서 잠들면, XRandR 에러(`BadRROutput` / `BadRRCrtc` / `BadRRMode`)로 `explorer.exe` 가 죽는다. 그러면 KakaoTalk 트레이 아이콘이 사라지고 KakaoTalk 에서 복사가 안 된다 ([WineHQ bug 60566](https://bugs.winehq.org/show_bug.cgi?id=60566)).

그래서 `/opt/wine-stable` 을 `~/opt/wine-randr` 로 복사하고, 이 에러를 무시하도록 패치한 `winex11.so` 로 KakaoTalk 을 띄운다. 이 패치는 업스트림에서 고쳐지거나 시스템 Wine 이 업그레이드되면 필요 없어진다.

## 동작

systemd user timer 가 한 달에 한 번 `kakao-wine-check` 실행. 아래 경우 `notify-send` 로 알린다.

- bug 60566 이 `RESOLVED` / `CLOSED` / `STAGED` 가 됨 -> 릴리스된 Wine 에 수정이 들어갔으면 업그레이드하고 로컬 패치 제거
- bug 상태를 못 읽음
- `~/opt/wine-randr` 패치본이 없음
- 시스템 Wine 버전이 패치본과 달라짐 -> KakaoTalk 이 이미 패치 없이 돌고 있음

## 설치

```bash
bash setup.sh
```

`~/bin/kakao-wine-check`, `~/.config/systemd/user/kakao-wine-check.{service,timer}` 설치 후 timer 활성화. 다시 실행해도 안전.

필요한 것: `curl`, `jq`, `notify-send` (libnotify-bin). 패치된 Wine 복사본(`~/opt/wine-randr`) 자체는 이 도구가 만들지 않는다.

## 제거

```bash
systemctl --user disable --now kakao-wine-check.timer
rm ~/bin/kakao-wine-check ~/.config/systemd/user/kakao-wine-check.{service,timer}
systemctl --user daemon-reload
```
