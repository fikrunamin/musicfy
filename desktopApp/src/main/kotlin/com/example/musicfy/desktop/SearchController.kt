package com.example.musicfy.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.musicfy.constants.ContentCountryKey
import com.example.musicfy.db.desktop.DesktopDao
import com.example.musicfy.db.entities.SearchHistory
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SongItem> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val contentCountry: String? = null,
    val error: String? = null,
)

fun interface SongSearch {
    suspend fun search(query: String): Result<List<SongItem>>
}

object InnerTubeSongSearch : SongSearch {
    override suspend fun search(query: String): Result<List<SongItem>> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).map { result -> result.items.filterIsInstance<SongItem>() }
}

/** The skeleton's only screen logic: search InnerTube, remember queries in the DB, show a setting. */
class SearchController(
    private val search: SongSearch,
    private val dao: DesktopDao,
    private val settings: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun start(): Job = scope.launch {
        val country = settings.data.first()[ContentCountryKey]
        _state.update { it.copy(contentCountry = country) }
        dao.searchHistory().collect { history ->
            _state.update { it.copy(recentQueries = history.map(SearchHistory::query)) }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun submit(): Job = scope.launch {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return@launch
        _state.update { it.copy(loading = true, error = null) }
        dao.insert(SearchHistory(query = query))
        search.search(query)
            .onSuccess { songs -> _state.update { it.copy(loading = false, results = songs) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) } }
    }
}
