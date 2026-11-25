package me.gm.cleaner.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用列表加载顺序单测。
 *
 * 首轮已是全量时不得重复刷新，避免列表闪一下；
 * 本地降级且恢复失败时不得重载，避免白跑一次全量扫描。
 */
class AppListLoadPlanTest {

    @Test
    fun `首轮全量且恢复成功不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(true, true))
    }

    @Test
    fun `首轮全量且恢复失败不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(true, false))
    }

    @Test
    fun `首轮降级且恢复失败不重载`() {
        assertFalse(AppListLoadPlan.shouldReloadAfterRecovery(false, false))
    }

    @Test
    fun `首轮降级且恢复成功需要重载`() {
        assertTrue(AppListLoadPlan.shouldReloadAfterRecovery(false, true))
    }
}
