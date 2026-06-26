# 설정 스키마 v1

**앱**이 Git 저장소에서 불러오는 설정 파일(`config.yaml`)의 스펙. 앱이 이 파일을 읽고
디코딩/필터링하며, 사용자가 켠 센서만 HA에 엔티티로 선언한다(아키텍처:
[DESIGN.md](DESIGN.md)). 하나의 파일에서 **광고/GATT notify/OBD** 세 경로를 모두
표현하며, OBD 부분은 ESPHome `ble_elm327`의 preset/formula 모델과 호환된다.

전체 예시는 [../config.example.yaml](../config.example.yaml) 참고.

## 설정 저장소 (Git)

| 항목 | 값 |
|------|-----|
| 앱 입력 (센서 탭) | `owner/repo` + 브랜치 (기본 `main`) |
| 운영 설정 파일 | repo 루트 **`config.yaml`** (고정, 앱에서 경로 변경 불가) |
| 템플릿 파일 | repo 루트 **`templates.yaml`** (고정) |
| raw URL 예 | `https://raw.githubusercontent.com/owner/repo/main/config.yaml` |
| 비공개 repo | config와 동일한 GitHub Token (센서 탭) |
| HA URL/토큰 | **게이트웨이 탭** — git에 올리지 않음 |

fetch 실패 시 **캐시된 config** 사용. 템플릿은 캐시 → 앱 내장 `templates.yaml` 순으로 폴백.

## 최상위
```yaml
version: 1
defaults:                           # 기기/센서가 생략 시 상속
  publish:
    on_change_only: true
    min_interval: 10s
devices: [ ... ]                    # 아래 참조
```

> HA 접속(URL/토큰)은 **게이트웨이 탭**, Git 저장소는 **센서 탭**에서 입력한다.
> 자격증명을 git에 올리지 않기 위함. HA 컴포넌트는 설치만 하면 되고 별도 설정이 없다.

## device 공통

| 필드 | 필수 | 설명 |
|------|------|------|
| `id` | ✅ | 고유 식별자 (topic/엔티티 키에 사용) |
| `name` | ✅ | HA 표시 이름 |
| `source` | ✅ | `advertisement` \| `gatt_notify` \| `obd` |
| `instance_mode` | | advertisement 전용. `mac`(기본)=MAC별 엔티티, `shared`=프로필 ID 하나로 덮어쓰기 |
| `sensors` | △ | 센서 목록 (읽기) |
| `controls` | | 제어 목록 (HA→BLE, gatt_notify/obd) |

예시: [config.example.yaml](../config.example.yaml)
## source: advertisement

```yaml
- id: xiaomi_lywsd03
  name: "거실 온습도계"
  source: advertisement
  instance_mode: mac           # mac(기본) | shared
  match:                       # 지정한 항목은 **모두** 일치해야 함 (AND)
    mac: "A4:C1:38:..."
    service_data_uuid: "181a"  # service_data 또는 광고 service_uuids 목록
    manufacturer_id: 0x004C
    manufacturer_hex_prefix: "0215"      # 선택: manufacturer payload hex 접두사
    manufacturer_min_length: 24          # 선택: manufacturer payload 최소 바이트
    name_prefix: "LYWSD"
  sensors:
    - key: temperature
      device_class: temperature
      unit: "°C"
      state_class: measurement
      source_field: service_data   # service_data | manufacturer_data | raw
      decode: { offset: 6, length: 2, type: int16, endian: big, scale: 0.1 }
```

`instance_mode` 동작 (advertisement만 해당):

| 값 | 동작 |
|----|------|
| `mac` (기본) | `match.mac` 없으면 스캔된 MAC마다 `{id}_{MAC}` 엔티티 생성. `match.mac` 있으면 단일 기기로 `{id}` 사용 |
| `shared` | 항상 `{id}` 하나. 여러 기기가 같은 프로필에 매칭되면 **마지막 광고 값**으로 덮어씀. 게이트웨이 시작 시 엔티티 선언 |

## source: gatt_notify

```yaml
- id: custom_meter
  name: "커스텀 미터"
  source: gatt_notify
  gatt:
    mac: "11:22:33:44:55:66"     # 선택 사항 (생략 시 앱 UI에서 스캔 후 동적 바인딩)
    service_uuid: "ffe0"
    notify_char_uuid: "ffe1"
    write_char_uuid: "ffe2"     # controls 사용 시 필요
  sensors:
    - key: power
      unit: "W"
      decode: { offset: 0, length: 2, type: uint16, endian: little }
  controls:
    - key: relay
      type: switch              # switch | number | select | button
      command: { on: "A10100", off: "A10000" }   # write_char로 전송할 hex
```

## source: obd (ELM327)

ESPHome `ble_elm327`과 동일한 개념. `preset`만 적으면 mode/pid/formula/단위가
내장 DB에서 채워진다.

```yaml
- id: car
  name: "콜로라도"
  source: obd
  obd:
    mac: "AA:BB:CC:DD:EE:FF"     # 선택 사항 (생략 시 앱 UI에서 스캔 후 동적 바인딩, 예: vLinker MC+)
    service_uuid: "18F0"         # 기본 FFF0 / vLinker 18F0
    tx_char_uuid: "2AF1"         # write (phone → adapter)
    rx_char_uuid: "2AF0"         # notify (adapter → phone)
    tx_delay: 50ms
    init_commands: [ "ATSP6" ]   # base(ATZ/ATE0/ATL0/ATS0/ATH0/ATSP0)는 앱에서 자동 실행하므로 제외 가능 (중복 입력 시 자동 제거됨)
  sensors:
    - { key: rpm,          preset: rpm,             update_interval: 1s }
    - { key: coolant_temp, preset: coolant_temp,    update_interval: 10s }
    - { key: gear,         preset: gm_current_gear, update_interval: 1s }
    - { key: fuel_level,   preset: fuel_level,      update_interval: 30s }
    # 비표준/커스텀 (preset 없이 직접 지정)
    - key: custom_pressure
      mode: "22"
      pid: "1234"
      update_interval: 5s
      formula: "(a*256+b)*0.1"   # a,b,c,d... = 응답 데이터 바이트
      unit: "kPa"
```

### sensor 필드 (obd)

| 필드 | 설명 |
|------|------|
| `key` | 센서 키 |
| `platform` | `sensor`(기본) \| `binary_sensor` \| `text_sensor` (읽기 전용 문자열, HA에는 `sensor`로 선언) |
| `preset` | 내장 preset 이름 (mode/pid/formula/unit 자동). `pid`와 배타 |
| `mode` | `"01"`(표준) / `"22"`(UDS 확장). 기본 `01` |
| `pid` | PID hex (mode 01: 2자리, mode 22: 4자리) |
| `formula` | `a,b,c,d...` 바이트 식. preset 사용 시 생략 |
| `update_interval` | 폴링 주기 (기본 60s) |
| `pre_commands` | 이 PID 전에 보낼 AT 명령 (헤더 전환용) |
| `unit` `device_class` `state_class` `icon` `accuracy_decimals` | HA 메타 |
| `publish` | 값 필터 (on_change_only, min_interval, deadband, heartbeat) — `defaults.publish` 상속 |

> **참고:** ESPHome 스타일 `filters`(median/delta/throttle)는 v1 스키마에서 지원하지 않습니다.
> 대신 센서/기기/defaults의 `publish` 규칙을 사용하세요.

## decode 블록 (구조적 파싱)

| 필드 | 설명 |
|------|------|
| `offset` | 시작 바이트 |
| `length` | 길이 |
| `type` | int8/uint8/int16/uint16/int32/uint32/float32/**timestamp**/**string** |
| `endian` | big / little (기본 big) |
| `bitmask` | 비트 추출 (선택) |
| `scale` | 곱할 계수 (기본 1) |
| `offset_value` | 더할 값 (기본 0) |
| `map` | enum 매핑 `{0: P, 1: R}` |

값 = `raw * scale + offset_value` (timestamp/string 제외).

센서 필드 `min_length` / `length`:
- `min_length`: manufacturer/service 데이터 최소 바이트 길이. 미만이면 스킵.
- `length`: manufacturer/service 데이터 정확한 바이트 길이. 일치하지 않으면 스킵.

`timestamp` 타입: `offset`부터 4바이트를 **월·일·시·분**(uint8)으로 읽어 ISO 8601 문자열 반환 (예: `2026-06-22T14:30:00`). 연도는 디코딩 시점의 현재 연도. `device_class: timestamp`와 함께 사용.

`string` 타입: `offset`부터 `length`바이트를 ASCII 문자로 연결. `platform: text_sensor`와 함께 사용 (예: 주차 위치 코드 2자).

## publish 억제 (통신 완화, 값 기반 — 앱)

`defaults.publish` / device별 / 센서별 `publish`로 지정(센서 > 기기 > defaults 상속).
앱이 **디코딩 값으로 직접** 판정하므로 정확하다.

```yaml
publish:
  on_change_only: true   # 값 동일하면 미전송
  min_interval: 10s       # 최소 전송 간격(전송 빈도 상한)
  heartbeat: 5m           # 안 바뀌어도 이 간격마다 1회 재전송(재동기)
  deadband: 0.2           # 이 이하 변화 무시 (수치형)
```

| 규칙 | 비고 |
|------|------|
| `min_interval` | 전송 빈도 상한 |
| `on_change_only` | 디코딩 값이 직전과 같으면 스킵 |
| `heartbeat` | 미변경 시에도 주기적 재전송(HA 재시작 후 재동기) |
| `deadband` | 수치형에서 이 이하 변화 무시 |

> 광고처럼 고빈도 broadcast에서 "값이 의미있게 바뀐 센서만" 전송해 전송량을 크게 줄인다.

## 템플릿 (`templates.yaml`)

센서 탭 **템플릿 가져오기** 목록은 설정 Git 저장소 **루트**의 `templates.yaml`에서 불러온다.
운영 설정은 같은 위치의 `config.yaml` (파일명 고정, 앱에서 선택 불가).

- 예: `https://raw.githubusercontent.com/owner/repo/main/config.yaml`
- 템플릿: `…/main/templates.yaml`
- 앱 입력: `owner/repo` + 브랜치만 (기본 `main`)
- 비공개 저장소는 config와 동일한 GitHub Token 사용
- 네트워크 실패 시 캐시 → 앱 내장 `templates.yaml` 순으로 폴백

스키마:

```yaml
templates:
  - id: unique_id
    name: "표시 이름"
    description: "선택 설명"
    device: { ... }   # devices[] 항목과 동일
```

예시: 리포 루트 [`templates.example.yaml`](../templates.example.yaml)

## 앱 로컬 draft & YAML 내보내기

앱에서 **템플릿 가져오기**, **OBD 추가**, **BLE 추가**(마법사)로 만든 기기는 Git에
자동 커밋되지 않고 **앱 로컬 draft**로 저장된다. 센서 탭에서 **Local** 배지로 표시.

| 동작 | 설명 |
|------|------|
| draft 추가 | Git `config.yaml`과 **병합**되어 게이트웨이에 즉시 반영 (실행 중 reload) |
| Export YAML | 미리보기 + 클립보드(우상단 아이콘) + Downloads 저장 + 공유 |
| 내보내기 모드 | **전체(병합)** — Git config + draft / **로컬만** — draft만 |
| Git 반영 | Export한 YAML을 repo의 `config.yaml`에 붙여넣고 **수동 커밋** |

draft 기기는 앱에서 **Remove from app**으로 삭제 가능. Git에 없는 id만 draft로 취급.

## 내장 OBD preset
`assets/obd_presets.yaml`에 동봉. ESPHome `presets.py`에서 이식.
표준(rpm, speed, coolant_temp, engine_load, throttle, fuel_level,
intake_air_temp, ambient_temp, battery_voltage, run_time, odometer …) +
GM 확장(gm_current_gear, gm_prnd_status, actual_torque, gm_oil_pressure,
gm_trans_temp, gm_fuel_level_liters …). 추가는 파일에 항목만 더하면 됨.
