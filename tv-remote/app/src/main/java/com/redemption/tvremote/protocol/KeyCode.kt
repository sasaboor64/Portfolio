package com.redemption.tvremote.protocol

/** Android KeyEvent key codes, which the remote protocol uses as-is. */
object KeyCode {
    const val HOME = 3
    const val BACK = 4
    const val DIGIT_0 = 7
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val A = 29
    const val COMMA = 55
    const val PERIOD = 56
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MINUS = 69
    const val SLASH = 76
    const val AT = 77
    /** The menu / options key: opens the current app's options menu on the TV. */
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val SETTINGS = 176
    const val TV_INPUT = 178
    /** KEYCODE_TV_MEDIA_CONTEXT_MENU: the "menu" key on some TV remotes. */
    const val TV_CONTEXT_MENU = 257

    /** Key code for a character, used to type when the TV has no text field focused. */
    fun forChar(ch: Char): Int? = when (val c = ch.lowercaseChar()) {
        in 'a'..'z' -> A + (c - 'a')
        in '0'..'9' -> DIGIT_0 + (c - '0')
        ' ' -> SPACE
        '.' -> PERIOD
        ',' -> COMMA
        '-' -> MINUS
        '/' -> SLASH
        '@' -> AT
        '\n' -> ENTER
        else -> null
    }
}

enum class KeyDirection(val value: Int) { START_LONG(1), END_LONG(2), SHORT(3) }
