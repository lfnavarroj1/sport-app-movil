package com.lfnj.sport_app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** Agrega un `X-Correlation-ID` UUID a cada solicitud que no lo traiga (el BFF solo acepta UUID). */
class CorrelationIdInterceptor(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(CORRELATION_HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(CORRELATION_HEADER, newId()).build())
    }
}
