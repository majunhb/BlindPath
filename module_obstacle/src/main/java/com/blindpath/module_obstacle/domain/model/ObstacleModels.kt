package com.blindpath.module_obstacle.domain.model

import com.blindpath.base.common.AlertLevel

/**
 * 障碍物类型枚举
 * 包含视障用户日常生活中常见的各类障碍物和道路元素
 */
enum class ObstacleType(
    val chineseName: String,
    val severity: Int, // 1=低, 2=中, 3=高
    val voicePriority: Int // 播报优先级，数字越小优先级越高
) {
    // ============ 地面障碍物 ============
    STEP_UP("上台阶", 2, 1),
    STEP_DOWN("下台阶", 3, 1),
    STAIRS("楼梯", 3, 1),
    CURB("路沿", 2, 3),
    PUDDLE("水坑", 2, 4),
    MANHOLE("井盖", 2, 5),
    PIT("坑洼", 3, 2),
    ZEBRA_CROSSING("斑马线", 1, 10), // 低危险但需提示

    // ============ 交通工具 ============
    VEHICLE("车辆", 3, 1),
    BUS("公交车", 3, 1),
    TRUCK("卡车", 3, 1),
    BICYCLE("自行车", 2, 4),
    MOTORCYCLE("摩托车", 2, 3),

    // ============ 道路用户 ============
    PERSON("行人", 2, 5),
    PET("宠物", 2, 4),             // 猫、狗等宠物
    ANIMAL("动物", 1, 7),           // 其他动物

    // ============ 街道设施 ============
    PILLAR("石墩/柱子", 3, 2),
    ELECTRIC_POLE("电线杆", 2, 4),
    TRAFFIC_LIGHT("红绿灯", 2, 3),
    TRAFFIC_SIGN("交通标志", 1, 7),
    BENCH("长椅", 1, 7),
    HANDRAIL("扶手", 1, 6),
    PARKING_METER("停车收费桩", 2, 6),  // 路边柱状设施

    // ============ 路面障碍物 ============
    ROAD_HAZARD("路面障碍", 2, 3),    // 滑板、施工锥等

    // ============ 家居物品（可能阻挡路径） ============
    CHAIR("椅子", 2, 6),
    SOFA("沙发", 1, 7),
    TABLE("桌子", 2, 6),
    BED("床", 1, 7),
    POTTED_PLANT("盆栽", 1, 7),

    // ============ 个人物品 ============
    BACKPACK("背包", 1, 8),
    HANDBAG("手提包", 1, 9),
    UMBRELLA("雨伞", 1, 9),
    SUITCASE("行李箱", 2, 6),
    BOTTLE("瓶子", 1, 9),

    // ============ 电子设备 ============
    LAPTOP("笔记本电脑", 1, 8),
    PHONE("手机", 1, 9),

    // ============ 交通工具（扩展）============
    AIRPLANE("飞机", 2, 7),
    TRAIN("火车", 3, 1),
    BOAT("船只", 2, 6),

    // ============ 动物类（COCO 80类） ============
    CAT("猫", 1, 6),
    DOG("狗", 2, 5),
    BIRD("鸟类", 1, 8),
    HORSE("马", 2, 5),
    SHEEP("羊", 1, 7),
    COW("牛", 2, 6),
    ELEPHANT("大象", 3, 2),
    BEAR("熊", 3, 1),
    ZEBRA("斑马", 2, 5),
    GIRAFFE("长颈鹿", 2, 5),

    // ============ 运动/娱乐设施 ============
    KITE("风筝", 1, 10),
    SKATEBOARD("滑板", 2, 6),
    SURFBOARD("冲浪板", 1, 9),
    SPORTS_BALL("运动球", 1, 9),
    TENNIS_RACKET("球拍", 1, 10),
    FRISBEE("飞盘", 1, 10),
    SKIS("滑雪板", 1, 9),
    SNOWBOARD("单板滑雪", 1, 9),

    // ============ 餐饮/厨房用品 ============
    WINE_GLASS("酒杯", 1, 9),
    CUP("杯子", 1, 9),
    FORK("叉子", 1, 10),
    KNIFE("刀具", 2, 7),
    SPOON("勺子", 1, 10),
    BOWL("碗", 1, 9),
    BANANA("香蕉", 1, 10),
    APPLE("苹果", 1, 10),
    FOOD("食物", 1, 10),

    // ============ 室内物品（扩展）============
    BOOK("书本", 1, 9),
    CLOCK("时钟", 1, 10),
    VASE("花瓶", 1, 9),
    SCISSORS("剪刀", 2, 8),
    TEDDY_BEAR("玩具熊", 1, 10),
    TOOTHBRUSH("牙刷", 1, 10),
    HAIR_DRYER("吹风机", 1, 9),
    TV("电视机", 1, 9),
    KEYBOARD("键盘", 1, 10),
    MOUSE_DEVICE("鼠标", 1, 10),
    REMOTE("遥控器", 1, 10),
    MICROWAVE("微波炉", 1, 9),
    OVEN("烤箱", 1, 9),
    TOASTER("烤面包机", 1, 9),
    SINK("水槽", 1, 9),
    REFRIGERATOR("冰箱", 1, 9),

    // ============ 建筑结构（对视障人员至关重要） ============
    WALL("墙壁", 3, 1),
    GLASS_WALL("玻璃墙", 3, 1),
    DOOR("门", 2, 3),
    WINDOW("窗户", 2, 5),
    RAILING("栏杆", 2, 4),
    FENCE("围栏", 2, 4),

    // ============ 通用障碍物 ============
    OBSTACLE("障碍物", 3, 2),
    UNKNOWN("未知物体", 1, 9);

    /**
     * 生成预警语音消息
     * 格式：方位 + 距离 + 物体名称 + 动作建议
     */
    fun getAlertMessage(distance: Float, direction: Direction? = null): String {
        val distanceInt = distance.toInt()
        val directionPrefix = when (direction) {
            Direction.LEFT, Direction.LEFT_FRONT -> "左侧"
            Direction.RIGHT, Direction.RIGHT_FRONT -> "右侧"
            Direction.CENTER -> ""
            Direction.BACK -> "后方"
            null -> ""
        }

        return when (this) {
            // 台阶类 - 需要特别提醒抬脚或注意落差
            STEP_UP -> if (distance < 1.5f) "前方${distanceInt}米有台阶，请抬脚" else "注意，前方${distanceInt}米有上台阶"
            STEP_DOWN -> if (distance < 1.5f) "前方${distanceInt}米有台阶，注意落差" else "注意，前方${distanceInt}米有下台阶"
            STAIRS -> if (distance < 2f) "前方${distanceInt}米有楼梯，请注意" else "注意，前方${distanceInt}米有楼梯"

            // 地面障碍物
            CURB -> when {
                distance < 0.5f -> "小心，路沿就在脚下，注意抬脚"
                distance < 1f -> "${directionPrefix}路沿，注意脚下，小心绊倒"
                distance < 2f -> "${directionPrefix}${distanceInt}米有路沿，请提前注意"
                else -> "注意，${directionPrefix}${distanceInt}米有路沿"
            }
            PUDDLE -> if (distance < 1.5f) "${directionPrefix}${distanceInt}米有水坑，请绕行" else "注意，${directionPrefix}${distanceInt}米有水坑"
            MANHOLE -> if (distance < 1.5f) "${directionPrefix}${distanceInt}米有井盖" else "注意，${directionPrefix}${distanceInt}米有井盖"
            PIT -> if (distance < 1f) "危险，${directionPrefix}有坑洼，请绕行" else "注意，${directionPrefix}${distanceInt}米有坑洼"
            ZEBRA_CROSSING -> "斑马线"

            // 悬空/侧面障碍物
            PILLAR -> "${directionPrefix}${distanceInt}米有石墩，请绕行"
            ELECTRIC_POLE -> "${directionPrefix}${distanceInt}米有电线杆"
            TRAFFIC_LIGHT -> when {
                distance < 3f -> "红绿灯，前方${distanceInt}米"
                else -> "注意，前方有红绿灯"
            }
            TRAFFIC_SIGN -> "注意，前方${distanceInt}米有交通标志"
            BENCH -> "${directionPrefix}${distanceInt}米有长椅"
            HANDRAIL -> "${directionPrefix}${distanceInt}米有扶手"

            // 交通工具
            VEHICLE -> if (distance < 2f) "注意，前方${distanceInt}米有车辆" else "远处有车辆"
            BUS -> if (distance < 3f) "注意，前方${distanceInt}米有公交车" else "远处有公交车"
            TRUCK -> if (distance < 3f) "注意，前方${distanceInt}米有卡车" else "远处有卡车"
            BICYCLE -> "注意，前方${distanceInt}米有自行车"
            MOTORCYCLE -> "注意，前方${distanceInt}米有摩托车"

            // 移动物体 - 需要特别提醒
            PERSON -> if (distance < 1.5f) "前方${distanceInt}米有行人" else "注意，前方有行人"

            // 宠物和动物
            PET -> when {
                distance < 1f -> "注意脚下，有宠物"
                distance < 2f -> "前方${distanceInt}米有宠物，请小心"
                else -> "注意，前方有宠物"
            }
            ANIMAL -> "前方${distanceInt}米有动物，请注意"

            // 停车桩
            PARKING_METER -> "${directionPrefix}${distanceInt}米有停车桩，注意脚下"

            // 路面障碍
            ROAD_HAZARD -> when {
                distance < 1f -> "注意脚下有障碍物"
                distance < 2f -> "前方${distanceInt}米有路面障碍，请绕行"
                else -> "前方${distanceInt}米有障碍"
            }

            // 家居物品
            CHAIR -> "${directionPrefix}${distanceInt}米有椅子"
            SOFA -> "${directionPrefix}${distanceInt}米有沙发"
            TABLE -> "${directionPrefix}${distanceInt}米有桌子"
            BED -> "${directionPrefix}${distanceInt}米有床"
            POTTED_PLANT -> "${directionPrefix}${distanceInt}米有盆栽"

            // 个人物品
            BACKPACK -> "注意，前方${distanceInt}米有背包"
            HANDBAG -> "注意，前方${distanceInt}米有手提包"
            UMBRELLA -> "注意，前方${distanceInt}米有雨伞"
            SUITCASE -> "注意，前方${distanceInt}米有行李箱"
            BOTTLE -> "注意，前方${distanceInt}米有瓶子"

            // 电子设备
            LAPTOP -> "注意，前方${distanceInt}米有笔记本电脑"
            PHONE -> "注意，前方${distanceInt}米有手机"

            // 交通工具（扩展）
            AIRPLANE -> "注意，前方有飞机"
            TRAIN -> if (distance < 5f) "危险，前方${distanceInt}米有火车" else "远处有火车"
            BOAT -> "注意，前方${distanceInt}米有船只"

            // 动物类
            CAT -> if (distance < 1f) "注意脚下，有猫" else "前方${distanceInt}米有猫"
            DOG -> if (distance < 1.5f) "注意，前方${distanceInt}米有狗" else "前方有狗"
            BIRD -> "前方${distanceInt}米有鸟"
            HORSE -> "注意，前方${distanceInt}米有马"
            SHEEP -> "前方${distanceInt}米有羊"
            COW -> "注意，前方${distanceInt}米有牛"
            ELEPHANT -> if (distance < 5f) "危险，前方${distanceInt}米有大象" else "远处有大象"
            BEAR -> if (distance < 5f) "危险，前方${distanceInt}米有熊" else "远处有熊"
            ZEBRA -> "注意，前方${distanceInt}米有斑马"
            GIRAFFE -> "注意，前方${distanceInt}米有长颈鹿"

            // 运动/娱乐
            KITE -> "上方有风筝"
            SKATEBOARD -> "注意，前方${distanceInt}米有滑板"
            SURFBOARD -> "前方${distanceInt}米有冲浪板"
            SPORTS_BALL -> "注意脚下，前方有球"
            TENNIS_RACKET -> "前方${distanceInt}米有球拍"
            FRISBEE -> "前方${distanceInt}米有飞盘"
            SKIS -> "前方${distanceInt}米有滑雪板"
            SNOWBOARD -> "前方${distanceInt}米有单板滑雪"

            // 餐饮/厨房
            WINE_GLASS -> "注意，前方${distanceInt}米有酒杯"
            CUP -> "前方${distanceInt}米有杯子"
            FORK -> "前方${distanceInt}米有叉子"
            KNIFE -> "注意，前方${distanceInt}米有刀具"
            SPOON -> "前方${distanceInt}米有勺子"
            BOWL -> "前方${distanceInt}米有碗"
            BANANA, APPLE, FOOD -> "前方${distanceInt}米有食物"

            // 室内物品
            BOOK -> "前方${distanceInt}米有书本"
            CLOCK -> "前方${distanceInt}米有时钟"
            VASE -> "注意，前方${distanceInt}米有花瓶"
            SCISSORS -> "注意，前方${distanceInt}米有剪刀"
            TEDDY_BEAR -> "前方${distanceInt}米有玩具熊"
            TOOTHBRUSH -> "前方${distanceInt}米有牙刷"
            HAIR_DRYER -> "前方${distanceInt}米有吹风机"
            TV -> "前方${distanceInt}米有电视"
            KEYBOARD -> "前方${distanceInt}米有键盘"
            MOUSE_DEVICE -> "前方${distanceInt}米有鼠标"
            REMOTE -> "前方${distanceInt}米有遥控器"
            MICROWAVE -> "前方${distanceInt}米有微波炉"
            OVEN -> "前方${distanceInt}米有烤箱"
            TOASTER -> "前方${distanceInt}米有烤面包机"
            SINK -> "前方${distanceInt}米有水槽"
            REFRIGERATOR -> "注意，前方${distanceInt}米有冰箱"

            // 建筑结构
            WALL -> when {
                distance < 0.5f -> "危险，前方有墙壁，请立即停下"
                distance < 1f -> "注意，前方${distanceInt}米有墙壁"
                else -> "前方${distanceInt}米有墙壁，请绕行"
            }
            GLASS_WALL -> when {
                distance < 1f -> "危险，前方有玻璃墙，请小心"
                else -> "前方${distanceInt}米有玻璃墙，请注意"
            }
            DOOR -> "前方${distanceInt}米有门"
            WINDOW -> "前方${distanceInt}米有窗户"
            RAILING -> "前方${distanceInt}米有栏杆"
            FENCE -> "前方${distanceInt}米有围栏"

            // 通用
            OBSTACLE -> "注意，前方${distanceInt}米有障碍物"
            UNKNOWN -> "注意，前方${distanceInt}米有物体"
        }
    }

    /**
     * 生成去重键（用于语音播报去重）
     * 相同类型和方向的障碍物使用相同的去重键
     */
    fun getDeduplicationKey(direction: Direction? = null): String {
        val directionKey = direction?.name ?: "NONE"
        return "${name}_${directionKey}"
    }

    /**
     * 获取危险级别描述
     */
    fun getSeverityDescription(): String {
        return when (severity) {
            3 -> "高危"
            2 -> "中危"
            else -> "低危"
        }
    }
}

/**
 * 障碍物方向
 */
enum class Direction {
    LEFT,       // 左侧
    LEFT_FRONT, // 左前方
    CENTER,     // 正前方
    RIGHT_FRONT,// 右前方
    RIGHT,      // 右侧
    BACK;       // 后方

    /**
     * 获取方位的中文描述
     */
    fun getChineseName(): String {
        return when (this) {
            LEFT -> "左侧"
            LEFT_FRONT -> "左前方"
            CENTER -> "正前方"
            RIGHT_FRONT -> "右前方"
            RIGHT -> "右侧"
            BACK -> "后方"
        }
    }
}

/**
 * 检测到的障碍物
 */
data class DetectedObstacle(
    val type: ObstacleType,
    val confidence: Float,        // 置信度 0-1
    val distance: Float,         // 距离（米）
    val direction: Direction,    // 方向
    val boundingBox: BoundingBox, // 包围框（用于调试显示）
    val timestamp: Long = System.currentTimeMillis(),
    val sceneContext: String? = null // 场景上下文
)

/**
 * 包围框
 */
data class BoundingBox(
    val left: Float,   // 0-1 相对坐标
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 预警信息
 */
data class AlertInfo(
    val level: AlertLevel,
    val obstacle: DetectedObstacle,
    val message: String,
    val isVoiceEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 场景类型
 */
enum class SceneType(
    val chineseName: String,
    val description: String,
    val indicatorTypes: Set<ObstacleType> = emptySet()
) {
    // ===== 室外场景 =====
    SIDEWALK("人行道", "人行道区域"),
    CROSSWALK("斑马线", "前方有斑马线"),
    STAIR_ENTRANCE("楼梯口", "楼梯入口区域"),
    INTERSECTION("路口", "十字路口或丁字路口"),
    ROAD("普通道路", "普通道路区域"),
    BUILDING_ENTRANCE("建筑物入口", "建筑物入口区域"),
    PARK("公园/绿地", "公园或绿化区域"),

    // ===== 新增：室外精细场景 =====
    TRAFFIC_SIGNAL_AREA("信号灯区域", "前方有红绿灯，请注意信号"),
    CURB("道牙", "前方有道牙/路沿，注意脚下"),
    PUDDLE("积水", "前方路面有积水，请绕行"),
    MANHOLE_COVER("井盖", "注意脚下井盖"),

    // ===== 新增：室内场景 =====
    INDOOR_CORRIDOR("走廊", "室内走廊通道"),
    INDOOR_STAIRS("楼梯间", "室内楼梯间，注意台阶"),
    INDOOR_DOORWAY("门口/门槛", "前方门口或门槛，注意高度差"),
    INDOOR_RESTROOM("厕所", "卫生间区域"),
    INDOOR_ELEVATOR("电梯口", "电梯入口区域"),
    INDOOR_HALL("大厅", "建筑大厅区域"),

    // ===== 新增：公共场所场景（基于障碍物推断）=====
    HOSPITAL_AREA("医院区域", "医院就诊区域，可能有人流和病床"),
    BANK_AREA("银行区域", "银行营业厅区域"),
    SCHOOL_AREA("学校区域", "校园教学区域"),
    SHOPPING_MALL("商城购物区", "商场购物中心区域"),
    RESTAURANT("餐饮区", "餐厅食堂区域"),

    // ===== 新增：交通/动物/运动/专业区域场景 =====
    PARKING_LOT("停车场", "停车场区域，注意车辆"),
    TRANSPORTATION_HUB("交通枢纽", "机场/火车站/港口区域"),
    ZOO_OR_FARM("动物区域", "动物园或农场区域"),
    PET_AREA("宠物聚集区", "宠物聚集区域"),
    SPORTS_AREA("运动区域", "运动场或健身区域"),
    APPLIANCE_AREA("电器区域", "家电展示或使用区域"),
    LIBRARY_AREA("图书馆/书店", "图书馆或书店区域"),
    KITCHEN_AREA("厨房区域", "厨房或备餐区域"),

    // ===== 大型公共场景（扩展）=====
    HOSPITAL("医院", "医院区域，请注意无障碍通道", setOf(ObstacleType.BENCH, ObstacleType.TRAFFIC_SIGN, ObstacleType.OBSTACLE)),
    BANK("银行", "银行区域，注意台阶和门", setOf(ObstacleType.BENCH, ObstacleType.TABLE, ObstacleType.PILLAR)),
    AIRPORT("机场", "机场航站楼，注意行李和指示牌", setOf(ObstacleType.SUITCASE, ObstacleType.BENCH, ObstacleType.TRAFFIC_SIGN)),
    TRAIN_STATION("火车站", "火车站区域，注意台阶和人群", setOf(ObstacleType.BENCH, ObstacleType.SUITCASE, ObstacleType.PERSON)),
    BUS_TERMINAL("汽车客运站", "汽车客运站区域", setOf(ObstacleType.BENCH, ObstacleType.SUITCASE, ObstacleType.BUS)),
    DOCK("码头", "码头区域，注意水边安全", setOf(ObstacleType.BENCH, ObstacleType.RAILING)),
    SCHOOL("学校", "学校区域，注意学生和交通", setOf(ObstacleType.PERSON, ObstacleType.TRAFFIC_SIGN, ObstacleType.BENCH)),
    SUPERMARKET("超市", "超市区域，注意货架和地面", setOf(ObstacleType.TABLE, ObstacleType.PERSON)),
    PHARMACY("药店", "药店区域", setOf(ObstacleType.TABLE, ObstacleType.TRAFFIC_SIGN)),
    RESTAURANT_AREA("餐厅", "餐厅区域，注意桌椅和地面", setOf(ObstacleType.CHAIR, ObstacleType.TABLE, ObstacleType.CUP)),

    // ===== 公交相关场景 =====
    BUS_STOP("公交站台", "公交站台，注意车辆和台阶", setOf(ObstacleType.BENCH, ObstacleType.TRAFFIC_SIGN, ObstacleType.PERSON)),
    BUS_INTERIOR("公交车内", "公交车内，注意扶手和台阶", setOf(ObstacleType.HANDRAIL, ObstacleType.PERSON)),

    // ===== 电梯/自助设备 =====
    ELEVATOR("电梯", "电梯区域，注意门和地面高度差", setOf(ObstacleType.DOOR, ObstacleType.OBSTACLE)),
    ATM("ATM机", "ATM机区域", setOf(ObstacleType.TV, ObstacleType.PILLAR)),

    UNKNOWN("未知", "未识别场景");

    /**
     * 获取场景进入提示
     */
    fun getEntryAnnouncement(): String {
        return when (this) {
            SIDEWALK -> "进入人行道区域"
            CROSSWALK -> "前方斑马线，请注意过往车辆"
            STAIR_ENTRANCE -> "前方楼梯口，请注意台阶"
            INTERSECTION -> "前方路口，请注意交通信号"
            BUILDING_ENTRANCE -> "建筑物入口，请注意"
            PARK -> "进入公园区域"
            ROAD -> ""
            TRAFFIC_SIGNAL_AREA -> "前方有红绿灯，请按信号通行"
            CURB -> "前方有道牙或路沿，小心绊倒"
            PUDDLE -> "前方有积水，请绕行"
            MANHOLE_COVER -> "注意脚下井盖"
            INDOOR_CORRIDOR -> "进入走廊通道"
            INDOOR_STAIRS -> "室内楼梯间，注意台阶高度"
            INDOOR_DOORWAY -> "前方门口或门槛，注意高度差"
            INDOOR_RESTROOM -> "附近有卫生间"
            INDOOR_ELEVATOR -> "电梯入口在前方"
            INDOOR_HALL -> "进入建筑大厅"
            HOSPITAL_AREA -> "进入医院区域，注意病床推车和人流"
            BANK_AREA -> "进入银行营业厅区域"
            SCHOOL_AREA -> "进入校园区域，注意学生人流"
            SHOPPING_MALL -> "进入商场购物区"
            RESTAURANT -> "进入餐饮区域，注意餐桌椅"
            PARKING_LOT -> "进入停车场，注意车辆往来"
            TRANSPORTATION_HUB -> "进入交通枢纽，请留意周围"
            ZOO_OR_FARM -> "前方有大型动物，请保持距离"
            PET_AREA -> "附近有宠物聚集，请注意脚下"
            SPORTS_AREA -> "进入运动区域，注意运动器材"
            APPLIANCE_AREA -> "进入电器区域"
            LIBRARY_AREA -> "进入图书馆或书店"
            KITCHEN_AREA -> "进入厨房区域，注意尖锐物品"
            HOSPITAL -> "进入医院区域，请注意无障碍通道"
            BANK -> "进入银行区域，注意台阶和门"
            AIRPORT -> "进入机场航站楼，注意行李和指示牌"
            TRAIN_STATION -> "进入火车站区域，注意台阶和人群"
            BUS_TERMINAL -> "进入汽车客运站区域"
            DOCK -> "进入码头区域，注意水边安全"
            SCHOOL -> "进入学校区域，注意学生和交通"
            SUPERMARKET -> "进入超市区域，注意货架和地面"
            PHARMACY -> "进入药店区域"
            RESTAURANT_AREA -> "进入餐厅区域，注意桌椅和地面"
            BUS_STOP -> "到达公交站台，注意车辆和台阶"
            BUS_INTERIOR -> "在公交车内，注意扶手和台阶"
            ELEVATOR -> "到达电梯区域，注意门和地面高度差"
            ATM -> "到达ATM机区域"
            UNKNOWN -> ""
        }
    }
}

/**
 * 场景识别结果
 */
data class SceneRecognitionResult(
    val sceneType: SceneType,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 避障模块状态
 */
data class ObstacleState(
    val isRunning: Boolean = false,
    val isCameraReady: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isModelInitComplete: Boolean = false,  // 初始化完成（模型加载成功或失败后都为 true）
    val currentAlert: com.blindpath.base.common.ObstacleAlert? = null,
    val detectedObstacles: List<DetectedObstacle> = emptyList(),
    val sceneRecognition: SceneRecognitionResult? = null,
    val fps: Int = 0,
    val lastError: String? = null
)
