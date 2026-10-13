package art.liminis.service

import org.junit.jupiter.api.Test
import art.liminis.domain.Domain
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.Person
import art.liminis.domain.Region
import art.liminis.web.ApiMapperImpl
import art.liminis.api.model.LifeLineKindDto
import kotlin.test.assertEquals

class LifeInTimeTests {

    private val rome = Region("IT", "Italy")
    private val republic = Era("it-republic", rome, "Roman Republic", "Roman Republic", -509, -27)
    private val empire = Era("it-empire", rome, "Roman Empire", "Roman Empire", -27, 476)
    private val augustus = Person("augustus", "Q1405", "augustus", "Augustus", -63, 14, false, rome, Domain.POWER, listOf("emperor"), 300, 100, null)
    private val mapper = ApiMapperImpl()

    @Test
    fun `ages skip the year 0 that never existed`() {
        val lines = lifeInTime(augustus, listOf(republic, empire), emptyList(), mapper::toDto)
        assertEquals(listOf(LifeLineKindDto.BIRTH, LifeLineKindDto.ERA, LifeLineKindDto.DEATH), lines.map { it.kind })
        assertEquals("Born under the Roman Republic", lines[0].text)
        assertEquals(36, lines[1].age)
        // 63 BCE to 14 CE is 76 years, not 77.
        assertEquals(76, lines[2].age)
    }

    @Test
    fun `their own events carry their role and others are capped`() {
        val own = Event("actium", empire, "Battle of Actium", -31, "", "").participant(augustus, "victor")
        val others = (1..6).map { Event("e$it", empire, "Event $it", -40 + it, "", "") }
        val lines = lifeInTime(augustus, emptyList(), others + own, mapper::toDto)
        assertEquals("victor", lines.single { it.kind == LifeLineKindDto.OWN_EVENT }.role)
        assertEquals(4, lines.count { it.kind == LifeLineKindDto.EVENT })
    }

    @Test
    fun `the birth and regime lines carry their era, the others none`() {
        val actium = Event("actium", empire, "Battle of Actium", -31, "", "")
        val lines = lifeInTime(augustus, listOf(republic, empire), listOf(actium), mapper::toDto)
        // Born -63, Actium -31, the Empire from -27, dies 14.
        assertEquals(listOf("it-republic", null, "it-empire", null), lines.map { it.era?.id })
        assertEquals("Roman Empire", lines.single { it.kind == LifeLineKindDto.ERA }.era?.label)
    }
}
