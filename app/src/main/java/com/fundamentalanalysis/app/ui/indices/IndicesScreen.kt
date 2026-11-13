package com.fundamentalanalysis.app.ui.indices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fundamentalanalysis.app.domain.model.Metric
import com.fundamentalanalysis.app.ui.components.EmptyState
import com.fundamentalanalysis.app.ui.components.IndexRow
import com.fundamentalanalysis.app.ui.components.LoadingState
import com.fundamentalanalysis.app.ui.components.ScreenScaffold

@Composable
fun IndicesScreen(
    onOpenIndex: (String) -> Unit,
    viewModel: IndicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Indices",
        actions = {
            IconButton(onClick = { viewModel.onWatchlistOnlyChange(!state.filters.watchlistOnly) }) {
                Icon(
                    if (state.filters.watchlistOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (state.filters.watchlistOnly) "Show all indices" else "Show watchlist only",
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Metric.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label + if (m == state.filters.sortBy) "  ✓" else "") },
                            onClick = { viewModel.onSortChange(m); sortMenu = false },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = state.filters.category == null, onClick = { viewModel.onCategorySelected(null) }, label = { Text("All") })
                }
                items(state.categories) { c ->
                    FilterChip(
                        selected = state.filters.category == c,
                        onClick = { viewModel.onCategorySelected(if (state.filters.category == c) null else c) },
                        label = { Text(c.label) },
                    )
                }
            }
            Text(
                "${state.indices.size} indices · sorted by ${state.filters.sortBy.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            when {
                state.loading -> LoadingState()
                state.indices.isEmpty() -> EmptyState("No indices match these filters.")
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
                    items(state.indices, key = { it.symbol }) { idx ->
                        IndexRow(
                            index = idx,
                            onClick = { onOpenIndex(idx.symbol) },
                            extraMetric = state.filters.sortBy.takeIf { it != Metric.RETURN_1D },
                            onToggleWatch = { viewModel.toggleWatchlist(idx.symbol) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
