package com.uma.workbench.plugin

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PluginRegistryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PluginRegistryDatabase : RoomDatabase() {
    abstract fun plugins(): PluginRegistryDao

    companion object {
        @Volatile private var instance: PluginRegistryDatabase? = null

        fun get(context: Context): PluginRegistryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PluginRegistryDatabase::class.java,
                "uma-plugin-registry.db"
            ).build().also { instance = it }
        }
    }
}
