package me.gm.cleaner.client.ui.storageredirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardPreserveProtectionTest {

    private val sdDir = "/storage/emulated/0"
    private val cacheDir = "$sdDir/Android/data/com.example/cache"
    private val dataDir = "$sdDir/Android/data/com.example"
    private val pictures = "$sdDir/pictures"
    private val dcim = "$sdDir/DCIM"

    @Test
    fun `isStrictSubPath 只接受严格子路径`() {
        assertTrue(isStrictSubPath(cacheDir, sdDir))
        assertTrue(isStrictSubPath("$sdDir/a", "/"))
        assertFalse(isStrictSubPath(sdDir, sdDir))
        assertFalse(isStrictSubPath("/storage/emulated/0x", sdDir))
    }

    @Test
    fun `source 被父 target 覆盖时追加保护规则`() {
        val existing = listOf(cacheDir to sdDir)
        val q3 = listOf(pictures to dcim)
        val result = appendPreserveProtection(existing + q3, q3)
        assertEquals(existing + q3 + (pictures to pictures), result)
    }

    @Test
    fun `source 是自身 target 的子路径时追加保护规则`() {
        val q3 = listOf(cacheDir to sdDir)
        val result = appendPreserveProtection(q3, q3)
        assertEquals(q3 + (cacheDir to cacheDir), result)
    }

    @Test
    fun `无覆盖关系时不追加保护规则`() {
        val q3 = listOf(pictures to dcim)
        val result = appendPreserveProtection(q3, q3)
        assertEquals(q3, result)
    }

    @Test
    fun `已有同名 PRESERVE 时不重复追加`() {
        val existing = listOf(cacheDir to sdDir, pictures to pictures)
        val q3 = listOf(pictures to dcim)
        val result = appendPreserveProtection(existing + q3, q3)
        assertEquals(existing + q3, result)
    }

    @Test
    fun `stripAutoPreserveRules 剥离自动保护规则`() {
        val allRules = listOf(cacheDir to sdDir, pictures to dcim, pictures to pictures)
        val remaining = listOf(pictures to dcim, pictures to pictures)
        assertEquals(listOf(pictures to dcim), stripAutoPreserveRules(allRules, remaining))
    }

    @Test
    fun `stripAutoPreserveRules 保留用户手动 PRESERVE 规则`() {
        val userPreserve = dataDir to dataDir
        val allRules = listOf(cacheDir to sdDir, userPreserve)
        val remaining = listOf(userPreserve)
        assertEquals(remaining, stripAutoPreserveRules(allRules, remaining))
    }

    @Test
    fun `createRules 的 q3 保护规则在 retrodict 后不重复`() {
        val existing = listOf(cacheDir to sdDir)
        val q3 = listOf(pictures to dcim)
        val generated = appendPreserveProtection(existing + q3, q3)
        val backtracked = stripAutoPreserveRules(generated, q3 + (pictures to pictures))
        assertEquals(q3, backtracked)
    }
}
