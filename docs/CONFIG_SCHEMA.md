# 설정 스키마 v1

**앱**이 git URL에서 불러오는 설정 파일(YAML)의 스펙. 앱이 이 파일을 읽고
디코딩/필터링하며, 사용자가 켠 센서만 HA에 엔티티로 선언한다(아키텍처:
[DESIGN.md](DESIGN.md)). 하나의 파일에서 **광고/GATT notify/OBD** 세 경로를 모두
표현하며, OBD 부분은 ESPHome `ble_elm327`의 preset/formula 모델과 호환된다.

전체 예시는 [../config.example.yaml](../config.example.yaml) 참고.

## 최상위

```yaml
version: 1
defaults:                           # 기기/센서가 생략 시 상속
  publish:
    on_change_only: true
    min_interval: 10s
devices: [ ... ]                    # 아래 참조
```

> HA 접속(URL/토큰)과 이 설정의 git URL은 모두 **앱 UI**에서 입력한다. 자격증명을
> git에 올리지 않기 위함. HA 컴포넌트는 설치만 하면 되고 별도 설정이 없다.

## device 공통

| 필드 | 필수 | 설명 |
|------|------|------|
| `id` | ✅ | 고유 식별자 (topic/엔티티 키에 사용) |
| `name` | ✅ | HA 표시 이름 |
| `source` | ✅ | `advertisement` \| `gatt_notify` \| `obd` |
| `sensors` | △ | 센서 목록 (읽기) |
| `controls` | | 제어 목록 (HA→BLE, gatt_notify/obd) |

## source: advertisement

```yaml
- id: xiaomi_lywsd03
  name: "거실 온습도계"
  source: advertisement
  match:                       # 아래 중 하나 이상
    mac: "A4:C1:38:..."
    service_data_uuid: "181a"
    manufacturer_id: 0x004C
    name_prefix: "LYWSD"
  sensors:
    - key: temperature
      device_class: temperature
      unit: "°C"
      state_class: measurement
      source_field: service_data   # service_data | manufacturer_data | raw
      decode: { offset: 6, length: 2, type: int16, endian: big, scale: 0.1 }
```

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
    init_commands: [ "ATSP6" ]   # base(ATZ/ATE0/ATL0/ATS0/ATH0/ATSP0) 뒤에 추가
  sensors:
    - { key: rpm,          preset: rpm,             update_interval: 1s }
    - { key: coolant_temp, preset: coolant_temp,    update_interval: 10s }
    - { key: gear,         preset: gm_current_gear, update_interval: 1s }
    - { key: fuel_level,   preset: fuel_level,      update_interval: 30s,
        filters: [ { median: { window: 5 } }, { delta: 0.5 }, { throttle: 60s } ] }
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
| `preset` | 내장 preset 이름 (mode/pid/formula/unit 자동). `pid`와 배타 |
| `mode` | `"01"`(표준) / `"22"`(UDS 확장). 기본 `01` |
| `pid` | PID hex (mode 01: 2자리, mode 22: 4자리) |
| `formula` | `a,b,c,d...` 바이트 식. preset 사용 시 생략 |
| `update_interval` | 폴링 주기 (기본 60s) |
| `pre_commands` | 이 PID 전에 보낼 AT 명령 (헤더 전환용) |
| `unit` `device_class` `state_class` `icon` `accuracy_decimals` | HA 메타 |
| `filters` | median / delta / throttle (ESPHome 호환) |

## decode 블록 (구조적 파싱)

| 필드 | 설명 |
|------|------|
| `offset` | 시작 바이트 |
| `length` | 길이 |
| `type` | int8/uint8/int16/uint16/int32/uint32/float32 |
| `endian` | big / little (기본 big) |
| `bitmask` | 비트 추출 (선택) |
| `scale` | 곱할 계수 (기본 1) |
| `offset_value` | 더할 값 (기본 0) |
| `map` | enum 매핑 `{0: P, 1: R}` |

값 = `raw * scale + offset_value`.

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

## 내장 OBD preset

`assets/obd_presets.yaml`에 동봉. ESPHome `presets.py`에서 이식.
표준(rpm, speed, coolant_temp, engine_load, throttle, fuel_level,
intake_air_temp, ambient_temp, battery_voltage, run_time, odometer …) +
GM 확장(gm_current_gear, gm_prnd_status, actual_torque, gm_oil_pressure,
gm_trans_temp, gm_fuel_level_liters …). 추가는 파일에 항목만 더하면 됨.
