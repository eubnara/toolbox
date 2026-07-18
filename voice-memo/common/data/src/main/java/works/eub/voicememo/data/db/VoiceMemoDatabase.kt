package works.eub.voicememo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VoiceMemoEntity::class], version = 1, exportSchema = false)
abstract class VoiceMemoDatabase : RoomDatabase() {
    abstract fun voiceMemoDao(): VoiceMemoDao

    companion object {
        @Volatile
        private var INSTANCE: VoiceMemoDatabase? = null

        fun getDatabase(context: Context): VoiceMemoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VoiceMemoDatabase::class.java,
                    "voice_memo_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
