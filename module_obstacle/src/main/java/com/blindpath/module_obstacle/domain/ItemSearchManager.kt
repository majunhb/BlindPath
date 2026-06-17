package com.blindpath.module_obstacle.domain

import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 物品查找管理器 - 管理定向物品搜索的状态机
 *
 * 状态流程：
 * IDLE -> SEARCHING -> FOUND / TIMEOUT -> CONFIRM_CONTINUE -> SEARCHING / IDLE
 *
 * 超时机制：
 * - 30秒未找到：语音询问"是否继续搜索"
 * - 60秒总超时：自动停止搜索
 */
@Singleton
class ItemSearchManager @Inject constructor() {

    enum class SearchState {
        IDLE,           // 空闲
        ASKING_TARGET,  // 等待用户说出目标物品
        SEARCHING,      // 正在搜索中
        FOUND,          // 已找到目标
        TIMEOUT,        // 搜索超时
        CONFIRMING      // 确认是否继续
    }

    data class ItemSearchState(
        val searchState: SearchState = SearchState.IDLE,
        val targetItem: ObstacleType? = null,
        val targetName: String? = null,
        val foundObstacle: DetectedObstacle? = null,
        val searchStartTime: Long = 0L,
        val searchDuration: Long = 0L,
        val message: String? = null
    )

    private val _state = MutableStateFlow(ItemSearchState())
    val state: StateFlow<ItemSearchState> = _state.asStateFlow()

    companion object {
        const val ASK_TIMEOUT_MS = 15_000L      // 等待用户说出物品名称的超时
        const val SEARCH_TIMEOUT_MS = 60_000L    // 搜索总超时
        const val CONFIRM_TIMEOUT_MS = 30_000L   // 30秒未找到时询问是否继续
        const val FIRST_CHECK_MS = 30_000L       // 首次检查点（30秒）
    }

    // 用户口语 -> ObstacleType 映射表
    private val spokenNameToType = mapOf(
        // 家具
        "椅子" to ObstacleType.CHAIR, "沙发" to ObstacleType.SOFA,
        "桌子" to ObstacleType.TABLE, "餐桌" to ObstacleType.TABLE,
        "床" to ObstacleType.BED, "柜子" to ObstacleType.OBSTACLE,
        "盆栽" to ObstacleType.POTTED_PLANT, "花" to ObstacleType.POTTED_PLANT,
        // 电子设备
        "手机" to ObstacleType.PHONE, "电话" to ObstacleType.PHONE,
        "电脑" to ObstacleType.LAPTOP, "笔记本" to ObstacleType.LAPTOP,
        "电视" to ObstacleType.TV, "遥控器" to ObstacleType.REMOTE,
        "键盘" to ObstacleType.KEYBOARD, "鼠标" to ObstacleType.MOUSE_DEVICE,
        // 厨房用品
        "杯子" to ObstacleType.CUP, "碗" to ObstacleType.BOWL,
        "瓶子" to ObstacleType.BOTTLE, "水杯" to ObstacleType.CUP,
        "冰箱" to ObstacleType.REFRIGERATOR, "微波炉" to ObstacleType.MICROWAVE,
        "烤箱" to ObstacleType.OVEN,
        // 个人物品
        "背包" to ObstacleType.BACKPACK, "包" to ObstacleType.HANDBAG,
        "手提包" to ObstacleType.HANDBAG, "行李箱" to ObstacleType.SUITCASE,
        "雨伞" to ObstacleType.UMBRELLA, "钥匙" to ObstacleType.OBSTACLE,
        // 书房
        "书" to ObstacleType.BOOK, "时钟" to ObstacleType.CLOCK,
        "花瓶" to ObstacleType.VASE, "剪刀" to ObstacleType.SCISSORS,
        // 卫浴
        "马桶" to ObstacleType.SINK, "水槽" to ObstacleType.SINK,
        // 宠物
        "猫" to ObstacleType.CAT, "狗" to ObstacleType.DOG,
        "宠物" to ObstacleType.PET,
        // 门
        "门" to ObstacleType.DOOR, "窗户" to ObstacleType.WINDOW,
        // 通用
        "楼梯" to ObstacleType.STAIRS, "台阶" to ObstacleType.STEP_UP,
        "墙壁" to ObstacleType.WALL, "柱子" to ObstacleType.PILLAR
    )

    /**
     * 开始物品查找流程
     */
    fun startSearch() {
        _state.value = ItemSearchState(
            searchState = SearchState.ASKING_TARGET,
            message = "请问您要找什么物品？"
        )
        Timber.d("ItemSearch: 进入等待目标物品状态")
    }

    /**
     * 设置目标物品（用户说出物品名称后调用）
     */
    fun setTarget(spokenName: String): Boolean {
        val targetType = spokenNameToType.entries.firstOrNull { (key, _) ->
            spokenName.contains(key)
        }?.value

        if (targetType != null) {
            _state.value = ItemSearchState(
                searchState = SearchState.SEARCHING,
                targetItem = targetType,
                targetName = spokenName,
                searchStartTime = System.currentTimeMillis(),
                message = "正在为您查找${spokenName}，请缓慢转动手机扫描周围环境"
            )
            Timber.d("ItemSearch: 开始搜索 $spokenName -> $targetType")
            return true
        } else {
            _state.value = _state.value.copy(
                message = "抱歉，暂不支持查找${spokenName}，请尝试其他物品，如手机、钥匙、杯子等"
            )
            return false
        }
    }

    /**
     * 处理检测结果，检查是否找到目标物品
     * @return true 如果找到目标
     */
    fun checkDetectionResult(obstacles: List<DetectedObstacle>): Boolean {
        val currentState = _state.value
        if (currentState.searchState != SearchState.SEARCHING) return false

        val target = currentState.targetItem ?: return false
        val found = obstacles.firstOrNull { it.type == target }

        if (found != null) {
            _state.value = currentState.copy(
                searchState = SearchState.FOUND,
                foundObstacle = found,
                searchDuration = System.currentTimeMillis() - currentState.searchStartTime,
                message = found.type.getAlertMessage(found.distance, found.direction)
            )
            Timber.d("ItemSearch: 找到目标 ${target.chineseName}，距离 ${found.distance}米")
            return true
        }

        // 检查超时
        val elapsed = System.currentTimeMillis() - currentState.searchStartTime
        if (elapsed >= CONFIRM_TIMEOUT_MS && elapsed < SEARCH_TIMEOUT_MS) {
            _state.value = currentState.copy(
                searchState = SearchState.CONFIRMING,
                message = "已经搜索${elapsed / 1000}秒未找到${currentState.targetName}，是否继续搜索？"
            )
        } else if (elapsed >= SEARCH_TIMEOUT_MS) {
            _state.value = currentState.copy(
                searchState = SearchState.TIMEOUT,
                searchDuration = elapsed,
                message = "搜索超时，未找到${currentState.targetName}。您可以换个角度再试一次"
            )
        }

        return false
    }

    /**
     * 继续搜索（超时确认后）
     */
    fun continueSearch() {
        val currentState = _state.value
        if (currentState.searchState == SearchState.CONFIRMING) {
            _state.value = currentState.copy(
                searchState = SearchState.SEARCHING,
                searchStartTime = System.currentTimeMillis(),
                message = "继续为您查找${currentState.targetName}"
            )
        }
    }

    /**
     * 停止搜索
     */
    fun stopSearch() {
        val currentState = _state.value
        _state.value = ItemSearchState(
            message = if (currentState.searchState == SearchState.SEARCHING ||
                         currentState.searchState == SearchState.CONFIRMING)
                "已停止查找${currentState.targetName}" else null
        )
        Timber.d("ItemSearch: 搜索已停止")
    }

    /**
     * 获取当前搜索状态提示消息
     */
    fun getTimedMessage(): String? {
        val currentState = _state.value
        if (currentState.searchState != SearchState.SEARCHING) return null

        val elapsed = System.currentTimeMillis() - currentState.searchStartTime
        return when {
            elapsed >= 45_000L -> "搜索时间较长，建议换个角度或位置再试"
            elapsed >= CONFIRM_TIMEOUT_MS -> null  // 由 checkDetectionResult 处理
            elapsed >= 15_000L && elapsed < 20_000L -> "正在继续搜索${currentState.targetName}，请耐心等待"
            else -> null
        }
    }
}
