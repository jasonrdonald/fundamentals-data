package com.fundamentalanalysis.app.data.remote

import android.content.Context
import com.fundamentalanalysis.app.BuildConfig
import com.fundamentalanalysis.app.data.remote.dto.DatasetDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import retrofit2.http.GET
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Singleton

interface DatasetApi {
    @GET
    suspend fun fetchDataset(@Url url: String): DatasetDto
}

/** The shared, read-only dataset every install reads. No authentication. */
interface RemoteDatasetSource {
    val isConfigured: Boolean
    suspend fun fetch(): DatasetDto
}

@Singleton
class HttpRemoteDatasetSource @Inject constructor(
    private val api: DatasetApi,
) : RemoteDatasetSource {
    private val url: String = BuildConfig.DATASET_URL.trim()
    override val isConfigured: Boolean get() = url.isNotEmpty()
    override suspend fun fetch(): DatasetDto = api.fetchDataset(url)
}

/** Sample dataset shipped inside the APK so the app is useful offline on first launch. */
interface SeedDatasetSource {
    /** Version of the bundled file, known without parsing it. */
    val version: Int
    suspend fun load(): DatasetDto
}

@Singleton
class AssetSeedDatasetSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : SeedDatasetSource {
    override val version: Int = BuildConfig.SEED_DATASET_VERSION

    override suspend fun load(): DatasetDto =
        context.assets.open(SEED_FILE).bufferedReader().use { json.decodeFromString(it.readText()) }

    private companion object {
        const val SEED_FILE = "seed_dataset.json"
    }
}
