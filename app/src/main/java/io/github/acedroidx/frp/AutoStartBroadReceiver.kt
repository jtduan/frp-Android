package io.github.acedroidx.frp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AutoStartBroadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val autoStartOnBoot = preferences.getBoolean(PreferencesKey.AUTO_START, false)
                if (!autoStartOnBoot) return
                val configList = AutoStartHelper.loadAutoStartConfigs(context)
                startShellService(context, configList)
            }

            BroadcastAction.START -> {
                val enableBroadcastStart =
                    preferences.getBoolean(PreferencesKey.AUTO_START_BROADCAST, false)
                if (!enableBroadcastStart) return

                val allowExtra =
                    preferences.getBoolean(PreferencesKey.AUTO_START_BROADCAST_EXTRA, false)

                val configList = if (allowExtra) {
                    val type =
                        AutoStartHelper.parseType(intent.getStringExtra(BroadcastExtraKey.TYPE))
                    val name = intent.getStringExtra(BroadcastExtraKey.NAME)

                    if (type != null && !name.isNullOrBlank()) {
                        AutoStartHelper.loadAutoStartConfigs(
                            context, typeFilter = type, nameFilter = name
                        )
                    } else {
                        AutoStartHelper.loadAutoStartConfigs(context)
                    }
                } else {
                    AutoStartHelper.loadAutoStartConfigs(context)
                }

                startShellService(context, configList)
            }
        }
    }

    private fun startShellService(context: Context, configList: List<FrpConfig>) {
        if (configList.isEmpty()) return

        val mainIntent = Intent(context, ShellService::class.java)
        mainIntent.action = ShellServiceAction.START
        mainIntent.putParcelableArrayListExtra(IntentExtraKey.FrpConfig, ArrayList(configList))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(mainIntent)
        } else {
            context.startService(mainIntent)
        }
    }
}