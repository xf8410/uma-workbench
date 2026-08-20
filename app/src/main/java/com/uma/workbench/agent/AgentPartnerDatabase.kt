package com.uma.workbench.agent

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentProfileEntity::class,
        AgentDiaryEntryEntity::class,
        AgentGroupEntity::class,
        AgentGroupMemberEntity::class,
        AgentGroupMessageEntity::class,
        AgentGroupContextSourceEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AgentPartnerDatabase : RoomDatabase() {
    abstract fun profiles(): AgentProfileDao
    abstract fun diaries(): AgentDiaryDao
    abstract fun groups(): AgentGroupDao

    companion object {
        @Volatile
        private var instance: AgentPartnerDatabase? = null

        fun get(context: Context): AgentPartnerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AgentPartnerDatabase::class.java,
                "uma-workbench-agents.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
