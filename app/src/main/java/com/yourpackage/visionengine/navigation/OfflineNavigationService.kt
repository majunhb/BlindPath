package com.yourpackage.visionengine.navigation

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class OfflineNavigationService(private val apiKey: String) {
    private val client = OkHttpClient()

    suspend fun getWalkingRoute(origin: Location, destination: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://restapi.amap.com/v3/direction/walking?" +
                      "origin=${origin.longitude},${origin.latitude}" +
                      "&destination=$destination&key=$apiKey"
            
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")

            if (json.getString("status") == "1") {
                val steps = json.getJSONObject("route").getJSONArray("paths").getJSONObject(0).getJSONArray("steps")
                val instructions = mutableListOf<String>()
                for (i in 0 until steps.length()) {
                    instructions.add(steps.getJSONObject(i).getString("instruction"))
                }
                return@withContext instructions
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
