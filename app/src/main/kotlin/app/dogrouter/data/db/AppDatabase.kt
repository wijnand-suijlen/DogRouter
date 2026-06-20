package app.dogrouter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule

@Database(
    entities = [
        Dog::class,
        DogScheduleRule::class,
        DogIncompatibility::class,
        Appointment::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dogDao(): DogDao
    abstract fun dogScheduleDao(): DogScheduleDao
    abstract fun dogIncompatibilityDao(): DogIncompatibilityDao
    abstract fun appointmentDao(): AppointmentDao
}
