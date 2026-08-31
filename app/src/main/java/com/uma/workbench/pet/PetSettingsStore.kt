package com.uma.workbench.pet

import android.content.Context
import android.content.SharedPreferences

/**
 * 桌宠开关的本地持久化。独立 SharedPreferences 文件，与其他设置互不覆盖。
 */
class PetSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pet-settings", Context.MODE_PRIVATE)

    fun loadEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun saveEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private companion object {
        const val KEY_ENABLED = "pet_enabled"
    }
}
