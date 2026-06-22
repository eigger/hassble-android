# HassBle

A system that turns a smartphone into a **BLE → Home Assistant gateway**.
It consists of two parts: a **Smart Android app (similar to the Companion app)** and a **Home Assistant bridge component**.

Instead of forwarding all raw packets (like ESPHome Bluetooth Proxy does), it parses and filters packets on the phone, sending **only changed sensor values** to Home Assistant, where they are exposed as **native entities**. Configuration (parsing rules) is **loaded by the app from Git**, and users **select which sensors** to apply on their phones.
It **does not use MQTT**, relying solely on the existing Home Assistant URL + Long-Lived Access Token (no additional ports required).

## Architecture

```
[Android App: The Brain]             WebSocket          [HA Component ws_bridge: Generic Bridge]
  Load config from Git + presets  ◀──(HA WS API)──▶     Create entities upon receiving declaration
  Scan Ads / GATT / OBD polling                         Update entities upon receiving state
  Raw -> Decode -> Value filter                         Relay commands for switch/number/select/button
  Only declare/send selected sensors                    (No BLE/format knowledge, generic)
```

- Since only the phone has BLE, the **BLE I/O + config/decoding/filtering/selection is handled by the app**, while **Home Assistant only handles entity creation and updates**.
- `ws_bridge` is not BLE-specific but **generic** — any authenticated WebSocket client can create entities if it matches the protocol. This follows the same pattern as the Home Assistant Companion App's sensor register/update.

## Features

1. **Advertisement Parsing** — Decodes raw passive scan advertisements into Home Assistant sensors.
2. **OBD (ELM327) Polling** — Polls BLE OBD adapters like vLinker. Compatible with the preset/formula model of ESPHome [`ble_elm327`](https://github.com/eigger/espcomponents/tree/master/components/ble_elm327).
3. **GATT notify + Bi-directional Control** — Receives data from push-based BLE devices and allows bi-directional control from Home Assistant to BLE devices via switch/number controls.

## Repository Structure

```
app/                        Android App (Kotlin/Compose, Config/Decoding/Filtering/Selection)
docs/DESIGN.md              Architecture Design
docs/PROTOCOL.md            App ↔ HA WebSocket Protocol (App perspective)
docs/CONFIG_SCHEMA.md       Git Configuration YAML Schema
```

> **Home Assistant component is in a separate repository**: `ws_bridge` (Generic WebSocket Bridge) is maintained at [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge). The protocol specification is also hosted there in `PROTOCOL.md`.

## Quick Start (Summary)

- **Home Assistant Component**: Install `ws_bridge` via HACS or manually from [hass-ws-bridge](https://github.com/eigger/hass-ws-bridge) → Add integration "WebSocket Bridge" (no configuration needed).
- **App**: Build the app in Android Studio (or download from GitHub Releases) → Enter **HA URL + Long-Lived Access Token + Config Git URL** (Default config repository: [eigger/hassble-config](https://github.com/eigger/hassble-config)) → Select sensors → Start.
- A Home Assistant Long-Lived Access Token is required (no MQTT broker is needed).

## Status

Fully functional core and UI. Loading configurations, decoding, presets, value filtering, and active BLE scan/GATT/OBD polling are implemented. Supports background service execution.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
