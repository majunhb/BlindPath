package com.blindpath.base.data.local

import androidx.room.TypeConverter
import java.util.Date

/**
 * Room 类型转换器
 */
class RoomTypeConverters {
    
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }
    
    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
    
    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        return value?.split("|||") ?: emptyList()
    }
    
    @TypeConverter
    fun stringListToString(list: List<String>?): String? {
        return list?.joinToString("|||")
    }
    
    @TypeConverter
    fun fromDoubleArray(value: String?): DoubleArray? {
        return value?.split(",")?.map { it.toDouble() }?.toDoubleArray()
    }
    
    @TypeConverter
    fun doubleArrayToString(array: DoubleArray?): String? {
        return array?.joinToString(",")
    }
}
