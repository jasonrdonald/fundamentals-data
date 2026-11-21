package com.fundamentalanalysis.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.fundamentalanalysis.app.BuildConfig
import com.fundamentalanalysis.app.data.local.AppDatabase
import com.fundamentalanalysis.app.data.local.MarketDao
import com.fundamentalanalysis.app.data.local.WatchlistDao
import com.fundamentalanalysis.app.data.remote.AssetSeedDatasetSource
import com.fundamentalanalysis.app.data.remote.DatasetApi
import com.fundamentalanalysis.app.data.remote.HttpRemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import com.fundamentalanalysis.app.data.repository.LocalWatchlistRepository
import com.fundamentalanalysis.app.data.repository.OfflineFirstMarketRepository
import com.fundamentalanalysis.app.domain.repository.MarketRepository
import com.fundamentalanalysis.app.domain.repository.WatchlistRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.Cache
import com.fundamentalanalysis.app.data.local.MIGRATION_5_6
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** DataStore holding user preferences (separate from dataset sync metadata). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

/** Lives as long as the process; for work and caches shared across screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    @Provides @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fundamentals.db")
            // 5 -> 6 keeps the watchlist and rebuilds the dataset tables (refilled from the dataset).
            .addMigrations(MIGRATION_5_6)
            // Older schemas only cached the shared dataset; they are rebuilt from it.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun marketDao(db: AppDatabase): MarketDao = db.marketDao()

    @Provides
    fun watchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()

    @Provides
    fun portfolioDao(db: AppDatabase): com.fundamentalanalysis.app.data.local.PortfolioDao = db.portfolioDao()

    @Provides @Singleton @SettingsStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        // HTTP cache: an unchanged manifest is revalidated with its ETag (304) instead of re-downloaded.
        .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .build()

    @Provides @Singleton
    fun datasetApi(client: OkHttpClient, json: Json): DatasetApi = Retrofit.Builder()
        // Requests use absolute URLs via @Url; the base URL is only a required placeholder.
        .baseUrl("https://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(DatasetApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun marketRepository(impl: OfflineFirstMarketRepository): MarketRepository
    @Binds abstract fun watchlistRepository(impl: LocalWatchlistRepository): WatchlistRepository
    @Binds abstract fun portfolioRepository(impl: com.fundamentalanalysis.app.data.portfolio.LocalPortfolioRepository): com.fundamentalanalysis.app.domain.repository.PortfolioRepository
    @Binds abstract fun settingsRepository(impl: com.fundamentalanalysis.app.data.local.SettingsPreferences): com.fundamentalanalysis.app.domain.repository.SettingsRepository
    @Binds abstract fun remoteDatasetSource(impl: HttpRemoteDatasetSource): RemoteDatasetSource
    @Binds abstract fun seedDatasetSource(impl: AssetSeedDatasetSource): SeedDatasetSource
    @Binds abstract fun tickerChecker(impl: com.fundamentalanalysis.app.data.portfolio.YahooTickerChecker): com.fundamentalanalysis.app.domain.repository.TickerChecker
    @Binds abstract fun holdingStocksRepository(impl: com.fundamentalanalysis.app.data.portfolio.LocalHoldingStocksRepository): com.fundamentalanalysis.app.domain.repository.HoldingStocksRepository
}
