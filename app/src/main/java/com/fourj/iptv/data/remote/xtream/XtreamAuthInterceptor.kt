package com.fourj.iptv.data.remote.xtream

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Appends the panel credentials to every outgoing request.
 *
 * Xtream Codes requires `username` and `password` as query parameters on *all* calls, including
 * `action=login`. Doing it in one interceptor rather than on every method keeps it impossible to
 * forget, and keeps credentials out of the call sites.
 */
class XtreamAuthInterceptor(
    private val username: String,
    private val password: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.newBuilder()
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
