# 앱 ↔ HA 프로토콜 (WebSocket) — 앱 측 뷰

> **정본**은 컴포넌트 리포 [hass-ws-bridge/PROTOCOL.md](https://github.com/eigger/hass-ws-bridge/blob/main/PROTOCOL.md)
> 에 있다. 이 문서는 BLE 게이트웨이 앱 관점의 동일 계약이다 (드리프트 시 정본 우선).

앱(스마트, Companion 앱과 유사)과 HA 커스텀 컴포넌트(`ws_bridge`, **범용 브릿지/엔티티
팩토리**) 사이의 계약. **MQTT 아님.** HA WebSocket API(`/api/websocket`) 위의 커스텀
명령으로 구현. 추가 포트 불필요 — 기존 HA URL + 장기 액세스 토큰만 사용.

> `ws_bridge`는 BLE 전용이 아니다 — 인증된 어떤 WebSocket 클라이언트든 이 프로토콜을
> 말하면 엔티티가 생성된다. 아래 예시의 device/센서 의미(OBD 등)는 클라이언트(앱)가 정한다.

## 역할

- **앱**: git 설정 로드 → BLE 디코딩 → 사용자가 켠 센서만 필터링 → 엔티티 **선언** +
  **상태** push. (HA Companion 앱의 sensor register/update와 같은 패턴)
- **HA 컴포넌트**: 받은 선언으로 엔티티 **생성**, 상태로 **갱신**. 제어(switch/number)는
  의도를 앱에 **중계**만. 디코딩/설정 지식 없음.

## 연결/인증

1. 앱이 `wss://<HA>/api/websocket` 접속
2. 표준 auth 핸드셰이크: `{type:"auth", access_token:"..."}` → `auth_ok`
3. 앱이 구독: `{id, type:"ws_bridge/connect", gateway_id, name}`
   - `gateway_id`: 이 게이트웨이(폰)의 고유 ID (예: ANDROID_ID). HA에 **게이트웨이
     디바이스**로 등록되고, 이후 모든 entity/state/availability가 이 게이트웨이에 귀속.
   - `name`: 게이트웨이 표시 이름(예: 폰 모델).
   - 컴포넌트가 이 connection↔gateway_id를 바인딩 → **command를 이 게이트웨이에만 push**
4. 앱이 엔티티들을 선언하고 상태를 흘려보냄

> 재연결 시 앱이 엔티티 선언을 **재전송**(idempotent) → HA가 기존 엔티티 복원/갱신.

### 디바이스 계층 (그룹화)
HA는 unique_id/디바이스를 `gateway_id`로 네임스페이스해 게이트웨이가 여럿이어도
충돌하지 않는다. 계층은:

```
게이트웨이 디바이스 (gateway_id, 예: "Galaxy S21")
   └─ via_device ─ BLE 디바이스 (예: "콜로라도")
                      └─ 엔티티 (RPM, 냉각수온, …)
```

`entity`의 `device` 필드가 BLE 디바이스, `connect`의 `gateway_id`가 그 부모.
앱은 원래 unique_id(`<device_id>_<key>`)만 쓰고, 네임스페이스는 컴포넌트가 처리한다.
`command`도 컴포넌트가 원래 unique_id로 되돌려 보낸다.

## 메시지 (⬇️ HA→앱, ⬆️ 앱→HA)

### ⬆️ `entity` — 엔티티 선언 (생성/메타 갱신, idempotent)
```jsonc
{ "id": <n>, "type": "ws_bridge/entity",
  "unique_id": "colorado_rpm",
  "platform": "sensor",            // sensor|binary_sensor|switch|number|select|button
  "name": "RPM",
  "device": { "id": "colorado", "name": "콜로라도" },
  "device_class": "speed",          // 선택 (sensor/binary_sensor)
  "unit_of_measurement": "rpm",     // 선택 (sensor/number)
  "state_class": "measurement",     // 선택 (sensor)
  "icon": "mdi:gauge",              // 선택
  "entity_category": "diagnostic",  // 선택 (config|diagnostic)
  "options": ["low","high"],        // select 전용
  "min": 0, "max": 100, "step": 1   // number 전용
}
```

**플랫폼별 state/명령**

| platform | 방향 | state 값 | 명령 action |
|----------|------|---------|------------|
| `sensor` | 읽기 | 숫자/문자 | — |
| `binary_sensor` | 읽기 | 불리언(또는 0/1) | — |
| `switch` | 제어 | 불리언 | turn_on / turn_off |
| `number` | 제어 | 숫자 | set_value(value) |
| `select` | 제어 | 현재 옵션(문자) | select_option(value=옵션) |
| `button` | 제어 | — | press |

### ⬆️ `state` — 상태 갱신 (앱이 필터링 후 통과분만; 배치 가능)
```jsonc
{ "id": <n>, "type": "ws_bridge/state",
  "states": [ { "unique_id": "colorado_rpm", "value": 1726 },
              { "unique_id": "colorado_coolant_temp", "value": 50.0 } ],
  "ts": 1719000000 }
```
값 기반 필터(on_change/min_interval/deadband)는 **앱이 디코딩 값으로** 적용하므로
HA는 받은 것을 그대로 반영만 한다.

### ⬆️ `availability` — 기기/게이트웨이 연결 상태
```jsonc
{ "id": <n>, "type": "ws_bridge/availability",
  "device_id": "colorado", "online": false }
```
`device_id`에 속한 엔티티들을 unavailable 처리.

### ⬇️ `command` — 제어 의도 (HA→앱)
```jsonc
{ "type": "event", "event": {
  "kind": "command",
  "unique_id": "custom_meter_relay",
  "action": "turn_on",             // turn_on|turn_off|set_value|select_option|press
  "value": 42                       // set_value(숫자) / select_option(옵션 문자)
} }
```
앱이 config의 control 매핑으로 변환해 BLE write에 전송 (switch on→`A10100`, select
옵션→hex, number template `A1{value:02X}`, button press→hex). hex 매핑은 **앱**이,
HA는 의도만 전달. unique_id는 컴포넌트가 원래 값(클라이언트 네임스페이스 제거)으로 보냄.

### ⬆️ `remove` — 엔티티/디바이스 삭제
```jsonc
{ "id": <n>, "type": "ws_bridge/remove", "device_id": "jaalee_jht" }
```
`unique_id`만 지정해 단일 엔티티를 지울 수도 있다. `device_id`는 해당 클라이언트
디바이스와 그 하위 엔티티를 제거한다.

**삭제 범위 (`mode`, 선택)**

| mode | 동작 |
|------|------|
| `exact` (기본) | `device_id` / `unique_id`와 **완전 일치**하는 대상만 |
| `prefix` | 대상 id와 일치하거나 `대상id_`로 시작하는 **하위 id 전부** |

MAC별 `device.id`를 쓰는 advertisement 프로필(`instance_mode: mac`, 고정 MAC 없음)을
앱에서 삭제할 때는 `mode: "prefix"`를 보낸다. 예: 프로필 `jaalee_jht` 삭제 시
`jaalee_jht_AABBCCDDEEFF` 인스턴스 디바이스·엔티티까지 함께 제거.

```jsonc
{ "id": <n>, "type": "ws_bridge/remove",
  "device_id": "jaalee_jht",
  "mode": "prefix" }
```

> 상세 스키마·응답은 [hass-ws-bridge PROTOCOL.md §3.4](https://github.com/eigger/hass-ws-bridge/blob/main/docs/PROTOCOL.md) 참고.

## 비고

- 모든 바이트열 표기는 hex 문자열(공백 없음).
- 엔티티 unique_id 규약: `<device_id>_<sensor_or_control_key>`.
- 사용자가 폰에서 끈 센서는 `entity` 선언 자체를 안 함 → HA에 안 생김.
