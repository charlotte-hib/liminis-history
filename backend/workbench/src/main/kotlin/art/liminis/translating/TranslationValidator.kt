package art.liminis.translating

import art.liminis.curation.TranslatedText

/**
 * What a translation's French should hold and may not: what the curator looks at first, as flags. It never refuses one.
 * Every year and number of the English is in the French; a term of the glossary is in its French; the French is not
 * left in English, nor much shorter or longer than the English.
 */
object TranslationValidator {

    fun problems(texts: List<TranslatedText>, glossary: Map<String, String>): List<String> = texts.flatMap { text ->
        val where = text.path
        buildList {
            NUMBER.findAll(text.en).map { it.value }.distinct().filter { it !in text.fr }.forEach { add("$where: ${it} is not in the French") }
            glossary.filter { (en, fr) -> text.en.contains(en, ignoreCase = true) && !text.fr.contains(fr, ignoreCase = true) }
                .forEach { (en, fr) -> add("$where: \"$en\" is \"$fr\" elsewhere") }
            if (text.en.length >= LONG && text.fr == text.en) add("$where: left in English")
            if (text.en.length >= LONG && text.fr.length !in (text.en.length * SHORTEST).toInt()..(text.en.length * LONGEST).toInt()) {
                add("$where: ${text.fr.length} characters for ${text.en.length} in English")
            }
        }
    }

    /** Years and numbers, without the decade's "s": "1870s" is 1870. */
    private val NUMBER = Regex("""\d+""")

    /** How long a text must be for its French to be measured against it. */
    private const val LONG = 20
    private const val SHORTEST = 0.5
    private const val LONGEST = 2.0
}
