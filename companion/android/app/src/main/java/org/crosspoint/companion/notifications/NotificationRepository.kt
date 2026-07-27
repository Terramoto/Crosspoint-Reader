package org.crosspoint.companion.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PhoneNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

object NotificationRepository {
    private const val PREFS = "crosspoint_notifications"
    private const val ITEMS = "active_items"
    private const val ENABLED = "enabled"
    private const val INTERVAL = "interval_minutes"
    private const val MAX_STORED = 20

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled)
        if (!enabled) editor.putString(ITEMS, "[]")
        editor.apply()
    }

    fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(INTERVAL, 5)
            .takeIf { it in setOf(1, 5, 10, 15, 30) } ?: 5

    fun setIntervalMinutes(context: Context, minutes: Int) {
        if (minutes !in setOf(1, 5, 10, 15, 30)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(INTERVAL, minutes).apply()
    }

    @Synchronized
    fun upsert(context: Context, item: PhoneNotification) {
        val items = load(context).filterNot { it.key == item.key }.toMutableList()
        items.add(item)
        save(context, items.sortedByDescending { it.timestamp }.take(MAX_STORED))
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        save(context, load(context).filterNot { it.key == key })
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
    }

    @Synchronized
    fun latest(context: Context, limit: Int): List<PhoneNotification> =
        load(context).sortedByDescending { it.timestamp }.take(limit.coerceIn(0, MAX_STORED))

    private fun load(context: Context): List<PhoneNotification> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ITEMS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.getJSONObject(index)
                    add(
                        PhoneNotification(
                            key = value.optString("key"),
                            app = value.optString("app"),
                            title = value.optString("title"),
                            text = value.optString("text"),
                            timestamp = value.optLong("timestamp"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<PhoneNotification>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("app", item.app)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("timestamp", item.timestamp)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ITEMS, array.toString()).apply()
    }
}
