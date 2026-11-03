package com.example.mockrules

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Debug builds: adds the mock interceptor. Release builds get the no-op twin in src/release, so
 * call this from shared code where you build your OkHttpClient:
 *
 * ```
 * OkHttpClient.Builder()
 *     .addMockRules(context)   // first, so other interceptors see the mocked response
 *     ...
 * ```
 *
 * It's an application interceptor, so bodies are already decompressed and it sees one call per request.
 */
fun OkHttpClient.Builder.addMockRules(context: Context): OkHttpClient.Builder {
    MockRules.init(context)
    return addInterceptor(MockRulesInterceptor(MockRules.engine))
}
