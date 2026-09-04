package com.kisshkitty.core.kitty

import com.kisshkitty.core.kitty.KittyProtocolParser.DeleteSelector
import com.kisshkitty.core.kitty.KittyProtocolParser.KittyEvent
import com.kisshkitty.core.kitty.KittyProtocolParser.ShowOp

/**
 * Turns raw terminal output into clean text plus ordered Kitty events.
 *
 * Text and graphics sequences are processed in stream order so image
 * anchors line up with the cursor position at display time.
 */
class KittyImageRenderer {

    private val parser = KittyProtocolParser()

    sealed interface OutputEvent {
        data class Text(val text: String) : OutputEvent
        data class Show(val image: KittyImage, val op: ShowOp) : OutputEvent
        data class Delete(val selector: DeleteSelector) : OutputEvent
        data class Respond(val payload: String) : OutputEvent
    }

    data class KittyOutput(
        val events: List<OutputEvent>
    )

    /**
     * Split text around complete Kitty sequences, parsing each one.
     * Only complete sequences are passed here (the UI layer holds back
     * a trailing incomplete escape).
     */
    fun processOutput(text: String): KittyOutput {
        if (!parser.containsKittySequence(text)) {
            return KittyOutput(listOf(OutputEvent.Text(text)))
        }

        val events = mutableListOf<OutputEvent>()
        var cursor = 0
        for (range in parser.extractSequenceRanges(text)) {
            if (range.first > cursor) {
                events.add(OutputEvent.Text(text.substring(cursor, range.first)))
            }
            val sequence = text.substring(range)
            for (event in parser.parse(sequence)) {
                events.add(
                    when (event) {
                        is KittyEvent.Show -> OutputEvent.Show(event.image, event.op)
                        is KittyEvent.Delete -> OutputEvent.Delete(event.selector)
                        is KittyEvent.Respond -> OutputEvent.Respond(event.payload)
                    }
                )
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            events.add(OutputEvent.Text(text.substring(cursor)))
        }
        return KittyOutput(events)
    }

    /**
     * Query the terminal for Kitty graphics support.
     * Returns the query sequence to send.
     */
    fun createQuerySequence(): String {
        return "${KittyProtocolParser.APC_START}Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA${KittyProtocolParser.APC_END}"
    }

    fun getParser(): KittyProtocolParser = parser
}
