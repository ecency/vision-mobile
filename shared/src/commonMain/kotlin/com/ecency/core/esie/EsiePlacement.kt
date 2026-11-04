package com.ecency.core.esie

/**
 * Where Esie appears in the apps, with the mood and height (dp on Android, points on iOS) each kind of place uses,
 * so every empty list, error, success moment and greeting looks the same on both platforms (README "Esie").
 */
enum class EsiePlacement(val mood: EsieMood, val height: Int, val waveCycles: Int? = null) {
    /** Empty lists (no posts, notifications, bookmarks, drafts, chats, search results): resting, a gentle idle. */
    EMPTY(EsieMood.REST, 120),

    /** Load failures and the error screen: sad. */
    ERROR(EsieMood.SAD, 120),

    /** Success moments on existing success UI (published, spin result, quests done): one hop, then idle. */
    SUCCESS(EsieMood.HAPPY, 96),

    /** The first-run welcome screen: waves a few times, then rests. */
    WELCOME(EsieMood.WAVE, 160, waveCycles = 3),

    /** The login screen: waves a few times, then rests; smaller so the form stays above the keyboard. */
    LOGIN(EsieMood.WAVE, 96, waveCycles = 3),

    /** The account boost purchase screen, in place of RN's old coin hugging mascot: a thumbs up. */
    PROMO(EsieMood.THUMBS_UP, 160),
    ;

    fun spec(reduceMotion: Boolean, talking: Boolean = false): EsieSpec = EsieSpec(mood, talking, waveCycles, reduceMotion = reduceMotion)
}
