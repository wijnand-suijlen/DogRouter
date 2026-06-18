package app.dogrouter.di

import androidx.room.Room
import app.dogrouter.data.db.AppDatabase
import app.dogrouter.ui.dogs.DogListViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "dogrouter.db",
        ).build()
    }
    single { get<AppDatabase>().dogDao() }

    viewModel { DogListViewModel(get()) }
}
