package dev.wholivedwhen.support

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextTests {

    @Test
    fun `slugify strips accents and punctuation`() {
        assertEquals("emile-zola", slugify("Émile Zola"))
        assertEquals("sen-no-rikyu", slugify("Sen no Rikyū"))
        assertEquals("a-peasant-in-the-hundred-years-war", slugify("A peasant in the Hundred Years' War"))
    }

    @Test
    fun `leadSentences keeps whole sentences within the limit`() {
        val text = "First sentence here. Second one is a bit longer. Third would not fit."
        assertEquals("First sentence here. Second one is a bit longer.", leadSentences(text, maxLength = 55))
    }

    @Test
    fun `leadSentences always keeps the first sentence and does not split abbreviations before numbers`() {
        assertEquals("Born c. 1412 in Domrémy, she led armies.", leadSentences("Born c. 1412 in Domrémy, she led armies. More.", maxLength = 10))
    }

    @Test
    fun `leadSentences removes the space Wikipedia leaves before punctuation`() {
        assertEquals("Zeami Motokiyo, also called Kanze Motokiyo, was a playwright.", leadSentences("Zeami Motokiyo , also called Kanze Motokiyo , was a playwright."))
    }

    @Test
    fun `dropHatnote removes the line pointing to someone else of the same name, and nothing else`() {
        assertEquals("Amenemhat III was a pharaoh.", dropHatnote("See Amenemhat, for other individuals with this name.\nAmenemhat III was a pharaoh."))
        assertEquals("Jacques de Billy was a Jesuit.", dropHatnote("For the abbot, see Jacques de Billy (abbot).\n\nJacques de Billy was a Jesuit."))
        assertEquals("For decades he painted.\nThen he stopped.", dropHatnote("For decades he painted.\nThen he stopped."))
    }
}
