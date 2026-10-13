package art.liminis.drafting

import art.liminis.domain.CardType

private const val MIN_CARDS = 4
private const val MAX_CARDS = 10
private val WHITESPACE = "\\s+".toRegex()

/**
 * Checks a draft against the sources it was written from, before a curator reads it.
 * The model is asked to cite; this verifies that every citation is real and every reference resolves.
 */
object StoryDraftValidator {

    fun problems(draft: StoryDraft, sources: List<Source>, startYear: Int, endYear: Int): List<String> {
        val byId = sources.associateBy { it.id }
        val problems = mutableListOf<String>()

        fun checkCitations(where: String, citations: List<Citation>) {
            if (citations.isEmpty()) problems += "$where has no citation"
            for (citation in citations) {
                val source = byId[citation.sourceId]
                when {
                    source == null -> problems += "$where cites unknown source '${citation.sourceId}'"
                    !normalise(source.text).contains(normalise(citation.quote)) ->
                        problems += "$where quotes text not found in '${citation.sourceId}': \"${citation.quote}\""
                }
            }
        }

        checkCitations("hook", draft.hook.citations)
        with(draft.world) {
            checkCitations("world.governs", governs.citations)
            checkCitations("world.everyday", everyday.citations)
            checkCitations("world.arts", arts.citations)
            checkCitations("world.meanwhile", meanwhile.citations)
        }

        if (draft.cards.size !in MIN_CARDS..MAX_CARDS) problems += "story has ${draft.cards.size} cards, expected $MIN_CARDS to $MAX_CARDS"
        draft.cards.forEachIndexed { index, card ->
            val where = "card ${index + 1} (${card.type})"
            checkCitations(where, card.citations)
            card.year?.let { if (it !in startYear..endYear) problems += "$where is set in $it, outside $startYear–$endYear" }
            when (card.type) {
                CardType.PERSON -> requireSource(where, "person", card.personSlug, byId, problems)
                CardType.LIFE -> requireSource(where, "life", card.lifeId, byId, problems)
                CardType.EVENT -> requireSource(where, "event", card.eventId, byId, problems)
                CardType.SCENE -> if (card.title.isNullOrBlank()) problems += "$where has no title"
            }
        }
        return problems
    }

    private fun requireSource(where: String, kind: String, id: String?, byId: Map<String, Source>, problems: MutableList<String>) {
        if (id.isNullOrBlank()) problems += "$where names no $kind"
        else if ("$kind:$id" !in byId) problems += "$where names unknown $kind '$id'"
    }

    /** Quotes are compared ignoring case, spacing and curly quotes, which models often normalise. */
    private fun normalise(text: String) =
        text.lowercase().replace('’', '\'').replace('“', '"').replace('”', '"').replace(WHITESPACE, " ").trim()
}
