package app.dogrouter.di

import androidx.room.Room
import app.dogrouter.data.db.ALL_MIGRATIONS
import app.dogrouter.data.db.AppDatabase
import app.dogrouter.ui.dogs.DogEditViewModel
import app.dogrouter.ui.dogs.DogListViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "dogrouter.db",
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
    single { get<AppDatabase>().dogDao() }
    single { get<AppDatabase>().dogScheduleDao() }

    viewModel { DogListViewModel(get()) }
    viewModel { (dogId: String?) -> DogEditViewModel(get(), get(), dogId) }
}
