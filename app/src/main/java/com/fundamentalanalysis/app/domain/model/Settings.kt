package com.fundamentalanalysis.app.domain.model

/** App appearance. [SYSTEM] follows the phone's light/dark setting. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}
