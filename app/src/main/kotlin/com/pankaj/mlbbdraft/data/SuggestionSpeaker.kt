package com.pankaj.mlbbdraft.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks the top pick aloud.
 *
 * The overlay solved "I have to leave the game", but not "I cannot read a panel in a
 * twelve-second pick window". Hearing "Pick Phoveus, counters Ling" costs no attention at
 * all, which is the only budget a player has during a draft.
 *
 * Deliberately terse and heavily de-duplicated: an assistant that talks over itself every
 * time the board twitches gets muted within one game.
 */
class SuggestionSpeaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var lastSpoken: String? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
                // Draft timers are short; the default rate is slower than the situation.
                engine?.setSpeechRate(1.15f)
            }
        }
    }

    /**
     * Says a line, unless it is the same line as last time. [key] identifies the content so
     * a re-render or an unrelated board edit does not re-trigger speech.
     */
    fun announce(key: String, text: String) {
        if (!ready || text.isBlank() || key == lastSpoken) return
        lastSpoken = key
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    /** Call when the draft resets, so the next identical suggestion is spoken again. */
    fun forget() {
        lastSpoken = null
    }

    fun stop() {
        engine?.stop()
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}
