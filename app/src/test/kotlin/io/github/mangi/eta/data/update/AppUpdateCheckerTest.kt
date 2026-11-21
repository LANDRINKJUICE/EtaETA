package io.github.mangi.eta.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun newerPatchVersionIsNewer() {
        assertTrue(AppUpdateChecker.isNewer("3.0.7", "3.0.6"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(AppUpdateChecker.isNewer("3.0.5", "3.0.6"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertTrue(AppUpdateChecker.isNewer("3.1", "3.0.9"))
        assertFalse(AppUpdateChecker.isNewer("3.0", "3.0.1"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertTrue(AppUpdateChecker.isNewer("v3.1.0", "3.0.6"))
        assertFalse(AppUpdateChecker.isNewer("v3.0.6", "3.0.6"))
    }

    @Test
    fun debugSuffixIsIgnored() {
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6-debug"))
        assertTrue(AppUpdateChecker.isNewer("3.0.10", "3.0.9-debug"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6+a1b2c3d"))
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6-debug+a1b2c3d"))
        assertTrue(AppUpdateChecker.isNewer("3.0.7", "3.0.6+a1b2c3d"))
    }

    /**
     * 本 fork 发的是 `1.1.3-dsh` 这类带发行标记的版本。
     * 两侧都必须剥掉 `-dsh`：只剥当前版本会导致远端 `1.1.3-dsh` 与本地 `1.1.3`
     * 走字符串比较（`"3-dsh" > "3"`），同一版本被误判为有新版本。
     */
    @Test
    fun forkReleaseSuffixIsIgnoredOnBothSides() {
        assertFalse(AppUpdateChecker.isNewer("1.1.3-dsh", "1.1.3-dsh"))
        assertFalse(AppUpdateChecker.isNewer("v1.1.3-dsh", "1.1.3-dsh"))
        assertFalse(AppUpdateChecker.isNewer("1.1.2-dsh", "1.1.3-dsh"))
        assertTrue(AppUpdateChecker.isNewer("1.1.4-dsh", "1.1.3-dsh"))
        assertTrue(AppUpdateChecker.isNewer("1.2.0-dsh", "1.1.3-dsh"))
    }

    /** 发行标记不影响数值比较：1.1.10 必须大于 1.1.9。 */
    @Test
    fun forkReleaseSuffixKeepsNumericOrdering() {
        assertTrue(AppUpdateChecker.isNewer("1.1.10-dsh", "1.1.9-dsh"))
        assertFalse(AppUpdateChecker.isNewer("1.1.9-dsh", "1.1.10-dsh"))
    }
}
