package com.monstertoss.wfp

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import androidx.room.*
import androidx.room.ForeignKey.CASCADE
import androidx.room.OnConflictStrategy.REPLACE
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.PropertyName
import kotlinx.android.parcel.Parcelize
import java.util.*

@Entity
data class Device(
        @PrimaryKey var id: String,
        var name: String,
        var ownKey: String,
        var otherKey: String,
        var createdAt: Date = Date(),
        var lastUsed: Date = Date()
)

@Entity(foreignKeys = [ForeignKey(entity = Device::class, parentColumns = ["id"], childColumns = ["device"], onUpdate = CASCADE, onDelete = CASCADE)])
data class Challenge(
        @PrimaryKey val challenge: String,
        var timestamp: Date = Date(),
        var signature: String? = null, // lateinit
        var response: String? = null,
        var responseAt: Date = Date(),
        var canceled: Boolean = false,
        var device: String? = null
)

@Parcelize
data class Info(
        @get:PropertyName("p") @set:PropertyName("p") var publicKey: String? = null,
        @get:PropertyName("s") @set:PropertyName("s") var signature: String? = null
) : Parcelable

data class DeviceAndInfo(val device: Device, val info: Info)

@Dao
interface DeviceDAO {
    @Insert(onConflict = REPLACE)
    fun save(device: Device)

    @Delete
    fun delete(device: Device)

    @Query("SELECT * FROM device ORDER BY createdAt")
    fun load(): Array<Device>

    @Query("SELECT * FROM device WHERE id = :id")
    fun load(id: String): Device?

    @Query("SELECT * FROM device ORDER BY createdAt")
    fun bind(): LiveData<Array<Device>>

    @Query("UPDATE device SET lastUsed = :lastUsed WHERE id = :device")
    fun updateLastUsed(device: Device, lastUsed: Date)
}

@Dao
abstract class ChallengeDAO {
    @Insert(onConflict = REPLACE)
    abstract fun save(challenge: Challenge)

    @Delete
    abstract fun delete(challenge: Challenge)

    @Query("SELECT * FROM challenge WHERE device = :device ORDER BY timestamp DESC")
    abstract fun load(device: Device): Array<Challenge>

    @Query("SELECT * FROM challenge WHERE device = :device ORDER BY timestamp DESC")
    abstract fun bindRaw(device: Device): DataSource.Factory<Int, Challenge>

    fun bind(device: Device): LiveData<PagedList<Challenge>> = LivePagedListBuilder(bindRaw(device), 20).build()

    @Query("SELECT * FROM challenge WHERE challenge = :challenge")
    abstract fun load(challenge: String): Challenge?

    fun fromSnapshot(snapshot: DataSnapshot, device: Device): Challenge? {
        return (snapshot.key ?: return null).run {
            val challenge = this
            (load(challenge) ?: Challenge(challenge)).apply {
                this.device = device.id
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return Date(value ?: return null)
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?) = date?.time

    @TypeConverter
    fun deviceToString(device: Device?) = device?.id
}

@Database(entities = [Device::class, Challenge::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract val devices: DeviceDAO
    abstract val challenges: ChallengeDAO
}

