package app.dogrouter.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.dogrouter.data.db.ALL_MIGRATIONS
import app.dogrouter.data.db.AppDatabase
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.data.remote.BanApi
import app.dogrouter.data.routing.BRouterRoutingProvider
import app.dogrouter.data.routing.RoutingDataInstaller
import app.dogrouter.data.routing.RoutingDataPaths
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.routing.RoutingProvider
import app.dogrouter.ui.addresspicker.AddressPickerViewModel
import app.dogrouter.ui.dogs.DogEditViewModel
import app.dogrouter.ui.dogs.DogListViewModel
import app.dogrouter.ui.followplan.FollowPlanViewModel
import app.dogrouter.ui.settings.SettingsViewModel
import app.dogrouter.ui.today.TodayViewModel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.time.LocalDate
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
    single { get<AppDatabase>().dogIncompatibilityDao() }

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("settings") },
        )
    }
    single { SettingsRepository(get()) }

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

    // Routing data + engine. The downloader uses its own OkHttp client
    // with a longer read timeout for the ~125 MB segment download.
    single { RoutingDataPaths(androidContext()) }
    single(qualifier = org.koin.core.qualifier.named("routingDownloader")) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS) // disable overall call timeout
            .build()
    }
    single {
        RoutingDataInstaller(
            context = androidContext(),
            paths = get(),
            httpClient = get(qualifier = org.koin.core.qualifier.named("routingDownloader")),
        )
    }
    single<RoutingProvider> { BRouterRoutingProvider(get()) }

    single { DayPlanService(get(), get(), get(), get(), get()) }

    viewModel { DogListViewModel(get()) }
    viewModel { (dogId: String?) -> DogEditViewModel(get(), get(), get(), get(), dogId) }
    viewModel { AddressPickerViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { TodayViewModel(get()) }
    viewModel { (date: LocalDate) -> FollowPlanViewModel(get(), date) }
}
