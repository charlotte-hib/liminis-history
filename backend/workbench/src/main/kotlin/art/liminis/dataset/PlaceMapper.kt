package art.liminis.dataset

/**
 * Maps a place's Wikidata entity to its row of the fetched dataset: where it lies today, by its coordinates and by
 * what Wikidata says. Which of them gives a person their region is the compiler's decision.
 */
class PlaceMapper(private val borders: Borders) {

    fun map(entity: WikidataEntity): PlaceRow {
        val coordinates = entity.statements(COORDINATES).firstNotNullOfOrNull { it.mainsnak.coordinates }
        val disputedArea = coordinates?.let(borders::disputedAreaAt)
        return PlaceRow(
            qid = entity.id,
            label = entity.name("en", "mul"),
            labelFr = entity.label("fr", "mul"),
            coordinates = coordinates,
            coordinatesCountry = coordinates?.let(borders::countryAt)?.iso,
            disputedArea = disputedArea?.name,
            countries = entity.statements(COUNTRY).filter { END !in it.qualifiers }.mapNotNull { it.mainsnak.id }.distinct()
                .map { PlaceCountry(it, borders.country(it)?.iso) },
            flags = if (disputedArea != null) setOf(PlaceFlag.DISPUTED_AREA) else emptySet(),
        )
    }

    companion object {
        private const val COORDINATES = "P625"
        private const val COUNTRY = "P17"
        private const val END = "P582"

        /** The statements the mapping reads. */
        val PROPERTIES = listOf(COORDINATES, COUNTRY)
    }
}
