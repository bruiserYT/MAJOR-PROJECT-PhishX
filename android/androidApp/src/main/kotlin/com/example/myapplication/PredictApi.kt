package com.example.myapplication

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class PredictRequest(val url: String)
data class PredictResponse(val verdict: String, val confidence: Double)

interface PredictApi {
    @POST("predict")
    suspend fun predict(@Body request: PredictRequest): PredictResponse
}

object Api {
    val service: PredictApi by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8000/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PredictApi::class.java)
    }
}