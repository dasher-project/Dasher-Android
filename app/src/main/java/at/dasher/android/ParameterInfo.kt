package at.dasher.android

/**
 * One entry from DasherCore's self-describing parameter schema, returned by
 * `dasher_get_parameter_info`. Android's settings UI is generated from a list of
 * these (mirroring DasherApple's `DasherSettingsView` / Dasher-Windows' `SettingsPanel`).
 *
 * Field order matches the C struct `dasher_parameter_info` and the JNI constructor
 * signature in `jni_bridge.cpp::nativeGetParameterInfo`.
 *
 * @param type      0=bool, 1=long, 2=string (-1 invalid)
 * @param uiType    suggested control: 0=none,1=switch,2=slider,3=step,4=enum,5=textField
 */
data class ParameterInfo(
    val key: Int,
    val name: String,
    val description: String,
    val group: String,
    val subgroup: String,
    val type: Int,
    val uiType: Int,
    val min: Long,
    val max: Long,
    val step: Long,
    val advanced: Int
) {
    val isBool get() = type == 0
    val isLong get() = type == 1
    val isString get() = type == 2
}
