package com.appsbyalok.echohunter.ui.terminal

import android.content.Context
import com.appsbyalok.echohunter.engine.GameState

/**
 * Defines how the terminal should render the command output.
 */
enum class OutputMode {
    INSTANT,      // Immediate display (e.g., clear, small status)
    TYPEWRITER,   // Character by character (narrative/immersion)
    LINE_BY_LINE  // Line by line with slight delay (systemic processing)
}

/**
 * Result of a command execution, including how to display it and if it starts a sub-session.
 */
data class CommandResult(
    val output: String? = null,
    val mode: OutputMode = OutputMode.TYPEWRITER,
    val subSession: InputHandler? = null
)

/**
 * Interface for objects that can handle terminal input (Global Terminal or Sub-modes).
 */
interface InputHandler {
    fun handleInput(input: String, context: CommandContext): CommandResult
    fun getPrompt(): String = ">"
}

/**
 * Interface for all terminal commands.
 */
interface TerminalCommand : InputHandler {
    val name: String
    val description: String
    val aliases: List<String> get() = emptyList()
    val manual: String get() = "No detailed information available for $name."

    /**
     * Check if the command is currently available to the player.
     */
    fun isUnlocked(gs: GameState): Boolean = true

    /**
     * Main execution logic (called when the command is first invoked).
     */
    fun execute(args: List<String>, context: CommandContext): CommandResult

    /**
     * Default implementation of handleInput for simple commands.
     */
    override fun handleInput(input: String, context: CommandContext): CommandResult {
        val parts = input.trim().split(" ").filter { it.isNotEmpty() }
        val args = if (parts.size > 1) parts.drop(1) else emptyList()
        return execute(args, context)
    }

    /**
     * Provides suggestions for autocompletion of arguments/subcommands.
     */
    fun getSuggestions(args: List<String>, gs: GameState): List<String> = emptyList()
}

/**
 * Context provided to commands during execution.
 * Allows commands to interact with the game state and terminal UI.
 */
data class CommandContext(
    val gameState: GameState,
    val terminalLines: MutableList<String>,
    val onExit: () -> Unit,
    val files: Map<String, String>,
    val onAddLines: (String, OutputMode) -> Unit = { _, _ -> },
    val onClear: () -> Unit = {},
    val onStartGame: (com.appsbyalok.echohunter.engine.GameModeId, Int) -> Unit = { _, _ -> },
    val androidContext: Context
)
