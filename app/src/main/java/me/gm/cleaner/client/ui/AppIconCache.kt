package me.gm.cleaner.client.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 应用图标缓存。
 *
 * 应用列表和文件系统记录列表都会反复绑定同一个包；若每次都在主线程
 * `PackageManager.loadIcon()`，单条可能耗时 30–400ms，滚动/回榜时会一格一格补图标。
 * 这里缓存图标的 [Drawable.ConstantState]（共享的图标数据），实际 Drawable 仍按
 * 各自的 Resources 新建，与旧实现复用图标的方式等价。
 *
 * 缓存同时服务于主列表的后台预取和列表绑定时的懒加载；[preloadOne] 必须在
 * 非主线程调用。
 */
object AppIconCache {

    private class CachedIcon(
        val lastUpdateTime: Long,
        val constantState: Drawable.ConstantState,
    )

    /**
     * 96 项：覆盖已配置应用、记录页一屏以及常见滚动回退范围。
     * 单条 ConstantState 量级约 50KB，最坏约 5MB。
     */
    private val cache = object : LruCache<String, CachedIcon>(96) {}

    /** 命中且应用未更新时返回可复用的 ConstantState。 */
    fun get(packageName: String, lastUpdateTime: Long): Drawable.ConstantState? =
        cache.get(packageName)?.takeIf { it.lastUpdateTime == lastUpdateTime }?.constantState

    fun put(packageName: String, lastUpdateTime: Long, constantState: Drawable.ConstantState) {
        cache.put(packageName, CachedIcon(lastUpdateTime, constantState))
    }

    /**
     * 预取单个包的图标并写入缓存。
     *
     * 必须在非主线程调用；任一步失败都静默跳过，绑定阶段仍会回退到懒加载，行为不退化。
     */
    fun preloadOne(context: Context, packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        if (get(packageName, packageInfo.lastUpdateTime) != null) {
            return
        }
        val state = runCatching {
            packageInfo.applicationInfo?.loadIcon(context.packageManager)?.constantState
        }.getOrNull() ?: return
        put(packageName, packageInfo.lastUpdateTime, state)
    }
}
