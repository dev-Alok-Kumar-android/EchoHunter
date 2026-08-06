package com.appsbyalok.echohunter.ui.terminal

import android.os.Handler
import android.os.Looper
import com.appsbyalok.echohunter.data.SaveManager
import com.appsbyalok.echohunter.data.StoryProtocol
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class HelpCommand : TerminalCommand {
    override val name = "HELP"
    override val description = "Lists available commands and usage info"
    override val manual = """
        USAGE: HELP [COMMAND]
        Lists all available commands when used without arguments.
        Provides detailed usage and manual for a specific command.
        Example: HELP DEBUG
    """.trimIndent()

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        val gs = context.gameState

        if (args.isNotEmpty()) {
            val target = args[0].uppercase()
            val cmd = CommandRegistry.getCommand(target)
            return if (cmd != null && cmd.isUnlocked(gs)) {
                CommandResult("=== MANUAL: ${cmd.name} ===\n${cmd.manual}", OutputMode.LINE_BY_LINE)
            } else {
                CommandResult("ERR: COMMAND '$target' NOT FOUND OR RESTRICTED.", OutputMode.INSTANT)
            }
        }

        val unlockedCmds = CommandRegistry.getAllCommands(gs)
            .filter { it.isUnlocked(gs) }
            .sortedBy { it.name }
        
        val sb = StringBuilder("=== NANO-OS HELP SYSTEM ===\n")
        sb.append("OBJECTIVE: ${if (gs.currentLevel == 1 && SaveManager.maxCampaignLevel == 1) "Access Archives or CONFIG." else "Clear Ring ${gs.currentLevel}."}\n\n")
        
        sb.append("AVAILABLE COMMANDS:\n")
        unlockedCmds.forEach { cmd ->
            sb.append("  ${cmd.name.padEnd(10)} - ${cmd.description}\n")
        }

        sb.append("\nUPCOMING UNLOCKS:\n")
        var anyPending = false
        if (SaveManager.maxCampaignLevel <= 2) {
            sb.append("  - SCAN      : Reach Ring 2\n")
            anyPending = true
        }
        if (!SaveManager.isHardModeUnlocked) {
            sb.append("  - SUDO/GOD  : Complete 3 Story Acts\n")
            anyPending = true
        }
        if (!SaveManager.isStoryModeUnlocked) {
            sb.append("  - STORY_LOG : Reach Ring 15\n")
            anyPending = true
        }

        if (!anyPending) {
            sb.append("  [ ALL SYSTEMS OPERATIONAL. FULL CLEARANCE GRANTED. ]\n")
        }

        sb.append("\nTIP: Use 'HELP [CMD]' for detailed manuals.\n")
        sb.append("TIP: Type 'HELP_TECH' for advanced terminal shortcuts.\n")
        sb.append("TIP: Type 'MARKET' to spend Data Coins on exclusive patches.\n")
        sb.append("TIP: Type 'FIX' if you notice visual corruption or glitches.\n")
        return CommandResult(sb.toString(), OutputMode.LINE_BY_LINE)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return CommandRegistry.getAllCommands(gs)
                .map { it.name }
                .filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class HelpTechCommand : TerminalCommand {
    override val name = "HELP_TECH"
    override val description = "Displays advanced terminal shortcuts and tips"
    override val aliases = listOf("TECH_HELP", "TERM_HELP")
    override val manual = "Displays a technical guide for advanced terminal navigation, shortcuts, and built-in calculator features."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult("""
            === NANO-OS TERMINAL TECH MANUAL ===
            
            NAVIGATION:
            - TAB          : Cycle autocomplete matches
            - CTRL + P / N : Cycle command history (Prev/Next)
            - CTRL + L     : Clear the screen
            - CTRL + C     : Clear current input line
            - CTRL + Q     : Exit terminal or sub-session
            
            SHORTCUTS:
            - ALT + .      : Insert last argument of previous command
            - SHIFT (Double Tap): Toggle CAPS LOCK
            
            LOGIC:
            - CMD1 & CMD2  : Execute multiple commands sequentially
            
            FILE SYSTEM:
            - Use 'CAT [FILE]' to read system logs.
            - Use 'DIR' to list available system files.
            
            CALCULATOR:
            - Type any math expression (e.g., '25 * 4 + 10') to evaluate.
            - Supports (), +, -, *, /, and implicit mult.
        """.trimIndent(), OutputMode.LINE_BY_LINE)
    }
}

class ClearCommand : TerminalCommand {
    override val name = "CLEAR"
    override val description = "Clears the terminal screen"
    override val aliases = listOf("CLS", "CLR")
    override val manual = "Wipes all current text from the terminal screen for a clean workspace."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        context.onClear()
        return CommandResult(null, OutputMode.INSTANT)
    }
}

class ExitCommand : TerminalCommand {
    override val name = "EXIT"
    override val description = "Exits the terminal"
    override val aliases = listOf("QUIT")
    override val manual = "Terminates the current terminal session and wipes the system buffer."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult("""
            
            SHUTTING DOWN NANO-OS...
            CLEANING TEMPORARY BUFFERS...
            SESSION TERMINATED.
        """.trimIndent(), OutputMode.TYPEWRITER)
    }
}

class DirCommand : TerminalCommand {
    override val name = "DIR"
    override val description = "Lists files in the current directory"
    override val aliases = listOf("LS")
    override val manual = """
        USAGE: DIR [PATH]
        Lists all virtual files in the specified directory. 
        If no path is provided, it defaults to /ROOT.
    """.trimIndent()
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        val path = if (args.isNotEmpty()) args[0].uppercase() else "/ROOT"
        
        if (path != "/ROOT" && path != "ROOT" && path != "/") {
            return CommandResult("ERR: DIRECTORY '$path' NOT FOUND.", OutputMode.INSTANT)
        }

        val sb = StringBuilder("FILES IN $path:\n")
        context.files.forEach { (name, size) ->
            sb.append("  ${name.padEnd(18)} $size\n")
        }
        return CommandResult(sb.toString().trimEnd(), OutputMode.LINE_BY_LINE)
    }
}

class DateCommand : TerminalCommand {
    override val name = "DATE"
    override val description = "Displays the current system date and time"
    override val manual = "Displays the current timestamp according to the local system clock."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), OutputMode.INSTANT)
    }
}

class VerCommand : TerminalCommand {
    override val name = "VER"
    override val description = "Displays NANO-OS version information"
    override val manual = "Shows detailed system versioning, build timestamps, and current kernel identification."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult("""
            
            NANO-OS v4.2.1-STABLE
            BUILD: 2024.11.12-RELEASE
            KERNEL: ECHO-CORE-9000
        """.trimIndent(), OutputMode.TYPEWRITER)
    }
}

class WhoamiCommand : TerminalCommand {
    override val name = "WHOAMI"
    override val description = "Displays current user and host information"
    override val manual = "Displays your current user ID, host name, authorization level (USER/ROOT), and a session UUID."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        val auth = if (SaveManager.isHardModeUnlocked) "ROOT/ADMIN" else "USER/READ-ONLY"
        val uuid = UUID.randomUUID().toString().take(8).uppercase()
        return CommandResult("""
            USER: PROBE-7
            HOST: ECHO_NET_CENTRAL
            AUTH: $auth
            UUID: $uuid
        """.trimIndent(), OutputMode.INSTANT)
    }
}

class ScanCommand : TerminalCommand {
    override val name = "SCAN"
    override val description = "Scans surrounding sectors for activity"
    override val manual = "Performs a sector-wide scan to detect active nodes and signal stability."
    override fun isUnlocked(gs: GameState): Boolean = gs.currentLevel >= 2 || SaveManager.maxCampaignLevel > 2
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult("""
            SCANNING NODES...
            FOUND: ${context.gameState.currentSector} ACTIVE SECTORS
            SIGNAL: STABLE
        """.trimIndent(), OutputMode.LINE_BY_LINE)
    }
}

class CatCommand : TerminalCommand {
    override val name = "CAT"
    override val description = "Displays the contents of a file"
    override val manual = """
        USAGE: CAT [FILENAME]
        Prints the text content of the specified file to the terminal.
        Example: CAT USER_DATA.LOG
    """.trimIndent()
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) return CommandResult("USAGE: CAT [FILENAME]", OutputMode.INSTANT)
        val output = when (val filename = args[0].uppercase()) {
            "ROOT_TERM.SH" -> """
                #!/BIN/NANO-OS
                ECHO 'INITIATING ECHO PROTOCOL...'
                EXEC ./KERNEL_DUMP.BIN --FORCE
            """.trimIndent()
            "USER_DATA.LOG" -> """
                10.24.01 | NODE_ACCESS_GRANTED
                10.24.05 | DATA_STREAM_STABLE
                10.25.12 | WARNING: ECHO_DETECTION
            """.trimIndent()
            "ECHO_PROTOCOL.CFG" -> """
                [PROTOCOL]
                VERSION=4.2
                ENCRYPTION=AES-ECHO-256
            """.trimIndent()
            "PROJECT_ECHO.DOC" -> """
                SUBJECT: PROJECT ECHO
                STATUS: PROBE-7 DEPLOYED
            """.trimIndent()
            "REDACTED_MSG.TXT" -> """
                MESSAGE: DON'T BELIEVE THE OS.
                THE ECHO IS NOT THE ENEMY.
            """.trimIndent()
            "MAINFRAME.SYS" -> """
                [SYSTEM_MANIFEST]
                ID: MH-9000
                STATUS: ACTIVE
                NODES: 4
                - TRAINING_PORT [ACTIVE]
                - STORY_ACT_1   [DECRYPTED]
                - STORY_ACT_2   [LOCKED]
                - STORY_ACT_3   [LOCKED]
            """.trimIndent()
            "KERNEL_DUMP.BIN" -> "ERR: CANNOT READ BINARY DATA"
            else -> "ERR: FILE '$filename' NOT FOUND"
        }
        return CommandResult(output, if (output.startsWith("ERR")) OutputMode.INSTANT else OutputMode.TYPEWRITER)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        val files = listOf("ROOT_TERM.SH", "USER_DATA.LOG", "ECHO_PROTOCOL.CFG", "PROJECT_ECHO.DOC", "REDACTED_MSG.TXT", "KERNEL_DUMP.BIN")
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return files.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class EchoCommand : TerminalCommand {
    override val name = "ECHO"
    override val description = "Echoes input text back to the terminal"
    override val manual = "USAGE: ECHO [MESSAGE]\nSimply repeats the provided message. Useful for testing output or script diagnostics."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult(args.joinToString(" "), OutputMode.INSTANT)
    }
}

class SudoCommand : TerminalCommand {
    override val name = "SUDO"
    override val description = "Executes a command with superuser privileges"
    override val manual = "USAGE: SUDO [COMMAND]\nAttempts to run a restricted system command. Access is only granted to users with ROOT clearance."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (SaveManager.isHardModeUnlocked) {
            if (args.isEmpty()) return CommandResult("USAGE: SUDO [COMMAND]", OutputMode.INSTANT)
            val subCmd = args[0].uppercase()
            return CommandResult("ROOT ACCESS GRANTED. EXECUTING '$subCmd'...", OutputMode.TYPEWRITER)
        }
        return CommandResult("ERR: USER NOT IN SUDOERS FILE.", OutputMode.INSTANT)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        if (SaveManager.isHardModeUnlocked && args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return CommandRegistry.getAllCommands(gs)
                .map { it.name }
                .filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class RebootCommand : TerminalCommand {
    override val name = "REBOOT"
    override val description = "Reboots the NANO-OS system"
    override val manual = "Triggers a full system restart sequence. Clears all terminal buffers and re-initializes kernel services."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        Handler(Looper.getMainLooper()).postDelayed({
            context.onClear()
            context.onAddLines("NANO-OS [Version 4.2.0-ROOT]", OutputMode.INSTANT)
            context.onAddLines("SYSTEM REBOOT SUCCESSFUL.", OutputMode.TYPEWRITER)
        }, 1500)
        return CommandResult("REBOOTING SYSTEM...", OutputMode.TYPEWRITER)
    }
}

class DebugCommand : TerminalCommand {
    override val name = "DEBUG"
    override val description = "Developer tools for system testing"
    override val manual = """
        USAGE: DEBUG [SUBCOMMAND] [VALUE]
        
        SUBCOMMANDS:
        - godmod       : Toggle invulnerability.
        - unlockall    : Unlock all campaign levels and features.
        - unlock [n]   : Unlock campaign up to Ring [n].
        - unlocktill [n]: Same as unlock.
        - finishtill [n]: Mark all levels up to [n] as 3-star cleared.
        - unlockstory  : Unlock all story fragments.
        - adddata [v]  : Inject [v] amount of Data (KB).
        - resetall     : Wipe all progress and settings.
    """.trimIndent()
    override fun isUnlocked(gs: GameState): Boolean = true
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) return CommandResult("USAGE: DEBUG [godmod|unlockall|unlock <n>|finishtill <n>|unlockstory|adddata <v>|resetall]", OutputMode.INSTANT)
        
        val sub = args[0].lowercase()
        val gs = context.gameState
        
        val output = when (sub) {
            "godmod" -> {
                gs.modGodMode = !gs.modGodMode
                "GOD MODE: ${if (gs.modGodMode) "ACTIVE" else "DISABLED"}"
            }
            "unlockall" -> {
                SaveManager.debugUnlockAll()
                "ALL SYSTEMS OVERRIDDEN. CAMPAIGN MAXED TO INT_MAX."
            }
            "unlock", "unlocktill" -> {
                if (args.size < 2) return CommandResult("ERR: SPECIFY LEVEL", OutputMode.INSTANT)
                val lvl = args[1].toIntOrNull() ?: return CommandResult("ERR: INVALID LEVEL", OutputMode.INSTANT)
                SaveManager.debugSetLevel(lvl)
                "CAMPAIGN ACCESS GRANTED UP TO RING $lvl."
            }
            "finishtill" -> {
                if (args.size < 2) return CommandResult("ERR: SPECIFY LEVEL", OutputMode.INSTANT)
                val lvl = args[1].toIntOrNull() ?: return CommandResult("ERR: INVALID LEVEL", OutputMode.INSTANT)
                
                if (lvl <= 1000) {
                    for (i in 1..lvl) SaveManager.saveLevelStats(i, 60f, 3)
                } else {
                    for (i in 1..100) SaveManager.saveLevelStats(i, 60f, 3)
                    val startTail = max(1, lvl - 100)
                    for (i in startTail..lvl) SaveManager.saveLevelStats(i, 60f, 3)
                    val step = max(1000, lvl / 100)
                    for (m in step..lvl step step) SaveManager.saveLevelStats(m, 60f, 3)
                }
                SaveManager.debugSetLevel(lvl + 1)
                "SUCCESS: RINGS 1-$lvl MARKED AS COMPLETED (OPTIMIZED)."
            }
            "unlockstory" -> {
                SaveManager.debugModifyStoryStreak(3)
                "STORY PROTOCOLS DECRYPTED. ACT 3 CLEARED."
            }
            "adddata" -> {
                if (args.size < 2) return CommandResult("ERR: SPECIFY VALUE", OutputMode.INSTANT)
                val amount = args[1].toLongOrNull() ?: return CommandResult("ERR: INVALID VALUE", OutputMode.INSTANT)
                SaveManager.addData(amount)
                "INJECTED: ${SaveManager.formatDataString(amount)}"
            }
            "resetall" -> {
                SaveManager.clearAllData()
                gs.difficulty = DifficultyLevel.NORMAL // Force return to Normal Mode
                "SYSTEM PURGED. ALL DATA AND PROGRESS WIPED."
            }
            else -> "ERR: UNKNOWN DEBUG PARAMETER '$sub'"
        }
        return CommandResult(output, OutputMode.INSTANT)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        val subs = listOf("godmod", "unlockall", "unlock", "unlocktill", "finishtill", "unlockstory", "adddata", "resetall")
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].lowercase()
            return subs.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class SysInfoCommand : TerminalCommand {
    override val name = "SYS_INFO"
    override val description = "Displays current system resource and level status"
    override val manual = "Summarizes critical system data, including current Probe location (Ring) and total Data Coins harvested."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult("""
            HOST: PROBE-7
            OS: NANO-OS v4.2.0
            DATA: ${SaveManager.formatDataString(SaveManager.dataCoinsKB)}
            LEVEL: ${context.gameState.currentLevel}
        """.trimIndent(), OutputMode.INSTANT)
    }
}


class ConfigCommand : TerminalCommand {
    override val name = "CONFIG"
    override val description = "Manage game settings: SOUND, VIBE, FX"
    override val aliases = listOf("SETTINGS", "SET")
    override val manual = """
        USAGE: CONFIG [KEY] [ON/OFF]
        
        KEYS:
        - SOUND    : Toggle background music and SFX.
        - VIBE     : Toggle haptic vibration.
        - FX        : Toggle advanced visual effects.
        
        Example: CONFIG SOUND OFF
    """.trimIndent()
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) {
            return CommandResult("""
                SYSTEM CONFIGURATION:
                SOUND  : ${if (SaveManager.isSoundEnabled) "ON" else "OFF"}
                VIBE   : ${if (SaveManager.isVibrationEnabled) "ON" else "OFF"}
                FX     : ${if (SaveManager.isEffectsEnabled) "ON" else "OFF"}
                
                USAGE: CONFIG [KEY] [ON/OFF]
            """.trimIndent(), OutputMode.INSTANT)
        }
        
        if (args.size < 2) return CommandResult("ERR: MISSING VALUE (ON/OFF)", OutputMode.INSTANT)
        
        val key = args[0].uppercase()
        val value = args[1].uppercase() == "ON"
        
        val output = when (key) {
            "SOUND" -> {
                SaveManager.setSoundEnabled(value)
                "SOUND SYSTEM ${if (value) "INITIALIZED" else "DISABLED"}"
            }
            "VIBE", "VIBRATION" -> {
                SaveManager.setVibrationEnabled(value)
                "HAPTIC FEEDBACK ${if (value) "ACTIVE" else "MUTED"}"
            }
            "FX", "EFFECTS" -> {
                SaveManager.setEffectsEnabled(value)
                "VISUAL EFFECTS ${if (value) "MAXIMIZED" else "OPTIMIZED (MINIMAL)"}"
            }
            else -> "ERR: UNKNOWN CONFIG KEY '$key'"
        }
        return CommandResult(output, OutputMode.INSTANT)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return listOf("SOUND", "VIBE", "FX").filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            val prefix = args[1].uppercase()
            return listOf("ON", "OFF").filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class UICommand : TerminalCommand {
    override val name = "UI"
    override val description = "Customize terminal aesthetics: THEME, SPEED, SIZE"
    override val aliases = listOf("THEME", "STYLE", "APPEARANCE")
    override val manual = """
        USAGE: UI [KEY] [VALUE]
        
        KEYS:
        - THEME : DARK, AMBER, MATRIX, CLARITY, BLOOD
        - SPEED : 1 (Slow) to 5 (Instant)
        - SIZE  : SMALL, NORMAL, LARGE
        
        Example: UI THEME AMBER
    """.trimIndent()

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) {
            return CommandResult("""
                CURRENT UI SETTINGS:
                THEME  : ${SaveManager.terminalTheme}
                SPEED  : ${SaveManager.typewriterSpeed}
                SIZE   : ${SaveManager.fontSize}
                
                USAGE: UI [KEY] [VALUE]
            """.trimIndent(), OutputMode.INSTANT)
        }

        if (args.size < 2) return CommandResult("ERR: MISSING VALUE.", OutputMode.INSTANT)
        
        val key = args[0].uppercase()
        val value = args[1].uppercase()

        val output = when (key) {
            "THEME" -> {
                SaveManager.terminalTheme = value
                "SYSTEM COLOR PALETTE UPDATED TO $value."
            }
            "SPEED" -> {
                val s = value.toIntOrNull()?.coerceIn(1, 5) ?: 3
                SaveManager.typewriterSpeed = s
                "TEXT RENDERING SPEED SET TO LEVEL $s."
            }
            "SIZE", "FONT" -> {
                SaveManager.fontSize = value
                "INTERFACE SCALE ADJUSTED TO $value."
            }
            else -> "ERR: UNKNOWN UI PARAMETER '$key'"
        }
        
        // UX Tip: Style changes should be INSTANT so the user sees the effect immediately
        return CommandResult(output, OutputMode.INSTANT)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return listOf("THEME", "SPEED", "SIZE").filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            val key = args[0].uppercase()
            val prefix = args[1].uppercase()
            return when(key) {
                "THEME" -> listOf("DARK", "AMBER", "MATRIX", "CLARITY", "BLOOD")
                "SPEED" -> listOf("1", "2", "3", "4", "5")
                "SIZE" -> listOf("SMALL", "NORMAL", "LARGE")
                else -> emptyList()
            }.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class FixCommand : TerminalCommand {
    override val name = "FIX"
    override val description = "Repairs UI glitches and resets visual anomalies"
    override val aliases = listOf("REPAIR", "DEGLITCH")
    override val manual = """
        USAGE: FIX
        Scans for active visual glitches and system corruption. 
        Resets screen shake, chromatic aberration, inverted controls, and blackout states.
    """.trimIndent()

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        val gs = context.gameState
        var foundAny = false

        if (StoryProtocol.isGlitchActive || StoryProtocol.areControlsInverted || StoryProtocol.isBlackoutActive) {
            StoryProtocol.isGlitchActive = false
            StoryProtocol.areControlsInverted = false
            StoryProtocol.isBlackoutActive = false
            foundAny = true
        }

        if (gs.shakeAmount > 0f || gs.chromaticIntensity > 0f || gs.damageFlash > 0f || 
            gs.sectorFlash > 0f || gs.empFlashTimer > 0f || gs.whiteFlash > 0f) {
            gs.shakeAmount = 0f
            gs.chromaticIntensity = 0f
            gs.damageFlash = 0f
            gs.sectorFlash = 0f
            gs.empFlashTimer = 0f
            gs.whiteFlash = 0f
            foundAny = true
        }

        return if (foundAny) {
            CommandResult("""
                >> INITIATING SYSTEM REPAIR...
                >> ANALYZING VISUAL BUFFERS... DONE.
                >> RESETTING POST-PROCESSING STACK... DONE.
                >> RESTORING CONTROL UPLINK... DONE.
                >> SYSTEM STABILIZED.
            """.trimIndent(), OutputMode.TYPEWRITER)
        } else {
            CommandResult("SCAN COMPLETE: NO ANOMALIES DETECTED. SYSTEM NOMINAL.", OutputMode.INSTANT)
        }
    }
}

class GlitchCommand : TerminalCommand {
    override val name = "GLITCH"
    override val description = "Artificially induces system instability"
    override val manual = """
        USAGE: GLITCH [TYPE]
        Induces visual anomalies for testing.
        TYPES: SHAKE, COLOR, INVERT, DARK, ALL
    """.trimIndent()
    override fun isUnlocked(gs: GameState): Boolean = SaveManager.isHardModeUnlocked

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) return CommandResult("USAGE: GLITCH [SHAKE|COLOR|INVERT|DARK|ALL]", OutputMode.INSTANT)
        
        val gs = context.gameState
        val type = args[0].uppercase()
        
        when (type) {
            "SHAKE" -> gs.shakeAmount = 0.5f
            "COLOR" -> gs.chromaticIntensity = 1.0f
            "INVERT" -> StoryProtocol.areControlsInverted = true
            "DARK" -> StoryProtocol.isBlackoutActive = true
            "ALL" -> {
                gs.shakeAmount = 0.5f
                gs.chromaticIntensity = 1.0f
                StoryProtocol.areControlsInverted = true
                StoryProtocol.isBlackoutActive = true
                StoryProtocol.isGlitchActive = true
            }
            else -> return CommandResult("ERR: UNKNOWN GLITCH TYPE '$type'", OutputMode.INSTANT)
        }
        
        return CommandResult("ANOMALY INJECTED: $type", OutputMode.TYPEWRITER)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].uppercase()
            return listOf("SHAKE", "COLOR", "INVERT", "DARK", "ALL").filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}

class LevelCommand : TerminalCommand {
    override val name = "START"
    override val description = "Jump to a specific Ring (Level)"
    override val aliases = listOf("GOTO", "LEVEL")
    override val manual = """
        USAGE: START [RING_NUMBER] [MODE] [DIFFICULTY]
        Directs the probe to a specific Ring level and initializes gameplay.
        
        MODES: CAMPAIGN (Default), STORY, TRAINING
        DIFFICULTY: NORMAL (Default), HARD
        
        Examples:
        - START 10
        - START 5 STORY
        - START 1 HARD
    """.trimIndent()

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (args.isEmpty()) return CommandResult("USAGE: START [LEVEL_NUMBER] [MODE] [DIFFICULTY]", OutputMode.INSTANT)
        val level = args[0].toIntOrNull() ?: return CommandResult("ERR: INVALID LEVEL NUMBER", OutputMode.INSTANT)
        if (level < 1) return CommandResult("ERR: LEVEL OUT OF RANGE (1-2147483647)", OutputMode.INSTANT)

        val gs = context.gameState
        
        // Mode detection
        var mode = GameModeId.CAMPAIGN
        if (args.size >= 2) {
            val modeStr = args[1].uppercase()
            mode = when (modeStr) {
                "STORY" -> GameModeId.STORY
                "TRAINING" -> GameModeId.TRAINING
                "CAMPAIGN" -> GameModeId.CAMPAIGN
                else -> {
                    // Check if 2nd arg is difficulty instead
                    if (modeStr == "HARD" || modeStr == "NORMAL") mode
                    else return CommandResult("ERR: UNKNOWN MODE '$modeStr'", OutputMode.INSTANT)
                }
            }
        }

        // Difficulty detection
        var difficulty = DifficultyLevel.NORMAL
        for (i in 1 until args.size) {
            val arg = args[i].uppercase()
            if (arg == "HARD") difficulty = DifficultyLevel.HARD
            if (arg == "NORMAL") difficulty = DifficultyLevel.NORMAL
        }

        // Lock check
        if (mode == GameModeId.CAMPAIGN && level > SaveManager.maxCampaignLevel && !SaveManager.isHardModeUnlocked) {
             return CommandResult("ERR: SECTOR RING $level IS LOCKED. CLEAR PREVIOUS RINGS.", OutputMode.INSTANT)
        }
        
        if (mode == GameModeId.STORY && !SaveManager.isStoryModeUnlocked && !SaveManager.isHardModeUnlocked) {
            return CommandResult("ERR: STORY PROTOCOLS ENCRYPTED. REACH RING 15 TO UNLOCK.", OutputMode.INSTANT)
        }

        // Apply difficulty
        gs.difficulty = difficulty
        
        // Start Game!
        context.onStartGame(mode, level)
        
        return CommandResult("INITIALIZING RING $level [MODE: $mode] [DIFF: $difficulty]...", OutputMode.TYPEWRITER)
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> {
        return when (args.size) {
            1 -> emptyList() // Level number
            2 -> listOf("CAMPAIGN", "STORY", "TRAINING", "HARD", "NORMAL").filter { it.startsWith(args[1].uppercase()) }
            3 -> listOf("HARD", "NORMAL").filter { it.startsWith(args[2].uppercase()) }
            else -> emptyList()
        }
    }
}

class ResetTutorialCommand : TerminalCommand {
    override val name = "SYSTEM.RESET_TUTORIAL"
    override val description = "Resets all onboarding and tutorial flags"
    override val aliases = listOf("RESET_TUTORIAL", "TUTORIAL_RESET")
    override val manual = "Wipes tutorial completion flags, restoring the initial UI hints and gameplay guides for a fresh start."
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        SaveManager.resetTutorials()
        return CommandResult("""
            RESTORING ONBOARDING MODULES...
            CLEANING USER_GUIDE CACHE...
            TUTORIAL FLAGS RESET SUCCESSFUL.
        """.trimIndent(), OutputMode.TYPEWRITER)
    }
}

class StoryLogCommand : TerminalCommand {
    override val name = "STORY_LOG"
    override val description = "Displays all story text (requires Story completion)"
    override val manual = "Decrypts and displays the chronological log of all recovered story fragments. Requires full Story decryption to access."
    override fun isUnlocked(gs: GameState): Boolean = SaveManager.isStoryModeUnlocked
    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        if (SaveManager.unlockedStoryStreak < 3) {
            return CommandResult("ERR: ENCRYPTION DETECTED. COMPLETE ALL 3 ACTS TO DECRYPT LOGS.", OutputMode.INSTANT)
        }
        
        val androidContext = context.androidContext
        val sb = StringBuilder("--- DECRYPTED STORY LOGS ---\n")
        
        val allStoryRes = StoryProtocol.storyIntroLines + StoryProtocol.storyMidLines + 
                        StoryProtocol.storyPerfectEnding + StoryProtocol.storyNeutralEnding + 
                        StoryProtocol.badEndingLines
                        
        allStoryRes.forEach { resId ->
            try {
                val text = androidContext.getString(resId)
                sb.append("- $text\n\n")
            } catch (_: Exception) {
            }
        }
        
        return CommandResult(sb.toString().trim(), OutputMode.LINE_BY_LINE)
    }
}

class MarketCommand : TerminalCommand {
    override val name = "MARKET"
    override val description = "Access the Underground Market for level skips and patches"
    override val aliases = listOf("SHOP", "STORE", "BLACKMARKET")
    override val manual = """
        USAGE: MARKET
        Enters the interactive Underground Market sub-session.
        Inside the market, you can use 'LIST', 'BUY [ITEM]', and 'EXIT'.
    """.trimIndent()

    private val skips = mapOf("SKIP_10" to 10, "SKIP_50" to 50, "SKIP_100" to 100)
    private val skipCosts = mapOf("SKIP_10" to 1024000L, "SKIP_50" to 4096000L, "SKIP_100" to 7168000L)

    override fun execute(args: List<String>, context: CommandContext): CommandResult {
        return CommandResult(
            output = ">> ACCESSING UNDERGROUND MARKET...\n>> TYPE 'LIST' TO SEE ITEMS, 'EXIT' TO LEAVE.",
            mode = OutputMode.TYPEWRITER,
            subSession = MarketSession()
        )
    }

    inner class MarketSession : InputHandler {
        override fun getPrompt(): String = "MARKET>"

        override fun handleInput(input: String, context: CommandContext): CommandResult {
            val parts = input.trim().split(" ").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return CommandResult(null, OutputMode.INSTANT)

            return when (val action = parts[0].uppercase()) {
                "LIST" -> CommandResult(getList(), OutputMode.LINE_BY_LINE, subSession = this)
                "BUY" -> {
                    if (parts.size < 2) CommandResult("ERR: SPECIFY ITEM_ID.", OutputMode.INSTANT, subSession = this)
                    else {
                        val res = handleBuy(parts[1].uppercase())
                        res.copy(subSession = this)
                    }
                }
                "EXIT" -> CommandResult(">> EXITING MARKET. RETURNED TO ROOT.", OutputMode.TYPEWRITER, subSession = null)
                else -> CommandResult("ERR: UNKNOWN MARKET COMMAND '$action'. TYPE 'LIST', 'BUY', OR 'EXIT'.", OutputMode.INSTANT, subSession = this)
            }
        }

        private fun getList(): String {
            val sb = StringBuilder()
            sb.append("╔═══════════════════════════════════════════╗\n")
            sb.append("         - UNDERGROUND DATA MARKET -         \n")
            sb.append("╚═══════════════════════════════════════════╝\n")
            sb.append("CREDITS: ${SaveManager.formatDataString(SaveManager.dataCoinsKB)}\n")
            sb.append("─".repeat(45) + "\n")
            sb.append(" [ LEVEL SKIP MODULES ]\n")
            skips.forEach { (id, count) ->
                sb.append("  > ${id.padEnd(12)} : +$count Rings (Cost: ${SaveManager.formatDataString(skipCosts[id]!!)}KB)\n")
            }
            sb.append("\n [ FIRMWARE PATCHES ]\n")
            val patches = listOf(
                com.appsbyalok.echohunter.data.UpgradeType.PATCH_OVERCLOCK_REGEN,
                com.appsbyalok.echohunter.data.UpgradeType.PATCH_HEALTH_SIPHON,
                com.appsbyalok.echohunter.data.UpgradeType.PATCH_SHIELD_BURST
            )
            patches.forEach { type ->
                val config = com.appsbyalok.echohunter.data.UpgradeSystem.catalog[type]!!
                val owned = com.appsbyalok.echohunter.data.UpgradeSystem.getLevel(type) > 0
                val status = if (owned) " [INSTALLED] " else " Cost: ${SaveManager.formatDataString(config.baseCostKB)}KB"
                sb.append("  > ${type.name.replace("PATCH_", "").padEnd(12)} : ${config.descStr}\n    $status\n")
            }
            sb.append("─".repeat(45) + "\n")
            return sb.toString()
        }

        private fun handleBuy(itemId: String): CommandResult {
            if (skips.containsKey(itemId)) {
                val cost = skipCosts[itemId]!!
                return if (SaveManager.spendData(cost)) {
                    val count = skips[itemId]!!
                    SaveManager.debugSetLevel(SaveManager.maxCampaignLevel + count)
                    CommandResult(">> JUMP SUCCESSFUL. SKIPPED $count RINGS.", OutputMode.TYPEWRITER)
                } else {
                    CommandResult("ERR: INSUFFICIENT DATA.", OutputMode.INSTANT)
                }
            }

            val patchType = try {
                com.appsbyalok.echohunter.data.UpgradeType.valueOf("PATCH_$itemId")
            } catch (_: Exception) {
                null
            }

            if (patchType != null) {
                if (com.appsbyalok.echohunter.data.UpgradeSystem.getLevel(patchType) > 0) {
                    return CommandResult("ERR: ALREADY INSTALLED.", OutputMode.INSTANT)
                }
                val config = com.appsbyalok.echohunter.data.UpgradeSystem.catalog[patchType]!!
                return if (SaveManager.spendData(config.baseCostKB)) {
                    com.appsbyalok.echohunter.data.UpgradeSystem.purchaseUpgrade(patchType)
                    CommandResult(">> PATCH INSTALLED: $itemId.", OutputMode.TYPEWRITER)
                } else {
                    CommandResult("ERR: INSUFFICIENT DATA.", OutputMode.INSTANT)
                }
            }
            return CommandResult("ERR: ITEM '$itemId' NOT FOUND.", OutputMode.INSTANT)
        }
    }

    override fun getSuggestions(args: List<String>, gs: GameState): List<String> = emptyList()
}
