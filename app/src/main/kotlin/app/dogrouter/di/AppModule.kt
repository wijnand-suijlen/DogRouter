package app.dogrouter.di

import androidx.room.Room
import app.dogrouter.data.db.ALL_MIGRATIONS
import app.dogrouter.data.db.AppDatabase
import app.dogrouter.data.remote.BanApi
import app.dogrouter.ui.dogs.DogEditViewModel
import app.dogrouter.ui.dogs.DogListViewModel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

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

    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
    single { BanApi(get(), get()) }

    viewModel { DogListViewModel(get()) }
    viewModel { (dogId: String?) -> DogEditViewModel(get(), get(), get(), dogId) }
}
