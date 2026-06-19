package app.dogrouter.data.db

import androidx.room.TypeConverter
import app.dogrouter.data.entity.TransportState
import java.time.LocalTime

class Converters {
    @TypeConverter
    fun fromLocalTime(value: LocalTime?): String? = value?.toString()

    @TypeConverter
    fun toLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter
    fun fromTransportState(value: TransportState?): String? = value?.name

    @TypeConverter
    fun toTransportState(value: String?): TransportState? = value?.let(TransportState::valueOf)
}
