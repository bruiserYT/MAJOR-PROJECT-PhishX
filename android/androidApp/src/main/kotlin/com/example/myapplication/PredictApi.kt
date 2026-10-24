package com.example.myapplication

import android.os.Build
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class PredictRequest(val url: String)

data class PredictResponse(
    val verdict: String,
    val confidence: Double,
    val reason: String? = null
)

interface PredictApi {
    @POST("predict")
    suspend fun predict(@Body request: PredictRequest): PredictResponse
}

object Api {
    @Volatile
    var customBaseUrl: String? = null
        set(value) {
            field = value
            cachedService = null
        }

    private var cachedService: PredictApi? = null

    private fun isEmulator(): Boolean {
        return try {
            (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                    || "google_sdk" == Build.PRODUCT)
        } catch (_: Throwable) {
            false
        }
    }

    fun getBaseUrl(): String {
        customBaseUrl?.let { return it }
        return if (isEmulator()) {
            "http://10.0.2.2:8000/"
        } else {
            "http://127.0.0.1:8000/"
        }
    }

    private fun createService(): PredictApi {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(getBaseUrl())
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PredictApi::class.java)
    }

    val service: PredictApi
        get() {
            return cachedService ?: synchronized(this) {
                cachedService ?: createService().also { cachedService = it }
            }
        }
}
