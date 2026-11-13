package io.github.mangi.eta.agent.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全 App 通知号段登记表的纯 JVM 回归（治「散乱」的第一块护栏）。
 *
 * 钉住：现网所有已登记段两两不相交、新增段撞车能被 [NotificationIdRegistry.validate] 抓到、
 * 通知 ID 与 requestCode 是两个独立空间（同数值不冲突）、ownerOf / isFree 查询正确。
 * 任何新功能在 [NotificationIdRegistry.allIdSegments] 里加段若与既有段相交，这里会直接红灯。
 */
class NotificationIdRegistryTest {

    @Test
    fun `all currently registered segments are conflict free`() {
        assertTrue(
            "登记表出现段冲突：${NotificationIdRegistry.conflicts()}",
            NotificationIdRegistry.validate(),
        )
        assertEquals(emptyList<String>(), NotificationIdRegistry.conflicts())
    }

    @Test
    fun `notification id space and request code space are validated independently`() {
        // 1108（执行服务通知 ID）与 rc=20、9600（宏录制）数值可在两个空间各出现，不互相打架。
        assertTrue(NotificationIdRegistry.allIdSegments.any { 1_108 in it.range })
        assertTrue(NotificationIdRegistry.allRequestCodeSegments.any { 9_600 in it.range })
        // 两个空间分别都干净 → 全量也干净。
        assertEquals(
            emptyList<String>(),
            NotificationIdRegistry.conflictsInSpace(NotificationIdRegistry.allIdSegments),
        )
        assertEquals(
            emptyList<String>(),
            NotificationIdRegistry.conflictsInSpace(NotificationIdRegistry.allRequestCodeSegments),
        )
    }

    @Test
    fun `a newly added overlapping segment is detected`() {
        // 模拟未来某个功能没查表就用了 9400（待确认汇总已有），必须被抓出来。
        val bad = NotificationIdRegistry.allIdSegments + NotificationIdRegistry.IdSegment(
            owner = "new_feature_forgot_to_check",
            range = 9_400..9_400,
        )
        val found = NotificationIdRegistry.conflictsInSpace(bad)
        assertTrue("新增段撞既有段必须被检测到", found.isNotEmpty())
        assertTrue(found.any { it.contains("new_feature_forgot_to_check") })
    }

    @Test
    fun `a genuinely free segment is reported as free`() {
        assertTrue("8xxx 段无人占用，应为空闲", NotificationIdRegistry.isNotificationIdRangeFree(8_000..8_010))
        assertFalse("9400 已归 approval_pending，不能当空闲段", NotificationIdRegistry.isNotificationIdRangeFree(9_400..9_400))
        assertFalse("9500..9501 虽标预留但已登记占用，不能当空闲段", NotificationIdRegistry.isNotificationIdRangeFree(9_500..9_501))
        assertFalse("部分相交也不算空闲", NotificationIdRegistry.isNotificationIdRangeFree(9_348..9_351))
    }

    @Test
    fun `ownerOf resolves known ids and returns null for unknown`() {
        assertEquals("approval_pending", NotificationIdRegistry.ownerOfNotificationId(9_400))
        assertEquals("execution_service", NotificationIdRegistry.ownerOfNotificationId(1_107))
        assertEquals("approval_attention", NotificationIdRegistry.ownerOfNotificationId(9_700))
        assertNull(NotificationIdRegistry.ownerOfNotificationId(8_000))
    }

    @Test
    fun `every channel id is unique`() {
        val ids = NotificationIdRegistry.NotificationChannel.entries.map { it.id }
        assertEquals("channel id 不能重复", ids.toSet().size, ids.size)
    }

    @Test
    fun `registry covers every id NotificationPolicy actually reserves`() {
        // 防漂移的硬断言：直接对齐 policy 真实分配的段，而不是自证登记表自己写的几个点。
        // 若 NotificationPolicy 改了段值（如 APPROVAL_PENDING_ID 9400→9450）而表没跟着改，
        // 这条会立刻红灯——配得上 SOP 「撞段/漂移会直接红灯」的说法。
        NotificationPolicy.reservedSegments().forEach { seg ->
            seg.forEach { id ->
                org.junit.Assert.assertNotNull(
                    "policy 段 $seg 的 $id 未在 NotificationIdRegistry 登记",
                    NotificationIdRegistry.ownerOfNotificationId(id),
                )
            }
        }
    }

    @Test
    fun `every segment owner is unique within each space`() {
        // 文件头声明「owner 必须唯一」——用断言钉住，冲突时才能一眼看出是谁和谁撞了。
        assertEquals(
            NotificationIdRegistry.allIdSegments.size,
            NotificationIdRegistry.allIdSegments.map { it.owner }.toSet().size,
        )
        assertEquals(
            NotificationIdRegistry.allRequestCodeSegments.size,
            NotificationIdRegistry.allRequestCodeSegments.map { it.owner }.toSet().size,
        )
    }

    @Test
    fun `notify user reply request codes stay in their own segment`() {
        // 通知 ID 段 9300..9349 经 notifyReplyRequestCode 映射到 6700..6749，
        // 与审批按钮 6400..6699、唤醒 6200..6299 全不相交（防漂移）。
        val codes = (9_300..9_349).map { NotificationPolicy.notifyReplyRequestCode(it) }
        assertTrue(codes.all { it in 6_700..6_749 })
        assertTrue(codes.toSet().size == 50)
        assertTrue(codes.none { it in 6_400..6_699 })
        assertTrue(codes.none { it in 6_200..6_299 })
    }

    @Test
    fun `known single request codes are registered`() {
        // M1 回归：这几个单值 rc 以前散落在各模块、没进表，容易与新增段撞车。
        val registered = NotificationIdRegistry.allRequestCodeSegments.flatMap { it.range.toList() }.toSet()
        assertTrue(1_108 in registered)        // 运行时结果浮窗 open
        assertTrue(4_543_553 in registered)    // 语音交接 0x455441
        assertTrue(0 in registered)            // 执行/朗读/宏录制共用的裸 0
    }
}
