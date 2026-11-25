package me.gm.cleaner.client.ui

/**
 * 应用列表加载顺序的纯策略（无 Android 依赖，可 JVM 单测）。
 *
 * 旧顺序把列表加载串在服务恢复之后；服务未存活时 `recoverIfTargetRunning`
 * 会走完整启动流程，慢路径可能阻塞 10 秒以上。而主界面的"已配置应用"只依赖
 * 本地配置 + PackageManager，不需要服务在线。
 *
 * 新顺序：先出列表（服务不可用走本地降级），再恢复服务；只有首轮是本地降级
 * 且恢复成功时才补一次全量重载。
 */
internal object AppListLoadPlan {

    /**
     * 是否需要在服务恢复成功后补一次全量重载。
     *
     * - 首轮已是全量：再刷只会让列表闪一下；
     * - 恢复失败：服务仍不可用，重载还是同样的降级结果。
     */
    fun shouldReloadAfterRecovery(
        firstResultIsFullList: Boolean,
        recoverySucceeded: Boolean,
    ): Boolean = !firstResultIsFullList && recoverySucceeded
}
