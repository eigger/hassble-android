# 설계 문서 (DESIGN)

## 0. 아키텍처 (확정): 스마트 앱(Companion 유사) + HA 브릿지

폰만 BLE를 가진다. **설정 로드·디코딩·필터링·센서 선택은 앱**이 하고, **HA 커스텀
컴포넌트는 브릿지**(엔티티 생성/갱신 + 제어 의도 중계)만 한다. HA Companion 앱이
센서를 register하고 state를 push하는 패턴과 같다.

```
┌─ Android 앱 (두뇌) ─────────────────────┐        ┌─ HA 컴포넌트 ws_bridge (범용 브릿지) ┐
│  Git repo에서 config.yaml + templates 로드 │        │                                      │
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

## 5. 설정 (Git, 앱이 로드)

- **센서 탭** 입력: `owner/repo` + 브랜치 (기본 `main`). 비공개 repo는 GitHub Token.
- 앱이 `config.yaml` fetch → YAML 파싱 → OBD preset 펼침 → 센서 목록. 실패 시 캐시 폴백.
- `templates.yaml`은 템플릿 가져오기용 (캐시 → 앱 내장 폴백).
- 사용자가 센서를 **토글로 선택** → 켠 것만 엔티티 선언/전송.
- 스키마: [CONFIG_SCHEMA.md](CONFIG_SCHEMA.md), 예시: [../config.example.yaml](../config.example.yaml), [../templates.example.yaml](../templates.example.yaml)

## 5b. 통신 완화 (값 기반 필터, 앱)

앱이 디코딩 값으로 직접 판정하므로 정확하다:
- `min_interval`(전송 빈도 상한), `on_change`(값 동일 시 스킵), `deadband`(미세 변화
  무시), `heartbeat`(미변경 시 주기 재전송). 센서/기기/defaults 순으로 상속.
- 광고는 100ms~1s로 broadcast되지만 값은 드물게 변하므로 큰 절감(수십 배).
- state는 배치로 묶어 전송(메시지 오버헤드 완화).

## 5c. 사용자 편의성

- **센서 선택 UI**: git 설정을 불러와 센서 목록을 보여주고 토글 → 켠 것만 HA 생성
- **게이트웨이 준비 배너**: HA URL·토큰·Git repo 미충족 시 시작 버튼 위 경고(빨강), 활성 센서 0개는 주황 권고
- **HA OAuth 로그인**: 게이트웨이 탭에서 OAuth 또는 장기 토큰. DataStore 영속, refresh token 자동 갱신
- **기기 템플릿**: Git `templates.yaml` + 앱 내장 폴백. 센서 탭 **템플릿 가져오기**
- **로컬 draft + Export YAML**: OBD/BLE/템플릿으로 추가한 기기 → 로컬 저장 → YAML 내보내기 → Git 수동 커밋
- **로그 탭**: LINK/TX/RX/NOTIF/ADV 이벤트, 필터·복사·저장·공유. MAC 탭으로 필터, 길게 눌러 센서 탭 검색
- **센서 탭 기기 검색**: 이름·ID·MAC·소스로 필터
- **HA 자동발견**: mDNS(`_home-assistant._tcp`)로 HA URL 자동 검색 (후속)
- **동적 기기 바인딩 및 일괄 적용 (Dynamic Device Binding & Bulk Map)**:
  - **광고 수신 기기 (Auto-Discovery)**: `match`에서 `name_prefix`나 `service_data_uuid` 같은 필터만 정의되어 있고 `mac`이 생략된 경우, 앱이 주변 스캔 중 해당 필터와 일치하는 기기들을 **자동 탐색**하여 각각의 고유 MAC 주소별로 개별 디바이스 인스턴스(예: `xiaomi_lywsd03_<MAC>`)를 HA에 동적으로 생성·등록합니다. 사용자는 아무런 수동 등록 과정 없이 기기를 일괄 사용할 수 있습니다.
  - **어댑터 중심 계층 바인딩 (Adapter-Centric Hierarchical Binding)**: `gatt_notify` 및 `obd`와 같이 능동 연결이 필요한 기기는 하나의 물리적 연결(어댑터) 하위에 여러 `sensors`와 `controls`가 묶여 있는 계층 구조를 가집니다. 사용자는 하위 센서들을 일일이 등록할 필요 없이, 상위 어댑터(기기 프로필)에 대해 단 **한 번만 물리 기기(MAC)를 매핑**해주면 하위의 모든 센서와 컨트롤이 일괄 활성화됩니다.
  - **서비스 UUID 기반 일괄 동적 매핑 (Bulk Service Binding)** (후속): 스캔 화면에서 동일 `service_uuid` 기기를 체크박스로 일괄 등록.

## 5d. 앱 초기 설정 및 기기/어댑터 등록 흐름 (UX Flow)

앱은 **게이트웨이 / 센서 / 로그** 세 탭으로 구성된다.

### Step 1: Home Assistant 접속 (게이트웨이 탭)
1. **HA URL** 입력.
2. **HA OAuth 로그인** 또는 **장기 액세스 토큰** 입력.
3. 준비 배너(빨강=시작 불가, 주황=권고)가 없을 때까지 HA·Git·활성 센서 확인.

### Step 2: Git 설정 로드 (센서 탭)
1. **GitHub repo** (`owner/repo`)와 **브랜치** 입력. (예: `eigger/hassble-config`, `main`)
2. 앱이 `config.yaml` fetch·파싱. 실패 시 캐시 사용.
3. **기기 목록** 표시. 각 기기에서 **센서 토글**.
4. (선택) 템플릿 가져오기 / OBD 추가 / BLE 추가 → **로컬 draft** 기기.

### Step 3: 게이트웨이 시작 (게이트웨이 탭)
1. **[게이트웨이 시작]** — Foreground Service, BLE 스캔·연결·폴링.
2. HA `ws_bridge`에 엔티티 선언 및 state push.

### Step 4: 기기별 바인딩 (센서 탭)

#### 1) 광고 수신 기기 (`advertisement`)
- **Auto-Discovery**: `match.mac` 생략 시 스캔 MAC마다 `{id}_{MAC}` 엔티티 자동 생성.
- 사용자는 센서 토글만 조정.

#### 2) 능동 연결형 (`gatt_notify`, `obd`)
1. **[Connect / Bind]** → BLE 스캔 (service UUID 필터).
2. 물리 기기(MAC) 선택 → DataStore 저장 → 연결 시작.
3. 하위 **모든 센서·제어** 엔티티 일괄 생성.

### Step 5: 로컬 draft → Git 반영 (선택)
1. **Export YAML** (전체 병합 또는 로컬만).
2. repo `config.yaml`에 붙여넣고 커밋.
3. **Reload Config** 또는 게이트웨이 재시작.

### Step 6: 디버깅 (로그 탭)
- LINK/TX/RX/NOTIF 이벤트, 타입·텍스트 필터, 복사·저장·공유.
- MAC 탭 → 로그 필터 / MAC 길게 누르기 → 센서 탭 MAC 검색.

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

### 완료
1. 프로토콜 + WS 핸드셰이크, 엔티티 선언/갱신
2. 광고 / GATT notify / OBD 경로 e2e
3. 센서 선택 UI, git config 로드, 캐시 폴백
4. OAuth 로그인, DataStore 영속
5. 템플릿 가져오기 (`templates.yaml`), OBD/BLE 추가, 로컬 draft, Export YAML
6. 로그 탭, 게이트웨이 준비 배너, 센서·로그 검색

### 후속
- mDNS HA 자동 발견
- GATT 일괄 바인딩 (service UUID 체크박스)
- OBD ISO-TP 멀티프레임 재조립
- HA command → BLE write (양방향 제어)

## 9. 참고

- ESPHome `ble_elm327` (OBD preset/formula 원본):
  https://github.com/eigger/espcomponents/tree/master/components/ble_elm327
- HA WebSocket API: https://developers.home-assistant.io/docs/api/websocket
- HA 커스텀 통합 개발: https://developers.home-assistant.io/docs/creating_component_index
