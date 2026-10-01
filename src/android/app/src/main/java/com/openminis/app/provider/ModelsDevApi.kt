@file:Suppress("unused")

package com.openminis.app.provider

/**
 * models.dev 目录的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.models.ModelsDevCatalog]：三级缓存（内存 →
 * cacheDir/models-dev-cache/api.json → 打包资产）、48h TTL 与单飞后台
 * 刷新、字段映射与富化并入规则。源 URL、缓存文件名、TTL、嵌套类型
 * （ProviderEntry / ModelDevEntry / CatalogModel）形状均为冻结面——
 * ModelReleaseIndex / ModelsCatalogApi / XAI / OpenRouter / 供应层刷新
 * 与 UI 全限定名引用全部钉在本名上。
 */
typealias ModelsDevApi = novex.android.models.ModelsDevCatalog
