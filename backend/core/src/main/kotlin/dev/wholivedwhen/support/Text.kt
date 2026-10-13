package dev.wholivedwhen.support

import java.text.Normalizer

private val DIACRITICS = "\\p{M}+".toRegex()
private val NON_ALPHANUMERIC = "[^a-z0-9]+".toRegex()
private val SENTENCE_END = "(?<=[.!?])\\s+(?=[A-Z\"“(])".toRegex()
// Wikipedia extracts drop parenthesised text and leave "Name , also called".
private val SPACE_BEFORE_PUNCTUATION = "\\s+(?=[,.;:])".toRegex()
private val WHITESPACE = "\\s+".toRegex()
// A line Wikipedia puts above an article for people looking for someone else: "See Amenemhat, for other individuals
// with this name." or "For the abbot, see Jacques de Billy (abbot)."
private val HATNOTE = "^(?:See |For [^\\n]*\\bsee\\b)[^\\n]*\\n+".toRegex()

/** "Émile Zola" becomes "emile zola": lowercase, without accents, for matching. */
fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

/** "Émile Zola" becomes "emile-zola". */
fun slugify(text: String): String = foldForSearch(text).replace(NON_ALPHANUMERIC, "-").trim('-')

/** A Wikipedia extract tidied for display: single spaces, and no space before punctuation. */
fun cleanExtract(text: String): String = text.trim().replace(WHITESPACE, " ").replace(SPACE_BEFORE_PUNCTUATION, "")

/** A Wikipedia extract without the note above it that points readers to someone else of the same name. */
fun dropHatnote(text: String): String = text.replace(HATNOTE, "")

/** The leading sentences of [text] that fit in [maxLength] characters, and always at least the first one. */
fun leadSentences(text: String, maxLength: Int = 280): String {
    val sentences = cleanExtract(text).split(SENTENCE_END)
    var lead = sentences.first()
    for (sentence in sentences.drop(1)) {
        if (lead.length + 1 + sentence.length > maxLength) break
        lead += " $sentence"
    }
    return lead
}
