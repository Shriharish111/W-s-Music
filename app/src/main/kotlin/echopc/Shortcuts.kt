package echopc

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Keyboard shortcuts. One table drives the key handling, the hints in button tooltips and the
 * "Keyboard shortcuts" dialog, so they cannot disagree. [keys] is the display text.
 * [repeatable]: holding the key keeps firing (volume, seeking); everything else fires once per press.
 */
enum class Shortcut(val label: String, val keys: String, val group: String, val repeatable: Boolean = false) {
    PLAY_PAUSE("Play or pause", "Space", "Playback"),
    NEXT("Next song", "Ctrl+→", "Playback"),
    PREVIOUS("Previous song", "Ctrl+←", "Playback"),
    SEEK_FORWARD("Skip forward 5 seconds", "Shift+→", "Playback", repeatable = true),
    SEEK_BACK("Skip back 5 seconds", "Shift+←", "Playback", repeatable = true),
    SHUFFLE("Shuffle, Smart Shuffle, off", "Ctrl+S", "Playback"),
    REPEAT("Repeat: off, all, one", "Ctrl+R", "Playback"),
    LIKE("Like or unlike the current song", "Alt+Shift+B", "Playback"),
    VOLUME_UP("Volume up", "Ctrl+↑", "Sound", repeatable = true),
    VOLUME_DOWN("Volume down", "Ctrl+↓", "Sound", repeatable = true),
    MUTE("Mute or unmute", "M", "Sound"),
    SEARCH("Search", "Ctrl+L", "Navigation"),
    BACK("Go back", "Alt+←", "Navigation"),
    FORWARD("Go forward", "Alt+→", "Navigation"),
    HELP("Keyboard shortcuts", "Ctrl+/", "Navigation"),
}

/** Which shortcut (if any) a key press is. Modifiers must match exactly, so Ctrl+Shift+S is not Ctrl+S. */
fun shortcutFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean = false): Shortcut? {
    if (meta) return null
    val none = !ctrl && !shift && !alt
    return when (key) {
        Key.Spacebar -> if (!ctrl && !alt) Shortcut.PLAY_PAUSE else null
        Key.DirectionRight -> when { ctrl && !shift && !alt -> Shortcut.NEXT; shift && !ctrl && !alt -> Shortcut.SEEK_FORWARD; alt && !ctrl && !shift -> Shortcut.FORWARD; else -> null }
        Key.DirectionLeft -> when { ctrl && !shift && !alt -> Shortcut.PREVIOUS; shift && !ctrl && !alt -> Shortcut.SEEK_BACK; alt && !ctrl && !shift -> Shortcut.BACK; else -> null }
        Key.DirectionUp -> if (ctrl && !shift && !alt) Shortcut.VOLUME_UP else null
        Key.DirectionDown -> if (ctrl && !shift && !alt) Shortcut.VOLUME_DOWN else null
        Key.S -> if (ctrl && !shift && !alt) Shortcut.SHUFFLE else null
        Key.R -> if (ctrl && !shift && !alt) Shortcut.REPEAT else null
        Key.L -> if (ctrl && !shift && !alt) Shortcut.SEARCH else null
        Key.B -> if (alt && shift && !ctrl) Shortcut.LIKE else null
        Key.M -> if (none) Shortcut.MUTE else null
        Key.Slash -> if (ctrl && !shift && !alt) Shortcut.HELP else null
        else -> null
    }
}

/**
 * Window-level key handling. While a text field has focus only Search and Help work, so typing,
 * word-jumping (Ctrl+arrows) and Space in the search box behave normally.
 */
class ShortcutHandler(private val c: Controller) {
    private var heldKey: Key? = null

    /** @return true when the event was a shortcut (and is consumed). */
    fun onKeyEvent(e: KeyEvent): Boolean = when (e.type) {
        KeyEventType.KeyDown -> onKey(e.key, true, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, e.isMetaPressed)
        KeyEventType.KeyUp -> onKey(e.key, false, false, false, false, false)
        else -> false
    }

    fun onKey(key: Key, down: Boolean, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Boolean {
        if (!down) { if (key == heldKey) heldKey = null; return false }
        val s = shortcutFor(key, ctrl, shift, alt, meta) ?: return false
        if (c.typing && s != Shortcut.HELP && s != Shortcut.SEARCH) return false
        // A key held down repeats KeyDown; only the repeatable shortcuts should repeat.
        val repeat = heldKey == key
        heldKey = key
        if (repeat && !s.repeatable) return true
        c.perform(s)
        return true
    }
}
