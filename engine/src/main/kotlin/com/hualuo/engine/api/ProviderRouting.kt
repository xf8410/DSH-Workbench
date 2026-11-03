package com.hualuo.engine.api

object ProviderRouting {
    @Volatile private var resolver: ((String) -> ProviderSession?)? = null

    fun install(fn: (String) -> ProviderSession?) {
        resolver = fn
    }

    fun sessionFor(model: String): ProviderSession? = resolver?.invoke(model)
}
