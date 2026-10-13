package art.liminis.curation

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * What claims rest on. A source is stored once, by its kind and its locator (its fields but the quote, as JSON), and
 * each claim points at it with its own quote: the same Wikidata entity or book page supports several claims.
 */
@Component
class ClaimSources(private val sources: SourceRepository, private val jsonMapper: JsonMapper) {

    /** The sources [claim] rests on, in the order they were added. */
    fun of(claim: Claim): List<SourceRef> = claim.sources.map(::toRef)

    /** Adds those of [added] that [claim] does not already rest on, after the ones it has. */
    fun attach(claim: Claim, added: List<SourceRef>) {
        val attached = of(claim).toMutableList()
        for (source in added.filter { it !in attached }) {
            val locator = jsonMapper.writeValueAsString(locatorOf(source))
            sources.addIfMissing(source.kind.name, locator)
            claim.sources += ClaimSource(sources.find(source.kind.name, locator), source.quote)
            attached += source
        }
    }

    private fun toRef(source: ClaimSource): SourceRef =
        jsonMapper.convertValue(source.source.locator + mapOf("kind" to source.source.kind.name, "quote" to source.quote), SourceRef::class.java)

    /** A source's fields but its kind and quote, which the source's identity does not include. */
    private fun locatorOf(source: SourceRef): Map<*, *> =
        jsonMapper.convertValue(source, Map::class.java).filterKeys { it != "kind" && it != "quote" }.filterValues { it != null }
}
