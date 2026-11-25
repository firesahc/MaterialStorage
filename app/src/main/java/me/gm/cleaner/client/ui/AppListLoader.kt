package me.gm.cleaner.client.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.PolicyRuleIndex
import me.gm.cleaner.core.config.policyRuleIndex
import me.gm.cleaner.model.PackageStatus

/** AppListLoader.load() 的结果：isFullList=false 表示服务端不可用时的本地降级结果。 */
data class AppListLoadResult(
    val list: List<AppListModel>,
    val isFullList: Boolean,
)

class AppListLoader(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val context: Context,
) {

    suspend fun load(preloadIcons: Boolean = true): AppListLoadResult =
        withContext(defaultDispatcher) {
            val t0 = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: start loading packages")
            val installedPackages = CleanerClient.getInstalledPackagesOrNull(PackageManager.GET_PERMISSIONS)
            val t1 = SystemClock.elapsedRealtime()
            if (installedPackages == null) {
                if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: service unavailable or RPC failed, fallback")
                val fallback = loadLocalFallback()
                val t2 = SystemClock.elapsedRealtime()
                logTiming(fallback.size, false, t1 - t0, 0L, 0L, t2 - t1, 0L)
                return@withContext AppListLoadResult(fallback, isFullList = false)
            }
            if (installedPackages.isNotEmpty()) {
                AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
            } else if (BuildConfig.DEBUG) {
                Log.w("CleanerTest", "AppListLoader.load: server returned empty installed list, keep previous label cache")
            }
            val t2 = SystemClock.elapsedRealtime()
            val srPackageStatus = CleanerClient.getSrPackagesStatusOrNull(PackageStatus.GET_FROM_ALL_PROCESS)
            val t3 = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: srPackageStatus size=${srPackageStatus?.size}")
            // 服务端映射就绪且查无记录等于真无，未就绪或链路失败一律按未知处理，不混用空表示两种含义。
            val statusUnknown = srPackageStatus == null
            // 一次 readSnapshot 建「包名 → 规则数」索引。逐包调用 getPackageSrCount()/getPackageReadOnly()
            // 会各自重新读文件 + 解析 JSON，几百个包就是几百次全量解析。
            val counts = ConfiguredPolicyStoreProvider.instance.policyRuleIndex()
            val result = installedPackages.map { pi ->
                ensureActive()
                val packageName = pi.packageName
                AppListModel(
                    pi,
                    AppLabelCache.getPackageLabel(pi),
                    counts.srCount(packageName),
                    counts.readOnlyCount(packageName),
                    parseMountState(if (statusUnknown) null else srPackageStatus?.get(packageName), statusUnknown)
                )
            }
            val t4 = SystemClock.elapsedRealtime()
            // 主界面只显示「已配置应用」，先把它们的图标预取进缓存，绑定阶段不再主线程逐条 loadIcon。
            // 新建挂载选择页显示全部应用，预取已配置图标收益有限，由 ViewModel 关闭该开关。
            if (preloadIcons) {
                preloadConfiguredIcons(installedPackages, counts)
            }
            val t5 = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.load: result=${result.size} apps")
            logTiming(result.size, true, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4)
            AppListLoadResult(result, isFullList = true)
        }

    /**
     * 分阶段耗时（release 也打）。
     *
     * 列表"慢半拍"的候选原因有多个（安装列表 RPC、应用名解析、挂载状态 RPC、模型映射），
     * 而 release 构建里 CleanerTest 不输出；每条加载一行，代价可忽略。
     */
    private fun logTiming(
        size: Int,
        full: Boolean,
        getInstalled: Long,
        labels: Long,
        status: Long,
        map: Long,
        icons: Long,
    ) {
        Log.i(
            TAG, "load: apps=" + size + " full=" + full +
                " getInstalled=" + getInstalled + "ms" +
                " labels=" + labels + "ms" +
                " status=" + status + "ms" +
                " map=" + map + "ms" +
                " icons=" + icons + "ms" +
                " total=" + (getInstalled + labels + status + map + icons) + "ms"
        )
    }

    /**
     * 后台预取「已配置应用」的图标。
     *
     * 主界面只显示配置过规则的包，通常十几个；其余项仍走绑定阶段懒加载。
     * 预算耗尽后放弃剩余项，避免图标预取拖慢首屏。
     */
    private suspend fun preloadConfiguredIcons(
        packages: List<PackageInfo>,
        counts: PolicyRuleIndex,
        budgetMs: Long = ICON_PRELOAD_BUDGET_MS,
    ) {
        val configured = packages.filter {
            counts.srCount(it.packageName) > 0 || counts.readOnlyCount(it.packageName) > 0
        }
        if (configured.isEmpty()) {
            return
        }
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            for (pi in configured) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    break
                }
                AppIconCache.preloadOne(context, pi)
            }
        }
    }

    /** 服务不可用时：用 PackageManager 获取客户端可见的全量应用，供主界面过滤规则包和新建挂载选择。 */
    private fun loadLocalFallback(): List<AppListModel> {
        val store = ConfiguredPolicyStoreProvider.instance
        val installedPackages = try {
            context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("CleanerTest", "AppListLoader.loadLocalFallback: failed to get local packages", e)
            emptyList()
        }
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListLoader.loadLocalFallback: packages=${installedPackages.size}")
        if (installedPackages.isNotEmpty()) {
            AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        } else if (BuildConfig.DEBUG) {
            Log.w("CleanerTest", "AppListLoader.loadLocalFallback: keep previous label cache on empty/failed PM result")
        }
        val counts = store.policyRuleIndex()
        return installedPackages.mapNotNull { pi ->
            val packageName = pi.packageName
            val label = AppLabelCache.getLabelIfCached(packageName)
                ?: pi.applicationInfo?.let { it.loadLabel(context.packageManager).toString() }
                ?: packageName
            AppListModel(
                pi,
                label,
                counts.srCount(packageName),
                counts.readOnlyCount(packageName),
                AppListModel.STATE_UNKNOWN,
            )
        }
    }

    /**
     * 服务端状态缺失分两种：映射就绪但查无记录等于真无，未就绪或链路失败等于未知。
     * 用 statusUnknown 显式区分，不混用空值的两种含义。
     */
    private fun parseMountState(packageStatus: PackageStatus?, statusUnknown: Boolean): Int {
        if (statusUnknown) return AppListModel.STATE_UNKNOWN
        packageStatus ?: return AppListModel.STATE_UNMOUNTED
        val mountedPids = mutableListOf<Int>()
        val unknownPids = mutableListOf<Int>()
        var managedCount = 0
        packageStatus.pidFlags.forEachIndexed { index, pidFlag ->
            // UNMANAGED 不进分母；其余终态互斥，MOUNT_FAILED 等为正交证据不影响分类。
            if (pidFlag and PackageStatus.PID_FLAG_UNMANAGED != 0) return@forEachIndexed
            managedCount++
            if (pidFlag and PackageStatus.PID_FLAG_MOUNTED != 0) {
                mountedPids += packageStatus.pids[index]
            } else if (pidFlag and PackageStatus.PID_FLAG_UNKNOWN != 0) {
                unknownPids += packageStatus.pids[index]
            } else if (pidFlag and (
                    PackageStatus.PID_FLAG_PARTIALLY_MOUNTED or
                        PackageStatus.PID_FLAG_NOT_MOUNTED or
                        PackageStatus.PID_FLAG_DELETED or
                        PackageStatus.PID_FLAG_OVERRIDE
                    ) == 0
            ) {
                // 无终态位（含旧版本 0 盲区）：证据不足，按 UNKNOWN 处理，不判未挂载。
                unknownPids += packageStatus.pids[index]
            }
        }
        if (managedCount == 0) {
            // 空列表保持旧语义 UNMOUNTED；存在进程但全部 UNMANAGED 则无法验证，判 UNKNOWN。
            return if (packageStatus.pids.isNotEmpty()) {
                AppListModel.STATE_UNKNOWN
            } else {
                AppListModel.STATE_UNMOUNTED
            }
        }
        return if (managedCount == mountedPids.size) {
            AppListModel.STATE_MOUNTED
        } else if (unknownPids.isNotEmpty()) {
            AppListModel.STATE_UNKNOWN
        } else if (mountedPids.isNotEmpty()) {
            AppListModel.STATE_MOUNT_EXCEPTION
        } else {
            AppListModel.STATE_UNMOUNTED
        }
    }

    suspend fun updateRuleCount(
        old: List<AppListModel>,
        counts: PolicyRuleIndex,
    ): List<AppListModel> = withContext(defaultDispatcher) {
        val srPackageStatus = CleanerClient.getSrPackagesStatusOrNull(PackageStatus.GET_FROM_ALL_PROCESS)
        old.map {
            val packageName = it.packageInfo.packageName
            it.copy(
                mountRulesCount = counts.srCount(packageName),
                readOnlyCount = counts.readOnlyCount(packageName),
                mountState = if (srPackageStatus == null) AppListModel.STATE_UNKNOWN else parseMountState(srPackageStatus[packageName], false),
            )
        }
    }

    private companion object {
        const val TAG = "MC/AppList"
        const val ICON_PRELOAD_BUDGET_MS = 400L
    }
}
