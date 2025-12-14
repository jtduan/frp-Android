package io.github.acedroidx.frp

import android.content.Context

object AutoStartHelper {
    fun loadAutoStartConfigs(
        context: Context, typeFilter: FrpType? = null, nameFilter: String? = null
    ): List<FrpConfig> {
        val preferences = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        val result = mutableListOf<FrpConfig>()

        fun addConfigs(type: FrpType, key: String) {
            if (typeFilter != null && typeFilter != type) return
            val names = preferences.getStringSet(key, emptySet()) ?: emptySet()
            names.forEach { name ->
                if (nameFilter != null && nameFilter != name) return@forEach
                val config = FrpConfig(type, name)
                if (config.getFile(context).exists()) {
                    result.add(config)
                }
            }
        }

        addConfigs(FrpType.FRPC, PreferencesKey.AUTO_START_FRPC_LIST)
        addConfigs(FrpType.FRPS, PreferencesKey.AUTO_START_FRPS_LIST)

        return result
    }

    fun parseType(typeValue: String?): FrpType? {
        return when (typeValue?.lowercase()) {
            FrpType.FRPC.typeName -> FrpType.FRPC
            FrpType.FRPS.typeName -> FrpType.FRPS
            else -> null
        }
    }
}
