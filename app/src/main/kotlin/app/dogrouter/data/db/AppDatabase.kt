package app.dogrouter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.entity.BillableService
import app.dogrouter.data.entity.CommittedDay
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogIncompatibility
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.entity.Invoice
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
        BillableService::class,
        CommittedDay::class,
        Invoice::class,
    ],
    version = 14,
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
    abstract fun billableServiceDao(): BillableServiceDao
    abstract fun committedDayDao(): CommittedDayDao
    abstract fun invoiceDao(): InvoiceDao
}
