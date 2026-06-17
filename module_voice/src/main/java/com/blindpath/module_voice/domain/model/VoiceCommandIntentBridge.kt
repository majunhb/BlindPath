package com.blindpath.module_voice.domain.model

/**
 * VoiceCommand ↔ VoiceIntent 双向映射桥
 *
 * 旧架构（VoiceCommand 关键词匹配）和新架构（VoiceIntent NLU语义理解）并存期间的兼容层。
 * 新 Pipeline v2.0 优先走 NluEngine → VoiceIntent → IntentRouter；
 * 旧 VoiceCommand 仍用于 VoiceInteractionManager 的 commandHandler 回调。
 *
 * 迁移完成后可删除此文件。
 */
object VoiceCommandIntentBridge {

    /**
     * 旧 VoiceCommand → 新 VoiceIntent 映射
     *
     * 用于旧 commandHandler 收到 VoiceCommand 后需要走新 IntentRouter 的场景
     */
    fun toIntent(command: VoiceCommand): VoiceIntent = when (command) {
        // 导航类
        VoiceCommand.START_NAVIGATION -> VoiceIntent.NAVIGATE_TO
        VoiceCommand.STOP_NAVIGATION -> VoiceIntent.STOP_NAVIGATION
        VoiceCommand.WHERE_AM_I -> VoiceIntent.QUERY_LOCATION

        // 检测类
        VoiceCommand.START_OBSTACLE_DETECTION -> VoiceIntent.START_DETECTION
        VoiceCommand.STOP_OBSTACLE_DETECTION -> VoiceIntent.STOP_DETECTION

        // SOS
        VoiceCommand.SOS -> VoiceIntent.SOS
        VoiceCommand.CALL_SOS -> VoiceIntent.SOS

        // 模式切换
        VoiceCommand.START_AR_NAVIGATION -> VoiceIntent.SWITCH_AR_MODE

        // 室内
        VoiceCommand.START_INDOOR_PERCEPTION -> VoiceIntent.INDOOR_NAVIGATE
        VoiceCommand.STOP_INDOOR_PERCEPTION -> VoiceIntent.STOP_NAVIGATION

        // 场景
        VoiceCommand.START_SCENE_PERCEPTION -> VoiceIntent.LOOK_AHEAD
        VoiceCommand.STOP_SCENE_PERCEPTION -> VoiceIntent.STOP_DETECTION

        // 声呐 → 检测
        VoiceCommand.START_SONAR_DETECTION -> VoiceIntent.START_DETECTION
        VoiceCommand.STOP_SONAR_DETECTION -> VoiceIntent.STOP_DETECTION

        // 地图/设置
        VoiceCommand.SHOW_MAP -> VoiceIntent.QUERY_LOCATION
        VoiceCommand.HIDE_MAP -> VoiceIntent.STOP_NAVIGATION
        VoiceCommand.OPEN_SETTINGS -> VoiceIntent.HELP
        VoiceCommand.CLOSE_SETTINGS -> VoiceIntent.HELP

        // 通用
        VoiceCommand.HELP -> VoiceIntent.HELP
        VoiceCommand.REPEAT -> VoiceIntent.REPEAT
        VoiceCommand.CANCEL -> VoiceIntent.STOP_NAVIGATION
        VoiceCommand.BACK -> VoiceIntent.STOP_NAVIGATION

        // 物品查找
        VoiceCommand.FIND_ITEM -> VoiceIntent.LOOK_AHEAD
        VoiceCommand.STOP_FINDING -> VoiceIntent.STOP_DETECTION

        // 公交
        VoiceCommand.FIND_BUS_STOP -> VoiceIntent.QUERY_LOCATION
        VoiceCommand.TAKE_BUS -> VoiceIntent.NAVIGATE_TO
        VoiceCommand.NEXT_STOP -> VoiceIntent.QUERY_DISTANCE

        // 场景询问
        VoiceCommand.WHAT_PLACE -> VoiceIntent.QUERY_LOCATION

        // 出行导航
        VoiceCommand.START_OUTDOOR_NAVIGATION -> VoiceIntent.NAVIGATE_TO
        VoiceCommand.STOP_OUTDOOR_NAVIGATION -> VoiceIntent.STOP_NAVIGATION
    }

    /**
     * 新 VoiceIntent → 旧 VoiceCommand 反向映射
     *
     * 用于 IntentRouter 执行后需要触发旧 VoiceCommand handler 的场景
     */
    fun toCommand(intent: VoiceIntent): VoiceCommand? = when (intent) {
        VoiceIntent.NAVIGATE_TO -> VoiceCommand.START_OUTDOOR_NAVIGATION
        VoiceIntent.NAVIGATE_HOME -> VoiceCommand.START_NAVIGATION
        VoiceIntent.QUERY_DISTANCE -> VoiceCommand.NEXT_STOP
        VoiceIntent.QUERY_LOCATION -> VoiceCommand.WHERE_AM_I
        VoiceIntent.REPEAT -> VoiceCommand.REPEAT
        VoiceIntent.SWITCH_VOICE_MODE -> VoiceCommand.START_NAVIGATION
        VoiceIntent.SWITCH_AR_MODE -> VoiceCommand.START_AR_NAVIGATION
        VoiceIntent.VOLUME_UP -> VoiceCommand.HELP
        VoiceIntent.VOLUME_DOWN -> VoiceCommand.HELP
        VoiceIntent.SPEED_UP -> VoiceCommand.HELP
        VoiceIntent.SPEED_DOWN -> VoiceCommand.HELP
        VoiceIntent.SOS -> VoiceCommand.SOS
        VoiceIntent.STOP_NAVIGATION -> VoiceCommand.STOP_NAVIGATION
        VoiceIntent.LOOK_AHEAD -> VoiceCommand.START_SCENE_PERCEPTION
        VoiceIntent.START_DETECTION -> VoiceCommand.START_OBSTACLE_DETECTION
        VoiceIntent.STOP_DETECTION -> VoiceCommand.STOP_OBSTACLE_DETECTION
        VoiceIntent.INDOOR_NAVIGATE -> VoiceCommand.START_INDOOR_PERCEPTION
        VoiceIntent.QUERY_FLOOR -> VoiceCommand.WHERE_AM_I
        VoiceIntent.HELP -> VoiceCommand.HELP
        VoiceIntent.UNKNOWN -> null
    }
}
