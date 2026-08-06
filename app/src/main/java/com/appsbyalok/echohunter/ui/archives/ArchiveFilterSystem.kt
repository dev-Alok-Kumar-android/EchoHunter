package com.appsbyalok.echohunter.ui.archives

import com.appsbyalok.echohunter.data.LevelFeature

enum class FeatureFilterMode(val id: Int, val label: String) {
    ANY(0, "OR: ANY"),
    ALL(1, "AND: ALL"),
    EXACT(2, "EXACT")
}

class ArchiveFilterSystem {
    var isFilterExpanded = false
    var filterUniqueOnly = false
    var filterFinishedOnly = false
    var sortDescending = true
    var featureFilterMode = FeatureFilterMode.ANY
    val selectedFeatures = mutableSetOf<LevelFeature>()

    fun hasActiveFilters(): Boolean {
        return filterUniqueOnly || filterFinishedOnly || 
               featureFilterMode != FeatureFilterMode.ANY || 
               selectedFeatures.isNotEmpty()
    }

    fun clearAllFilters() {
        filterUniqueOnly = false
        filterFinishedOnly = false
        featureFilterMode = FeatureFilterMode.ANY
        selectedFeatures.clear()
    }
}
