package com.appsbyalok.echohunter.engine

/**
 * Identifier for all application screens and states.
 */
enum class AppStateId(val id: Int) {
    MENU(0),
    PLAYING(1),
    PAUSE(2),
    HELP(3),
    STORY_GAMEOVER(4),
    STORY_INTRO(5),
    STORY_ENDING(6),
    STORY_MID(7),
    CORE_MERGE(8),
    PERFECT_END_ZOOM(9),
    DECOMPILER(10),
    ARCHIVES(11),
    VICTORY(12),
    ARSENAL(13),
    NANO_OS(14),
    MAINFRAME(15),
    SETTINGS(16),
    TERMINAL(17);

    // Helpers to clean up logic in GameEngine/GameView
    val isGameplay: Boolean get() = this == PLAYING || this == CORE_MERGE || this == PERFECT_END_ZOOM
    val isStory: Boolean get() = this == STORY_INTRO || this == STORY_MID || this == STORY_ENDING || this == STORY_GAMEOVER
    val isSubMenu: Boolean get() = id in 10..17

    companion object {
        private val map = entries.associateBy(AppStateId::id)
        fun fromInt(id: Int): AppStateId = map[id] ?: MENU
    }
}

/**
 * Identifier for game modes.
 */
enum class GameModeId(val id: Int) {
    CAMPAIGN(0),
    STORY(1),
    TRAINING(2);

    companion object {
        private val map = entries.associateBy(GameModeId::id)
        fun fromInt(id: Int): GameModeId = map[id] ?: CAMPAIGN
    }
}

/**
 * Difficulty levels affecting AI and visibility.
 */
enum class DifficultyLevel(val id: Int) {
    NORMAL(0),
    HARD(1);

    companion object {
        fun fromInt(id: Int): DifficultyLevel = if (id == 1) HARD else NORMAL
    }
}
