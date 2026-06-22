# HassBle

스마트폰을 **BLE → Home Assistant 게이트웨이**로 쓰는 시스템.
**스마트 Android 앱(Companion 앱 유사) + HA 브릿지 컴포넌트** 두 부분으로 구성된다.

ESPHome Bluetooth Proxy처럼 raw 패킷을 전부 던지지 않고, 폰에서 파싱·필터링해
**값이 바뀐 센서만** 보내고 HA가 **정식 엔티티**로 노출한다. 설정(파싱 규칙)은
**git에서 앱이** 불러오고, 사용자가 폰에서 **센서를 골라** 적용한다.
**MQTT를 쓰지 않으며**, 기존 HA URL + 장기 액세스 토큰만 사용한다(추가 포트 없음).

## 구조

```
[Android 앱: 두뇌]                  WebSocket          [HA 컴포넌트 ws_bridge: 범용 브릿지]
  git URL에서 설정 로드 + preset  ◀──(HA WS API)──▶     entity 선언 받으면 → 생성
  광고scan / GATT / OBD 폴링                            state 받으면 → 갱신
  raw → 디코딩 → 값 필터                                switch/number/select/button → 명령 중계
  사용자가 켠 센서만 선언/전송                          (BLE/형식 지식 없음, 범용)
```

- 폰만 BLE를 가지므로 **BLE I/O + 설정/디코딩/필터/선택은 앱**, **HA는 엔티티 생성/갱신만**.
- `ws_bridge`는 BLE 전용이 아니라 **범용**이다 — 인증된 어떤 WebSocket 클라이언트든
  프로토콜만 맞으면 엔티티가 생성된다. HA Companion 앱의 sensor register/update와 같은 패턴.

## 무엇을 하나

1. **광고 파싱** — passive scan한 advertisement raw를 HA가 디코딩 → 센서
2. **OBD (ELM327)** — vLinker 등 BLE OBD 어댑터 폴링. 기존 ESPHome
   [`ble_elm327`](https://github.com/eigger/espcomponents/tree/master/components/ble_elm327)
   의 preset/formula 모델과 **호환**
3. **GATT notify + 양방향** — 푸시형 기기 수신, switch/number로 **HA → BLE 제어**

## 저장소 구성

```
app/                        Android 앱 (Kotlin/Compose, 설정/디코딩/필터/선택)
docs/DESIGN.md              아키텍처
docs/PROTOCOL.md            앱 ↔ HA WebSocket 프로토콜 (앱 측 뷰)
docs/CONFIG_SCHEMA.md       git 설정 YAML 스펙
config.example.yaml         git에 올릴 설정 예시
```

> **HA 컴포넌트는 별도 리포로 분리**: `ws_bridge`(범용 WebSocket 브릿지)는
> [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge) 에서 관리한다.
> 프로토콜 정본도 그쪽 `PROTOCOL.md`에 있다.

## 설치 (요약)

- **HA 컴포넌트**: [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge) 의
  `ws_bridge`를 HA에 설치(HACS 또는 수동) → 통합 추가 "WebSocket Bridge"(설정 없음)
- **앱**: Android Studio에서 빌드 → **HA URL + 장기 액세스 토큰 + 설정 git URL** 입력
  → 센서 선택 → 시작
- HA 프로필에서 장기 액세스 토큰 발급 필요 (MQTT broker는 불필요)

## 상태

설계 + 스캐폴딩. 앱의 설정 로드/디코딩/preset/값필터와 HA 브릿지(동적 엔티티)는 구현됨.
앱의 실제 BLE I/O(Nordic 기반 scan/GATT/ELM327)와 센서 선택 UI는 `TODO`. 로드맵은
[docs/DESIGN.md](docs/DESIGN.md) §8.
