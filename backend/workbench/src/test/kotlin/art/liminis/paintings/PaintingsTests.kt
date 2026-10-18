package art.liminis.paintings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Commons file a pasted text names, if it names one. */
class PaintingsTests {

    private val wave = "File:Tsunami by hokusai 19th century.jpg"

    @Test
    fun `a link to a file's page, on Commons or a Wikipedia, names that file`() {
        assertEquals(wave, fileNamed("https://commons.wikimedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg"))
        assertEquals(wave, fileNamed("https://commons.m.wikimedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg#/media"))
        assertEquals(wave, fileNamed("https://en.wikipedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg"))
        assertEquals(wave, fileNamed("https://commons.wikimedia.org/w/index.php?title=File:Tsunami_by_hokusai_19th_century.jpg&oldid=1"))
        assertEquals(wave, fileNamed("https://commons.wikimedia.org/wiki/FILE:Tsunami_by_hokusai_19th_century.jpg"))
        assertEquals("File:Kobayashi Kiyochika (1876) Tōkyō Shin-Ōhashi uchū.jpg", fileNamed("https://commons.wikimedia.org/wiki/File:Kobayashi_Kiyochika_(1876)_T%C5%8Dky%C5%8D_Shin-%C5%8Chashi_uch%C5%AB.jpg"))
    }

    @Test
    fun `a link to the image, or a copy of it, on either of Wikimedia's hosts names that file`() {
        assertEquals(wave, fileNamed("https://upload.wikimedia.org/wikipedia/commons/a/a5/Tsunami_by_hokusai_19th_century.jpg"))
        assertEquals(wave, fileNamed("https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a5/Tsunami_by_hokusai_19th_century.jpg/1280px-Tsunami_by_hokusai_19th_century.jpg"))
    }

    @Test
    fun `a typed name is taken as it is, a percent sign included, and words are searched for`() {
        assertEquals("File:100% pure.jpg", fileNamed("file:100% pure.jpg"))
        assertEquals("File:A broken %zz link.jpg", fileNamed("https://upload.wikimedia.org/wikipedia/commons/a/a5/A_broken_%zz_link.jpg"))
        assertNull(fileNamed("Hokusai Great Wave"))
        assertNull(fileNamed("https://example.org/File:Tsunami.jpg"))
    }
}
