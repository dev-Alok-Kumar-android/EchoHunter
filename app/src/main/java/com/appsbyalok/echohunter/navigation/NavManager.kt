package com.appsbyalok.echohunter.navigation

import android.os.Bundle
import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState

/**
 * Handles Global Navigation Stack for nested menus.
 * Prevents navigation traps by tracking the path taken by the user.
 */
class NavManager(private val gs: GameState) {
    private val navigationStack = mutableListOf<AppStateId>()

    /**
     * Pushes the current state to history if it's a menu state.
     */
    fun pushCurrentState() {
        val currentState = gs.state
        // Only track menu states
        if (currentState == AppStateId.MENU || currentState.isSubMenu) {
            if (navigationStack.isEmpty() || navigationStack.last() != currentState) {
                navigationStack.add(currentState)
            }
        }
    }

    /**
     * Returns the previous state from the stack or null if empty.
     */
    fun popPreviousState(): AppStateId? {
        if (navigationStack.isNotEmpty()) {
            return navigationStack.removeAt(navigationStack.size - 1)
        }
        return null
    }

    /**
     * Clears all navigation history.
     */
    fun clearHistory() {
        navigationStack.clear()
    }

    /**
     * Saves the navigation stack to a bundle for process death recovery.
     */
    fun saveState(outState: Bundle) {
        outState.putIntArray("navigationStack", navigationStack.map { it.id }.toIntArray())
    }

    /**
     * Restores the navigation stack from a bundle.
     */
    fun restoreState(savedInstanceState: Bundle?) {
        savedInstanceState?.getIntArray("navigationStack")?.let {
            navigationStack.clear()
            navigationStack.addAll(it.map { id -> AppStateId.fromInt(id) })
        }
    }

    /**
     * Checks if there are any states in the navigation history.
     */
    fun isStackEmpty(): Boolean = navigationStack.isEmpty()
}
