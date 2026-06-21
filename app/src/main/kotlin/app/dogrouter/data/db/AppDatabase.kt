package app.dogrouter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.Owner
import app.dogrouter.data.entity.SavedPlan

@Database(
    entities = [
        Dog::class,
        DogScheduleRule::class,
        DogIncompatibility::class,
        Appointment::class,
        SavedPlan::class,
        Owner::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dogDao(): DogDao
    abstract fun dogScheduleDao(): DogScheduleDao
    abstract fun dogIncompatibilityDao(): DogIncompatibilityDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun savedPlanDao(): SavedPlanDao
    abstract fun ownerDao(): OwnerDao
}
