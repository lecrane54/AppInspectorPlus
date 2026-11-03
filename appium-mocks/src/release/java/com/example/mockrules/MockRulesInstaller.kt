package com.example.mockrules

import android.content.Context
import okhttp3.OkHttpClient

/** Release builds: no mocks, no receiver, nothing added. See src/debug for the real one. */
@Suppress("UNUSED_PARAMETER")
fun OkHttpClient.Builder.addMockRules(context: Context): OkHttpClient.Builder = this
