package com.ezano.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Small persistent state: logins, favorites, watch history, settings, reminders. */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ezano", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _playlists = MutableStateFlow(read("playlists", ListSerializer(Playlist.serializer())) ?: emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString("active", null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _favorites = MutableStateFlow(read("favorites", SetSerializer(String.serializer())) ?: emptySet())
    /** Keys like "live:<id>", "movie:<id>", "series:<id>" (scoped per playlist via prefix). */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow(read("history", ListSerializer(HistoryEntry.serializer())) ?: emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(read("settings", AppSettings.serializer()) ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _reminders = MutableStateFlow(read("reminders", ListSerializer(Reminder.serializer())) ?: emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _recentSearches = MutableStateFlow(read("searches", ListSerializer(String.serializer())) ?: emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    val activePlaylist: Playlist? get() = _playlists.value.firstOrNull { it.id == _activeId.value } ?: _playlists.value.firstOrNull()

    private fun <T> read(key: String, s: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(s, it) }.getOrNull() }

    private fun <T> write(key: String, s: kotlinx.serialization.KSerializer<T>, v: T) {
        prefs.edit().putString(key, json.encodeToString(s, v)).apply()
    }

    // ---- playlists ----
    fun savePlaylist(p: Playlist, makeActive: Boolean = true) {
        val list = _playlists.value.filterNot { it.id == p.id } + p
        _playlists.value = list
        write("playlists", ListSerializer(Playlist.serializer()), list)
        if (makeActive) setActive(p.id)
    }

    fun removePlaylist(id: String) {
        val list = _playlists.value.filterNot { it.id == id }
        _playlists.value = list
        write("playlists", ListSerializer(Playlist.serializer()), list)
        if (_activeId.value == id) setActive(list.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        _activeId.value = id
        prefs.edit().putString("active", id).apply()
    }

    // ---- favorites (scoped to active playlist) ----
    private fun scoped(key: String) = "${_activeId.value}|$key"

    fun isFavorite(key: String) = scoped(key) in _favorites.value

    fun toggleFavorite(key: String): Boolean {
        val k = scoped(key)
        val set = _favorites.value.toMutableSet()
        val added = if (k in set) { set.remove(k); false } else { set.add(k); true }
        _favorites.value = set
        write("favorites", SetSerializer(String.serializer()), set)
        return added
    }

    /** Favorite keys for the active playlist, e.g. "live:123". Most recently added last. */
    fun favoriteKeys(): List<String> {
        val prefix = "${_activeId.value}|"
        return _favorites.value.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    // ---- history ----
    fun record(entry: HistoryEntry) {
        val e = entry.copy(key = scoped(entry.key), updatedAt = System.currentTimeMillis())
        val list = (listOf(e) + _history.value.filterNot { it.key == e.key }).take(300)
        _history.value = list
        write("history", ListSerializer(HistoryEntry.serializer()), list)
    }

    fun removeHistory(key: String) {
        val list = _history.value.filterNot { it.key == scoped(key) }
        _history.value = list
        write("history", ListSerializer(HistoryEntry.serializer()), list)
    }

    fun clearHistory() {
        _history.value = emptyList()
        write("history", ListSerializer(HistoryEntry.serializer()), emptyList())
    }

    /** History for active playlist with unscoped keys. */
    fun historyForActive(): List<HistoryEntry> {
        val prefix = "${_activeId.value}|"
        return _history.value.filter { it.key.startsWith(prefix) }.map { it.copy(key = it.key.removePrefix(prefix)) }
    }

    fun resumePosition(key: String): Long =
        _history.value.firstOrNull { it.key == scoped(key) }?.let { if (it.progress in 0.02f..0.95f) it.positionMs else 0L } ?: 0L

    // ---- settings ----
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        write("settings", AppSettings.serializer(), s)
    }

    // ---- reminders ----
    fun addReminder(r: Reminder) {
        val list = (_reminders.value.filterNot { it.channelId == r.channelId && it.start == r.start } + r).sortedBy { it.start }
        _reminders.value = list
        write("reminders", ListSerializer(Reminder.serializer()), list)
    }

    fun removeReminder(r: Reminder) {
        val list = _reminders.value.filterNot { it.channelId == r.channelId && it.start == r.start }
        _reminders.value = list
        write("reminders", ListSerializer(Reminder.serializer()), list)
    }

    fun hasReminder(channelId: String, start: Long) = _reminders.value.any { it.channelId == channelId && it.start == start }

    // ---- searches ----
    fun addRecentSearch(q: String) {
        val t = q.trim(); if (t.length < 2) return
        val list = (listOf(t) + _recentSearches.value.filterNot { it.equals(t, true) }).take(10)
        _recentSearches.value = list
        write("searches", ListSerializer(String.serializer()), list)
    }
}
