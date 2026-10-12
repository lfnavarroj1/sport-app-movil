package com.lfnj.sport_app.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Único punto de construcción del cliente HTTP hacia el BFF móvil. No agrega interceptores
 * de registro: cuerpos y encabezados pueden contener credenciales o tokens.
 */
object BffMobileClient {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(CorrelationIdInterceptor())
        .build()

    fun retrofit(baseUrl: String, client: OkHttpClient = okHttp()): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
