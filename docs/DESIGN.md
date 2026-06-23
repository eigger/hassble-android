# 설계 문서 (DESIGN)

## 0. 아키텍처 (확정): 스마트 앱(Companion 유사) + HA 브릿지

폰만 BLE를 가진다. **설정 로드·디코딩·필터링·센서 선택은 앱**이 하고, **HA 커스텀
컴포넌트는 브릿지**(엔티티 생성/갱신 + 제어 의도 중계)만 한다. HA Companion 앱이
센서를 register하고 state를 push하는 패턴과 같다.

```
┌─ Android 앱 (두뇌) ─────────────────────┐        ┌─ HA 컴포넌트 ws_bridge (범용 브릿지) ┐
│  git URL에서 설정(YAML) 로드 + preset    │        │                                      │
│  BLE: 광고scan / GATT notify / OBD 폴링  │        │  entity 선언 받으면 → 엔티티 생성    │
│  raw → 디코딩(offset/formula/map)        │  WS    │  state 받으면 → 엔티티 갱신          │
│  사용자가 켠 센서만 (선택 UI)            │ ◀────▶ │  switch/number → command 의도를 앱에 │
│  값 필터(on_change/min_interval/deadband)│        │  availability → 엔티티 가용성        │
│  entity 선언 + state push                │        │  (디코딩/설정 지식 없음)             │
│  HA command → config의 hex 매핑 → write  │        │                                      │
└─────────────────────────────────────────┘        └──────────────────────────────────────┘
```

전송은 **MQTT 아님**. HA WebSocket API 위 커스텀 명령(entity/state/command, 상세:
[PROTOCOL.md](PROTOCOL.md)). 추가 포트 불필요 — 기존 HA URL + 장기 액세스 토큰만.

> **컴포넌트는 별도 리포**: `ws_bridge`(범용 WebSocket 브릿지)는
> [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge) 에서 독립 관리한다.
> 이 리포(hassble-android)는 BLE 게이트웨이 **앱**만 담는다.

## 1. 왜 이 구조인가 (사용자 우선순위: 편의성 + 통신완화)

- **편의성**: 어려운 파싱 규칙은 git YAML, 폰이 불러와 **센서를 골라 적용**. HA 쪽은
  설치만 하면 끝(설정 없음). 디코더 엔진은 앱에 고정이라 config는 재빌드 없이 git 갱신.
- **통신완화**: 폰이 디코딩까지 하므로 **값 기반 필터**(실제 on_change/deadband)로
  "정말 바뀐 센서만" 전송. (raw dedup보다 정확)
- **정식 엔티티 + 양방향**: HA 컴포넌트가 만들어 unique_id/device/통계/switch·number 지원.

## 2. 역할 분담

| | git 스키마 | **Android 앱** | **HA 컴포넌트(브릿지)** |
|---|---|---|---|
| 파싱 규칙/preset 정의 | 보관 | 불러와 적용 | — |
| 디코딩 | — | ✅ offset/formula/preset | — |
| 센서 선택 | — | ✅ 사용자 토글 | — |
| 필터링(값 기반) | — | ✅ | — |
| 엔티티 생성/갱신 | — | 선언/상태 push | ✅ 생성·갱신만 |
| 양방향(HA→BLE) | — | ✅ hex 매핑+write | 의도 중계 |

## 3. 획득 경로 (앱 측 BLE I/O)

### A. 광고 passive scan
match(service_data_uuid/manufacturer_id/mac/name_prefix)를 ScanFilter로 변환,
매칭 광고의 serviceData/manufacturerData/raw 바이트를 디코더에 전달.

### B. GATT notify
connect → notify char 구독 → raw 디코딩. write char 있으면 command 수신 시 write.

### C. OBD (ELM327) — 요청/응답 폴링
켜진 OBD 센서들의 (mode+pid)·update_interval로 폴링 플랜을 앱이 구성:
- base init(ATZ/ATE0/ATL0/ATS0/ATH0/ATSP0) + init_commands 후 tx_delay 간격 드레인
- 응답 hex를 mode+pid로 매칭 → 해당 센서 formula로 디코딩 → 값 필터 → state push
- 멀티프레임(ISO-TP) 재조립은 앱이 처리 (TODO)

## 4. 디코딩 (앱 측, Kotlin)

- 구조적: offset/length/type(int/uint8~32,float32)/endian/bitmask/scale/offset/map
- formula: 응답 바이트 `a,b,c,d…` 식 (ESPHome ble_elm327 표기 호환, exp4j)
- preset: `app/assets/obd_presets.yaml`(앱 동봉)에서 mode/pid/formula/단위 자동

## 5. 설정 (git, 앱이 로드)

- 입력: raw 파일 URL (앱 UI). private repo는 토큰 옵션.
- 앱이 fetch → YAML 파싱 → preset 펼침 → 센서 목록. 실패 시 캐시 폴백.
- 사용자가 센서를 **토글로 선택** → 켠 것만 엔티티 선언/전송.
- 스키마: [CONFIG_SCHEMA.md](CONFIG_SCHEMA.md), 예시: [../config.example.yaml](../config.example.yaml)

## 5b. 통신 완화 (값 기반 필터, 앱)

앱이 디코딩 값으로 직접 판정하므로 정확하다:
- `min_interval`(전송 빈도 상한), `on_change`(값 동일 시 스킵), `deadband`(미세 변화
  무시), `heartbeat`(미변경 시 주기 재전송). 센서/기기/defaults 순으로 상속.
- 광고는 100ms~1s로 broadcast되지만 값은 드물게 변하므로 큰 절감(수십 배).
- state는 배치로 묶어 전송(메시지 오버헤드 완화).

## 5c. 사용자 편의성

- **센서 선택 UI**: git 설정을 불러와 센서 목록을 보여주고 토글 → 켠 것만 HA 생성
- **HA 자동발견**: mDNS(`_home-assistant._tcp`)로 HA URL 자동 검색(후속)
- **토큰/입력 영속**: DataStore 저장. (후속: HA OAuth 로그인)
- **기기 템플릿**: OBD preset처럼 광고 기기도 템플릿화 → 설정 손작성 최소화(후속)
- **동적 기기 바인딩 및 일괄 적용 (Dynamic Device Binding & Bulk Map)**:
  - **광고 수신 기기 (Auto-Discovery)**: `match`에서 `name_prefix`나 `service_data_uuid` 같은 필터만 정의되어 있고 `mac`이 생략된 경우, 앱이 주변 스캔 중 해당 필터와 일치하는 기기들을 **자동 탐색**하여 각각의 고유 MAC 주소별로 개별 디바이스 인스턴스(예: `xiaomi_lywsd03_<MAC>`)를 HA에 동적으로 생성·등록합니다. 사용자는 아무런 수동 등록 과정 없이 기기를 일괄 사용할 수 있습니다.
  - **어댑터 중심 계층 바인딩 (Adapter-Centric Hierarchical Binding)**: `gatt_notify` 및 `obd`와 같이 능동 연결이 필요한 기기는 하나의 물리적 연결(어댑터) 하위에 여러 `sensors`와 `controls`가 묶여 있는 계층 구조를 가집니다. 사용자는 하위 센서들을 일일이 등록할 필요 없이, 상위 어댑터(기기 프로필)에 대해 단 **한 번만 물리 기기(MAC)를 매핑**해주면 하위의 모든 센서와 컨트롤이 일괄 활성화됩니다.
  - **서비스 UUID 기반 일괄 동적 매핑 (Bulk Service Binding)**: 여러 대의 기기를 한 번에 연결할 때의 불편함을 해결하기 위해, 앱 스캔 화면에서 특정 `service_uuid` 규격을 갖는 여러 기기들을 목록에서 **체크박스로 일괄 선택하여 동시에 등록**할 수 있는 편의 기능을 제공합니다.

## 5d. 앱 초기 설정 및 기기/어댑터 등록 흐름 (UX Flow)

사용자가 HassBle 앱을 설치하고 처음 실행하여 기기를 설정하기까지의 라이프사이클은 다음과 같이 진행됩니다.

### Step 1: Home Assistant 접속 설정 및 기본 센서 생성
1. 사용자가 앱 실행 후 **HA URL**과 **장기 액세스 토큰**을 입력하고 [시작] 버튼을 누릅니다.
2. 앱이 HA WebSocket Bridge 컴포넌트(`ws_bridge`)에 연결을 시도합니다.
3. **연결 성공 즉시**, 앱은 설정 파일(YAML) 로드 여부와 관계없이 **스마트폰 자체의 상태를 나타내는 기본 센서**들을 HA에 선언 및 전송합니다.
   - `sensor.hassble_<gateway_id>_connection`: 연결 상태 (`online` / `offline`)
   - `sensor.hassble_<gateway_id>_battery`: 스마트폰 배터리 잔량 (`%`)
   - `sensor.hassble_<gateway_id>_service_status`: 백그라운드 BLE 서비스 작동 상태 (`running` / `stopped`)
   - 이 과정을 통해 HA 내에 게이트웨이 기기(스마트폰 자체)가 정상적으로 표시됩니다.

### Step 2: GitHub 설정 URL 로드 및 설정 리스트 노출
1. 사용자가 **설정 git URL (raw)**을 입력합니다.
2. 앱이 GitHub에서 YAML 파일을 다운로드하여 파싱합니다.
3. 파싱이 완료되면 앱 화면에 YAML에 정의된 **설정 프로필(어댑터/기기) 목록**이 리스트 형태로 나타납니다.
   - 예: `거실 온습도계 (advertisement)`, `콜로라도 OBD (obd)`, `스마트 플러그 (gatt_notify)` 등

### Step 3: 어댑터/기기별 등록 및 바인딩 진행
목록에 나타난 각 어댑터는 소스 타입에 따라 다르게 등록을 완료합니다.

#### 1) 광고 수신 기기 (`advertisement` 소스)
- **등록 방식**: **자동 적용 (Auto-Discovery)**
- **흐름**: 사용자가 수동으로 페어링할 필요가 없습니다. 앱이 백그라운드에서 광고 패킷을 스캔하면서 매칭 조건(`name_prefix` 등)에 부합하는 장치들을 찾으면, 해당 장치들의 고유 MAC 주소별로 HA에 개별 디바이스를 동적으로 자동 생성합니다. 사용자는 각 기기를 토글 스위치로 활성화/비활성화만 선택하면 됩니다.

#### 2) 능동 연결형 기기 (`gatt_notify` 및 `obd` 소스)
- **등록 방식**: **어댑터 바인딩 (1회 등록)**
- **흐름**: 
  1. 기기 프로필 옆의 **[어댑터 연결 / 바인딩]** 버튼을 누릅니다.
  2. BLE 기기 검색(스캔) 팝업이 열립니다. (프로필에 정의된 `service_uuid` 규격을 필터링하여 대상 기기만 선별해 노출)
  3. 사용자가 연결할 물리 장치(예: 내 차의 OBD 어댑터 `vLinker MC+` 또는 스마트 플러그)를 선택합니다.
  4. 매핑 정보(`Profile ID ➔ MAC`)가 앱 로컬 DataStore에 저장되고 즉시 백그라운드 연결을 시작합니다.
  5. 연결이 시작되면, 해당 어댑터 하위에 명시된 **모든 센서 및 제어 엔티티들이 HA에 일괄 생성**됩니다. (센서별로 일일이 매핑할 필요 없음)



## 6. Android 제약 (앱 측 필수)

| 항목 | 대응 |
|------|------|
| 백그라운드 BLE | Foreground Service + 고정 알림 (`connectedDevice`) |
| 권한 31+ | BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION (비콘 스캔 시 필수) |
| 권한 ≤30 | ACCESS_FINE_LOCATION |
| 스캔 스로틀 | 30초 5회 제한 → 지속 스캔 유지 |
| Doze/배터리 | 배터리 최적화 예외 안내 |
| WS 재연결 | HA 끊기면 백오프 재연결, 재연결 시 앱이 엔티티 선언 재전송 |

## 7. 기술 스택

- **앱**: Kotlin + Compose, Nordic Kotlin-BLE-Library, OkHttp(WebSocket + git fetch),
  kaml(YAML), exp4j(formula), kotlinx-serialization-json(프로토콜), DataStore
- **컴포넌트**: Python (HA integration), HA websocket_api / config_entries /
  entity platforms. (디코딩/설정 없음 — 의존성 거의 없음)

## 8. 로드맵

1. **프로토콜 + 핸드셰이크** — 앱 WS 연결/인증/구독, 엔티티 선언 → HA 생성
2. **광고 경로 e2e** — git 설정 로드 → 광고 스캔 → 디코딩 → 필터 → sensor 1개
3. **센서 선택 UI** — 설정 로드 후 센서 토글, 켠 것만 선언/전송
4. **OBD 경로** — ELM327 init + 폴링 + 응답 디코딩(preset/formula)
5. **양방향** — switch/number 명령 → config hex 매핑 → BLE write
6. **마감** — availability, WS 재연결, 입력 영속, 권한 온보딩, mDNS 발견

## 9. 참고

- ESPHome `ble_elm327` (OBD preset/formula 원본):
  https://github.com/eigger/espcomponents/tree/master/components/ble_elm327
- AndrOBD (GPL, PID 레퍼런스): https://github.com/fr3ts0n/AndrOBD
- HA WebSocket API: https://developers.home-assistant.io/docs/api/websocket
- HA 커스텀 통합 개발: https://developers.home-assistant.io/docs/creating_component_index
