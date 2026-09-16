package com.kyant.backdrop

import android.annotation.SuppressLint

interface RuntimeShaderCache {

    fun obtainRuntimeShader(key: String, string: String): RuntimeShader
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()

    // RuntimeShader 需要 API 33；调用方（lens / highlight）已用
    // isRuntimeShaderSupported() 在入口处提前 return，此处不会在旧系统执行。
    @SuppressLint("NewApi")
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { RuntimeShader(string) }
    }

    fun clear() {
        runtimeShaders.clear()
    }
}
