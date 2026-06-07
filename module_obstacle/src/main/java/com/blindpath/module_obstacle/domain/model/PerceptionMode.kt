package com.blindpath.module_obstacle.domain.model

enum class PerceptionMode(
    val chineseName: String,
    val description: String,
    val modelFileName: String,
    val confidenceThreshold: Float,
    val nmsThreshold: Float,
    val voiceCooldownMs: Long
) {
    INDOOR("室内感知", "微观环境防碰撞与找东西", "yolo_indoor.tflite", 0.45f, 0.50f, 2500L),
    NAVIGATION("出行导航", "宏观路径方向感与交通规则", "yolo_traffic.tflite", 0.40f, 0.45f, 2000L),
    SCENE("场景识别", "环境语义认知与地标识别", "yolo_scene.tflite", 0.40f, 0.45f, 4000L),
    AUTO("自动切换", "基于传感器自动选择模式", "yolo_traffic.tflite", 0.40f, 0.45f, 2000L);

    fun getModeSwitchAnnouncement(): String = when (this) {
        INDOOR -> "已切换到室内感知模式"
        NAVIGATION -> "已切换到出行导航模式"
        SCENE -> "已切换到场景识别模式"
        AUTO -> "已切换到自动模式"
    }
}
