package dev.eigger.hassble.config

import dev.eigger.hassble.ble.AdvertisePayload

enum class ValidationLevel { WARNING, ERROR }

data class ValidationIssue(
    val level: ValidationLevel,
    val deviceId: String,
    val sensorKey: String? = null,
    val message: String,
    val yamlPath: String = "",
) {
    val tag: String get() = if (sensorKey != null) "device='$deviceId' sensor='$sensorKey'" else "device='$deviceId'"
    override fun toString() = "[${level.name}] $tag: $message"
}

object ConfigValidator {

    private fun sensorPath(deviceId: String, sensorKey: String, field: String? = null): String {
        val base = "devices[$deviceId].sensors[$sensorKey]"
        return if (field != null) "$base.$field" else base
    }

    private fun controlPath(deviceId: String, controlKey: String, field: String? = null): String {
        val base = "devices[$deviceId].controls[$controlKey]"
        return if (field != null) "$base.$field" else base
    }

    private fun devicePath(deviceId: String, field: String? = null): String {
        val base = "devices[$deviceId]"
        return if (field != null) "$base.$field" else base
    }

    private val validStatClasses = setOf("measurement", "measurement_angle", "total", "total_increasing")
    private val numericDeviceClasses = setOf(
        "battery", "carbon_dioxide", "carbon_monoxide", "current", "distance",
        "duration", "energy", "frequency", "gas", "humidity", "illuminance",
        "moisture", "monetary", "nitrogen_dioxide", "nitrogen_monoxide",
        "nitrous_oxide", "ozone", "pm1", "pm10", "pm25", "power", "power_factor",
        "precipitation", "precipitation_intensity", "pressure", "reactive_energy",
        "reactive_power", "signal_strength", "sound_pressure", "speed",
        "sulphur_dioxide", "temperature", "volatile_organic_compounds",
        "volatile_organic_compounds_parts", "voltage", "volume", "volume_flow_rate",
        "volume_storage", "water", "weight", "wind_speed",
    )

    fun validate(config: GatewayConfig): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (device in config.devices) {
            issues += validateDevice(device)
        }
        return issues
    }

    private fun validateDevice(device: DeviceConfig): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val isText = { s: SensorConfig -> s.platform == "text_sensor" }

        // ── 센서 검증 ──────────────────────────────────────────────────────────
        for (s in device.sensors) {
            val id = device.id
            val key = s.key

            val effStateClass = s.effectiveStateClass()

            // text_sensor에 numeric 전용 속성
            if (isText(s)) {
                if (s.unit != null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "unit='${s.unit}' is ignored for text_sensor — HA requires numeric values for sensors with units",
                        sensorPath(id, key, "unit"))
                if (effStateClass != null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "state_class='$effStateClass' is ignored for text_sensor",
                        sensorPath(id, key, "state_class"))
                if (s.accuracyDecimals != null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "accuracy_decimals is ignored for text_sensor",
                        sensorPath(id, key, "accuracy_decimals"))
                if (s.deviceClass in numericDeviceClasses)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "device_class='${s.deviceClass}' expects a numeric value but platform is text_sensor",
                        sensorPath(id, key, "device_class"))
            }

            // 숫자 센서 검증
            if (!isText(s)) {
                if (effStateClass != null && effStateClass !in validStatClasses)
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "invalid state_class='$effStateClass' — must be one of $validStatClasses",
                        sensorPath(id, key, "state_class"))
                if (s.unit != null && effStateClass == null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "unit='${s.unit}' set but state_class is missing — HA may infer measurement and show as graph",
                        sensorPath(id, key, "unit"))
                if (effStateClass != null && s.unit == null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "state_class='$effStateClass' set but unit is missing — HA long-term statistics require a unit",
                        sensorPath(id, key, "state_class"))
                if (s.deviceClass == "timestamp" && s.accuracyDecimals != null)
                    issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                        "accuracy_decimals is meaningless for device_class='timestamp'",
                        sensorPath(id, key, "accuracy_decimals"))
            }

            // decode 범위 검증
            if (s.decode != null) {
                val required = s.decode.offset + s.decode.length
                if (s.minLength != null && required > s.minLength) {
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "decode(offset=${s.decode.offset} + length=${s.decode.length}=$required) exceeds min_length=${s.minLength}",
                        sensorPath(id, key, "decode"))
                }
                if (s.length != null && required > s.length) {
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "decode(offset=${s.decode.offset} + length=${s.decode.length}=$required) exceeds length=${s.length}",
                        sensorPath(id, key, "decode"))
                }
            }

            // OBD 센서는 pid가 있어야 폴링 가능
            if (device.source == Source.obd && s.pid == null && s.preset == null)
                issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                    "OBD sensor requires 'pid' or 'preset' — sensor will be skipped",
                    sensorPath(id, key, "preset"))

            // OBD 센서는 formula(preset 포함) 또는 decode 중 하나로 값을 뽑는다.
            // preset은 설정 로딩 시 이미 formula로 펼쳐진 뒤 검증된다.
            if (device.source == Source.obd && s.pid != null && s.formula == null && s.decode == null)
                issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                    "OBD sensor has neither 'formula' nor 'decode' — sensor will never produce a value",
                    sensorPath(id, key, "formula"))

            // advertisement/gatt_notify 센서는 decode가 있어야 값 추출 가능
            if (device.source != Source.obd && s.decode == null && s.platform != "binary_sensor")
                issues += ValidationIssue(ValidationLevel.WARNING, id, key,
                    "no decode config — sensor will never produce a value",
                    sensorPath(id, key, "decode"))
        }

        // ── advertise 검증 ────────────────────────────────────────────────────────
        if (device.advertise != null) {
            val id = device.id
            if (device.source != Source.advertisement) {
                issues += ValidationIssue(
                    ValidationLevel.ERROR, id, null,
                    "'advertise' is only supported for source: advertisement",
                    devicePath(id, "advertise")
                )
            }
            val payloadErr = AdvertisePayload.validationError(device.advertise.payload)
            if (payloadErr != null) {
                issues += ValidationIssue(
                    ValidationLevel.ERROR, id, null,
                    payloadErr,
                    devicePath(id, "advertise.payload")
                )
            }
            if (device.advertise.counterStart !in 0..255) {
                issues += ValidationIssue(
                    ValidationLevel.ERROR, id, null,
                    "counter_start must be between 0 and 255 (got ${device.advertise.counterStart})",
                    devicePath(id, "advertise.counter_start")
                )
            }
            if (device.instanceMode == AdvertisementInstanceMode.mac && device.match?.mac.isNullOrBlank()) {
                issues += ValidationIssue(
                    ValidationLevel.WARNING, id, null,
                    "advertise with instance_mode: mac creates one button per discovered MAC — use instance_mode: shared",
                    devicePath(id, "instance_mode")
                )
            }
            if (device.controls.none { it.action == ControlAction.advertise }) {
                issues += ValidationIssue(
                    ValidationLevel.WARNING, id, null,
                    "'advertise' block has no control with action: advertise — it can only be triggered from the app",
                    devicePath(id, "advertise")
                )
            }
            if (device.advertise.includeDeviceName) {
                issues += ValidationIssue(
                    ValidationLevel.WARNING, id, null,
                    "include_device_name: true includes device name in advertising data, which may exceed the 31-byte legacy BLE limit",
                    devicePath(id, "advertise.include_device_name")
                )
            }
        }

        // ── 컨트롤 검증 ────────────────────────────────────────────────────────
        for (c in device.controls) {
            val id = device.id
            val key = c.key
            if (c.action != null) {
                if (device.advertise == null) {
                    issues += ValidationIssue(
                        ValidationLevel.ERROR, id, key,
                        "control action '${c.action}' requires an 'advertise' block on the device",
                        controlPath(id, key, "action")
                    )
                }
            } else {
                if (device.source == Source.gatt_notify && device.gatt?.writeCharUuid.isNullOrBlank())
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "control requires write_char_uuid in gatt config — control will be skipped",
                        controlPath(id, key, "command"))
                if (device.source == Source.obd && device.obd?.txCharUuid.isNullOrBlank())
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "control requires tx_char_uuid in obd config — control will be skipped",
                        controlPath(id, key, "command"))

                // 컨트롤 타입별 필수 command 키 검증
                val missingKeys = when (c.type) {
                    ControlType.switch -> listOf("on", "off").filter { it !in c.command }
                    ControlType.button -> listOf("press").filter { it !in c.command }
                    ControlType.number -> listOf("template").filter { it !in c.command }
                    ControlType.select -> c.options.filter { it !in c.command }
                }
                if (missingKeys.isNotEmpty())
                    issues += ValidationIssue(ValidationLevel.ERROR, id, key,
                        "control type '${c.type}' missing command key(s): $missingKeys — control will not work",
                        controlPath(id, key, "command"))
            }
        }

        return issues
    }

    /** ERROR 레벨 이슈가 있는 센서/컨트롤 key 집합 반환. BleRuntime이 선언/폴링에서 제외하는 데 사용. */
    fun errorKeys(issues: List<ValidationIssue>, deviceId: String): Set<String> =
        issues.filter { it.level == ValidationLevel.ERROR && it.deviceId == deviceId && it.sensorKey != null }
            .mapNotNull { it.sensorKey }.toSet()

    /** 기기 수준(sensorKey == null)의 ERROR 이슈 존재 여부. */
    fun hasDeviceError(issues: List<ValidationIssue>, deviceId: String): Boolean =
        issues.any { it.level == ValidationLevel.ERROR && it.deviceId == deviceId && it.sensorKey == null }

    /**
     * 센서/컨트롤의 구조적 변경(platform/type 변경, 항목 삭제) 여부 반환.
     * HA 엔티티 타입 불일치가 예상될 때 true → 기존 HA 엔티티를 삭제하고 재선언해야 함.
     */
    fun hasSensorStructureChange(oldD: DeviceConfig, newD: DeviceConfig): Boolean {
        val oldSensors = oldD.sensors.associateBy { it.key }
        val newSensors = newD.sensors.associateBy { it.key }
        if ((oldSensors.keys - newSensors.keys).isNotEmpty()) return true
        if (newSensors.any { (key, s) -> oldSensors[key]?.platform != s.platform }) return true
        val oldControls = oldD.controls.associateBy { it.key }
        val newControls = newD.controls.associateBy { it.key }
        if ((oldControls.keys - newControls.keys).isNotEmpty()) return true
        if (newControls.any { (key, c) -> oldControls[key]?.type != c.type || oldControls[key]?.action != c.action }) return true
        return false
    }

    /** oldConfig → newConfig 변환에서 HA 엔티티 구조가 바뀐 device ID 집합을 반환. */
    fun structurallyChangedDeviceIds(oldConfig: GatewayConfig, newConfig: GatewayConfig): Set<String> {
        val oldMap = oldConfig.devices.associateBy { it.id }
        return newConfig.devices
            .filter { newD -> oldMap[newD.id]?.let { hasSensorStructureChange(it, newD) } == true }
            .map { it.id }
            .toSet()
    }

    /**
     * 특정 device의 실제 HA 선언 내용(platform, unit, stateClass 등 앱이 보정 후 보내는 값)을
     * 문자열 fingerprint로 반환. 이전 fingerprint와 다르면 HA 엔티티 cleanup이 필요.
     * issues는 반드시 호출 전에 validate()로 한 번만 계산하여 넘길 것 (O(n²) 방지).
     */
    fun computeEffectiveFingerprint(d: DeviceConfig, issues: List<ValidationIssue>): String {
        val errKeys = errorKeys(issues, d.id)
        val sb = StringBuilder()
        for (s in d.sensors) {
            if (s.key in errKeys) continue
            val isText = s.platform == "text_sensor"
            sb.append("S|${s.key}|${s.platform}|${if (isText) null else s.unit}|${if (isText) null else s.effectiveStateClass()}|${s.deviceClass}|${s.accuracyDecimals}\n")
        }
        for (c in d.controls) {
            if (c.key in errKeys) continue
            sb.append("C|${c.key}|${c.type}|${c.action}\n")
        }
        return sb.toString()
    }

    /** config 전체를 받아 validate 후 fingerprint 계산. 단일 device 조회 시 편의 오버로드. */
    fun computeEffectiveFingerprint(config: GatewayConfig, deviceId: String): String {
        val d = config.devices.firstOrNull { it.id == deviceId } ?: return ""
        return computeEffectiveFingerprint(d, validate(config))
    }
}
