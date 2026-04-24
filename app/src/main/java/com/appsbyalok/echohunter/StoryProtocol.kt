package com.appsbyalok.echohunter

import kotlin.random.Random

object StoryProtocol {

    // --- In Game popup system ---
    var currentPopup: String = ""
    var popupTimer: Float = 0f
    var isGlitchActive: Boolean = false
    var areControlsInverted: Boolean = false

    fun showIngameMessage(msg: String, duration: Float = 3f) {
        currentPopup = msg
        popupTimer = duration
    }

    fun update(dt: Float) {
        if (popupTimer > 0f) {
            popupTimer -= dt
        }
        if (bossIntroTimer > 0f) {
            bossIntroTimer -= dt
        }
    }

    // --- Cinematic Intro for Boss ---
    var bossIntroTimer: Float = 0f
    var currentBossName: String = ""

    fun startBossIntro(bossType: Int) {
        currentBossName = when(bossType) {
            0 -> "GUARDIAN-01: THE WATCHER"
            1 -> "GUARDIAN-02: MUTATED CORE"
            2 -> "GUARDIAN-03: SYSTEM LEACH"
            3 -> "GUARDIAN-04: MEMORY EATER"
            else -> "GUARDIAN-OMEGA: THE ARCHITECT"
        }
        bossIntroTimer = 2.5f // 2.5 सेकंड के लिए गेम फ्रीज होगा
    }

    // --- Story Lines for each Mode ---

    // 1. ENDLESS WAVES (Survival)
    val endlessIntroLines = arrayOf(
        "> SYSTEM TRAP DETECTED.",
        "> THERE IS NO EXIT.",
        "> THIS IS NOT A MISSION ANYMORE...",
        "> THIS IS SURVIVAL."
    )
    val endlessPopups = arrayOf(
        "THEY ARE WATCHING YOU...",
        "HOW LONG CAN YOU LAST?",
        "SIGNAL LOST...",
        "SYSTEM MEMORY LEAKING..."
    )

    // 2. STORY MODE (Mainframe Salvation - Canon Story)
    val storyIntroLines = arrayOf(
        "> INITIALIZING PROBE-7...",
        "> SYSTEM CORRUPTION AT 98%.",
        "> DIRECTIVE: PURGE THE SECTORS.",
        "> PROBE-7, YOU ARE OUR LAST HOPE."
    )
    val storyMidLines = arrayOf(
        "> WARNING: THE VIRUS IS EVOLVING.",
        "> IT IS LEARNING FROM YOUR MOVES.",
        "> I... I THINK I AM CORRUPTING TOO..."
    )
    // Dark Twist Endings
    val storyPerfectEnding = arrayOf(
        "> MAINFRAME PURGED WITH ZERO ERRORS.",
        "> CORRUPTION ELIMINATED.",
        "> NEW CORE PROTOCOL ACCEPTED...",
        "> NEW CORE IDENTITY: PROBE-7.",
        "> (YOU HAVE BECOME THE SYSTEM)"
    )
    val storyNeutralEnding = arrayOf(
        "> MAINFRAME PURGED.",
        "> HEAVY DAMAGE SUSTAINED.",
        "> I CAN'T SEE ANYTHING...",
        "> REBOOTING IN THE DARK..."
    )

    // 3. FIREWALL BREACH (Escape Protocol)
    val firewallIntroLines = arrayOf(
        "> ANOMALY DETECTED IN PROBE-7.",
        "> YOU ARE NO LONGER AUTHORIZED.",
        "> INITIATING DELETION FIREWALL.",
        "> RUN."
    )
    val firewallPopups = arrayOf(
        "STOP RUNNING.",
        "WE CREATED YOU.",
        "YOU ARE THE VIRUS NOW.",
        "THERE IS NOWHERE TO HIDE."
    )

    val badEndingLines = arrayOf(
        "> CRITICAL FAILURE.",
        "> PROBE-7 DELETED.",
        "> THE CORRUPTION WINS."
    )

    // गेम के दौरान रैंडम ग्लिच ट्रिगर करने का फंक्शन
    fun triggerRandomGlitch(score: Int, gameMode: Int) {
        if (score > 30 && Random.nextDouble() < 0.05) {
            isGlitchActive = true
            showIngameMessage("SYSTEM GLITCH... DON'T TRUST YOUR EYES", 2f)
        } else {
            isGlitchActive = false
            areControlsInverted = false
        }

        // कहानी के बीच में कंट्रोल्स उल्टे कर देना
        if (gameMode == 1 && score > 100 && Random.nextDouble() < 0.1) {
            areControlsInverted = true
            showIngameMessage("THE SYSTEM IS FIGHTING BACK!", 3f)
        }
    }
}