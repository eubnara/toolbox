# charger-watts

연결된 USB-C PD 충전기의 **협상 와트수** 를 표시하는 Linux용 CLI + KDE Plasma 6 패널 위젯.

macOS 의 `system_profiler SPPowerDataType` (Wattage 항목) 처럼 "지금 몇 W 짜리 충전기가 협상돼서 들어오는 중이지?" 를 KDE 데스크탑에서도 한 눈에 확인하려고 만듦.

```
$ charger-watts
100W
```

> [!CAUTION]
> - **OS 호환성**: `setup.sh` 는 `apt-get` 을 쓰므로 **Debian / Ubuntu / KDE Neon / Mint 등 Debian 계열에서만 자동 설치 가능**. Fedora · Arch · openSUSE 등은 패키지 설치 한 줄을 직접 바꿔야 한다 (`acpica-tools` 패키지명은 동일하거나 비슷함).
> - **하드웨어 호환성**: **ThinkPad P16s Gen 1 (AMD Ryzen 6000) 에서만 실측 테스트** 했다. 다른 머신은 best-effort:
>   - `setup.sh` 가 DSDT 에서 `HWAT` 필드 오프셋을 자동 탐지하므로 같은 필드명을 쓰는 다른 ThinkPad 모델이라면 동작할 가능성이 있음.
>   - Framework / 일부 신형 Dell 같이 UCSI sysfs 가 정상 노출되는 머신은 fallback 경로로 추정값이 나올 수 있음 (정확도는 낮음).
>   - 위 둘 다 안 맞는 머신에서는 항상 `0` 또는 `no charger` 만 출력됨.

## 어떻게 동작하나

표준 sysfs (`/sys/class/power_supply/AC/`) 는 충전기의 와트수를 노출하지 않는다. 그래서 ThinkPad EC (Embedded Controller) 가 협상 결과를 보관하고 있는 바이트를 `ec_sys` 모듈로 직접 읽어온다.

- ThinkPad P16s Gen 1 의 DSDT 에서 `\_SB.PCI0.LPC0.EC0.ECOR` 영역 byte offset `0xC9` 의 `HWAT` 필드가 협상 와트수임을 확인.
- `setup.sh` 가 실행 시점에 그 머신의 DSDT 를 자동 디컴파일해서 `HWAT` 오프셋을 다시 탐지함 → 같은 필드명을 쓰는 다른 ThinkPad 에서도 동작 가능.
- 비-ThinkPad 라서 `HWAT` 가 없으면 `/sys/class/power_supply/ucsi-source-psy-*` 의 `voltage_max × current_max` 로 fallback.

P16s Gen 1 실측:

| 충전기 | `charger-watts` 출력 |
|---|---|
| 20 W PD | `20W` |
| 65 W PD | `65W` |
| 100 W PD | `100W` |
| 140 W PD 3.1 EPR | `100W` (P16s 는 PD 3.0 한계라 100 W 까지만 협상) |

## 설치

Debian / Ubuntu 계열에서:

```bash
git clone https://github.com/eubnara/toolbox.git
cd toolbox/charger-watts
bash setup.sh   # sudo 한 번 묻고, 이후 NOPASSWD 로 동작
```

`setup.sh` 가 설치하는 것:

| 경로 | 용도 |
|---|---|
| `/etc/modules-load.d/ec_sys.conf` | 부팅 시 `ec_sys` 모듈 자동 로드 |
| `/usr/local/sbin/charger-watts-read` | EC 직접 읽는 root 전용 스크립트 (HWAT 오프셋이 박혀 있음) |
| `/usr/local/bin/charger-watts` | 사용자용 wrapper (sudo 자동 처리) |
| `/etc/sudoers.d/charger-watts` | NOPASSWD 항목 — 위젯이 매번 비밀번호 안 묻게 |
| `~/.local/share/plasma/plasmoids/com.eub.chargerwatts` | 이 레포의 `plasmoid/` 디렉터리로 향하는 심볼릭 링크 |

## 사용

CLI:

```bash
$ charger-watts
100W

$ charger-watts --raw
100

$ charger-watts            # 충전기 안 꽂힌 상태
no charger
```

Plasma 위젯:

```bash
kquitapp6 plasmashell && kstart plasmashell &
```

다음 patch 로 plasmashell 이 재시작되면 패널 우클릭 → "위젯 추가하기" → `Charger Watts` 검색 → 패널로 드래그.

위젯은 **순수 event-driven** 으로 동작한다 (폴링 없음). Plasma 의 `powermanagement` 데이터 엔진을 구독하고, 그 엔진은 내부적으로 Solid → UPower D-Bus `PropertiesChanged` 시그널을 받기 때문에 **충전기 연결/해제 edge 에서만** 신호가 들어온다. 그 시점에 EC 를 한 번 읽어 와트수를 갱신하고, 그 외엔 위젯이 깨어나지 않는다. 위젯을 클릭하면 강제 재읽기 (PD 가 edge 없이 재협상되는 경우 대비).

## 제거

```bash
sudo rm -f /usr/local/sbin/charger-watts-read \
           /usr/local/bin/charger-watts \
           /etc/sudoers.d/charger-watts \
           /etc/modules-load.d/ec_sys.conf
rm -rf ~/.local/share/plasma/plasmoids/com.eub.chargerwatts
```

## Plasma 6 외 환경에서

`plasmoid/` 는 KDE Plasma 6 (`X-Plasma-API-Minimum-Version: 6.0`) 에서만 동작하지만, CLI (`charger-watts`) 자체는 데스크탑 환경과 무관하게 작동한다. GNOME 확장 / waybar / polybar / eww 등에서 그대로 호출해 쓸 수 있다.
