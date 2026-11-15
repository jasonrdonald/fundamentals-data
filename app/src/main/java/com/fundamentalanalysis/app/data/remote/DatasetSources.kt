package com.fundamentalanalysis.app.data.remote

import android.content.Context
import com.fundamentalanalysis.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Singleton

interface DatasetApi {
    /** Raw bytes, so the app can verify a part's SHA-256 before using it. */
    @GET
    suspend fun fetch(@Url url: String, @Header("Cache-Control") cacheControl: String? = null): ResponseBody
}

/**
 * Where dataset files come from: the manifest, then each part named in it. Both sources return
 * raw bytes; the sync engine checks them against the manifest's SHA-256.
 */
interface DatasetFileSource {
    suspend fun manifest(revalidate: Boolean = false): ByteArray
    suspend fun file(name: String): ByteArray
}

/** The shared, read-only dataset every install reads. No authentication. */
interface RemoteDatasetSource : DatasetFileSource {
    val isConfigured: Boolean
}

@Singleton
class HttpRemoteDatasetSource @Inject constructor(
    private val api: DatasetApi,
) : RemoteDatasetSource {
    private val manifestUrl: String = BuildConfig.MANIFEST_URL.trim()
    override val isConfigured: Boolean get() = manifestUrl.isNotEmpty()

    // "no-cache" still uses the HTTP cache, but revalidates (ETag): an unchanged file costs a 304.
    override suspend fun manifest(revalidate: Boolean): ByteArray =
        api.fetch(manifestUrl, if (revalidate) "no-cache" else null).use { it.bytes() }

    override suspend fun file(name: String): ByteArray =
        api.fetch(manifestUrl.substringBeforeLast('/') + "/" + name).use { it.bytes() }
}

/** The same files shipped inside the APK (assets/seed), so the app works offline from first launch. */
interface SeedDatasetSource : DatasetFileSource

@Singleton
class AssetSeedDatasetSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SeedDatasetSource {
    override suspend fun manifest(revalidate: Boolean): ByteArray = file("manifest.json")
    override suspend fun file(name: String): ByteArray = context.assets.open("seed/$name").use { it.readBytes() }
}
