package com.hermes.harness.core

import kotlinx.serialization.json.Json

/**
 * One JSON configuration for everything Hermes reads or writes.
 *
 * [explicitNulls] is off so optional wire fields (temperature, tools, …) are
 * omitted rather than sent as `null`, and [encodeDefaults] is on so flags like
 * `stream = true` are always transmitted.
 */
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
