package com.openminis.app.sandbox.offload

/**
 * Thread-safe holder for heavyweight Android services that are not needed at
 * process startup. Construction stays side-effect free until [value] is read.
 */
internal class DeferredInitializer<T>(factory: () -> T) {
    private val delegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    val value: T
        get() = delegate.value

    fun isInitialized(): Boolean = delegate.isInitialized()
}
