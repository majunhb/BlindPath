package com.blindpath.module_obstacle.domain.model

import com.blindpath.base.common.AlertLevel
import org.junit.Test
import org.junit.Assert.*

/**
 * ObstacleType 枚举测试
 * 
 * 测试障碍物类型的属性和预警消息生成逻辑
 */
class ObstacleTypeTest {

    // ============ 基本属性测试 ============

    @Test
    fun `all obstacle types should have chinese name`() {
        ObstacleType.values().forEach { type ->
            assertTrue(
                "Type $type should have non-empty chinese name",
                type.chineseName.isNotEmpty()
            )
        }
    }

    @Test
    fun `all obstacle types should have valid severity`() {
        ObstacleType.values().forEach { type ->
            assertTrue(
                "Type $type severity should be 1-3",
                type.severity in 1..3
            )
        }
    }

    @Test
    fun `all obstacle types should have valid voice priority`() {
        ObstacleType.values().forEach { type ->
            assertTrue(
                "Type $type voice priority should be positive",
                type.voicePriority > 0
            )
        }
    }

    // ============ 严重性分类测试 ============

    @Test
    fun `high severity obstacles should include dangerous objects`() {
        val highSeverityTypes = ObstacleType.values().filter { it.severity == 3 }

        assertTrue("VEHICLE should be high severity", highSeverityTypes.contains(ObstacleType.VEHICLE))
        assertTrue("PIT should be high severity", highSeverityTypes.contains(ObstacleType.PIT))
        assertTrue("STEP_DOWN should be high severity", highSeverityTypes.contains(ObstacleType.STEP_DOWN))
    }

    @Test
    fun `low severity obstacles should include safe objects`() {
        val lowSeverityTypes = ObstacleType.values().filter { it.severity == 1 }

        assertTrue("ZEBRA_CROSSING should be low severity", lowSeverityTypes.contains(ObstacleType.ZEBRA_CROSSING))
        assertTrue("TRAFFIC_SIGN should be low severity", lowSeverityTypes.contains(ObstacleType.TRAFFIC_SIGN))
    }

    // ============ getAlertMessage() 测试 ============

    @Test
    fun `PERSON alert message should include distance`() {
        val message = ObstacleType.PERSON.getAlertMessage(2.5f)

        assertTrue("Message should mention person", message.contains("人") || message.contains("行人"))
        assertTrue("Message should include distance", message.contains("2") || message.contains("米"))
    }

    @Test
    fun `VEHICLE alert message should warn about danger`() {
        val message = ObstacleType.VEHICLE.getAlertMessage(1.0f)

        assertTrue("Message should mention vehicle", message.contains("车"))
    }

    @Test
    fun `STEP_UP alert message should remind to lift foot`() {
        val message = ObstacleType.STEP_UP.getAlertMessage(0.5f)

        assertTrue("Message should mention step", message.contains("台阶"))
    }

    @Test
    fun `STEP_DOWN alert message should warn about drop`() {
        val message = ObstacleType.STEP_DOWN.getAlertMessage(0.5f)

        assertTrue("Message should mention step", message.contains("台阶") || message.contains("下"))
    }

    @Test
    fun `TRAFFIC_LIGHT alert message should include traffic light`() {
        val message = ObstacleType.TRAFFIC_LIGHT.getAlertMessage(5.0f)

        assertTrue("Message should mention traffic light", message.contains("红绿灯"))
    }

    // ============ 方向测试 ============

    @Test
    fun `alert message with LEFT direction should include left side info`() {
        val message = ObstacleType.PERSON.getAlertMessage(2.0f, Direction.LEFT)

        assertTrue("Message should mention left side", message.contains("左"))
    }

    @Test
    fun `alert message with RIGHT direction should include right side info`() {
        val message = ObstacleType.PERSON.getAlertMessage(2.0f, Direction.RIGHT)

        assertTrue("Message should mention right side", message.contains("右"))
    }

    @Test
    fun `alert message with FRONT direction should not include direction prefix`() {
        val message = ObstacleType.PERSON.getAlertMessage(2.0f, Direction.CENTER)

        // 前方是默认方向，不需要特别标注
        assertFalse("Message should not mention front explicitly", message.contains("前"))
    }

    // ============ 距离格式化测试 ============

    @Test
    fun `alert message should format distance as integer`() {
        val message = ObstacleType.PERSON.getAlertMessage(2.7f)

        // 距离应该显示为整数（2米而不是2.7米）
        assertTrue("Distance should be formatted as integer", 
            message.contains("2米") || message.contains("2 米"))
    }

    @Test
    fun `close distance alert should be more urgent`() {
        val closeMessage = ObstacleType.VEHICLE.getAlertMessage(0.5f)
        val farMessage = ObstacleType.VEHICLE.getAlertMessage(5.0f)

        // 近距离消息应该更紧迫
        assertTrue("Close distance should have shorter message or more urgent tone",
            closeMessage.isNotEmpty())
    }

    // ============ 未知障碍物测试 ============

    @Test
    fun `UNKNOWN obstacle should have generic message`() {
        val message = ObstacleType.UNKNOWN.getAlertMessage(1.0f)

        assertTrue("Unknown should have generic message", message.isNotEmpty())
    }

    @Test
    fun `OBSTACLE generic type should have valid message`() {
        val message = ObstacleType.OBSTACLE.getAlertMessage(1.0f)

        assertTrue("Generic obstacle should have message", message.contains("障碍物"))
    }
}
