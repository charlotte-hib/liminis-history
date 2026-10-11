package dev.wholivedwhen.curation

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.ListIndexJdbcTypeCode
import org.hibernate.type.SqlTypes
import dev.wholivedwhen.workbench.api.model.ClaimStatusDto
import dev.wholivedwhen.workbench.api.model.ClaimTypeDto
import dev.wholivedwhen.workbench.api.model.FlagReasonDto
import dev.wholivedwhen.workbench.api.model.OriginDto
import dev.wholivedwhen.workbench.api.model.PayloadDto
import dev.wholivedwhen.workbench.api.model.SourceDto
import java.time.OffsetDateTime

// The workbench's schema curation (db/curation), mapped for one claim at a time. The JSON columns are read and written
// with Spring's JsonMapper (CurationSchema), as the API reads and writes them.

/** A claim: [payload] is what would ship, [proposal] what was last proposed, before any edit. Its id: [Claims.idOf]. */
@Entity
@Table(schema = "curation")
class Claim(
    @Id val id: String,
    @Enumerated(EnumType.STRING) val type: ClaimTypeDto,
    @Enumerated(EnumType.STRING) var status: ClaimStatusDto,
    @Enumerated(EnumType.STRING) val origin: OriginDto,
    @JdbcTypeCode(SqlTypes.JSON) var payload: PayloadDto,
    @JdbcTypeCode(SqlTypes.JSON) var proposal: PayloadDto,
    @JdbcTypeCode(SqlTypes.JSON) var flags: List<FlagReasonDto>,
) {

    /** The order claims were proposed in, given by the database. */
    @Generated
    @Column(insertable = false, updatable = false)
    val seq: Long = 0

    /** What the claim rests on, in the order the sources were added. */
    @ElementCollection
    @CollectionTable(schema = "curation", name = "claim_source", joinColumns = [JoinColumn(name = "claim")])
    @OrderColumn(name = "position")
    @ListIndexJdbcTypeCode(SqlTypes.SMALLINT)
    val sources: MutableList<ClaimSource> = mutableListOf()
}

/** A source a claim rests on, with the passage it rests on. A book's quotes never leave the workbench. */
@Embeddable
class ClaimSource(
    @ManyToOne @JoinColumn(name = "source") val source: Source,
    val quote: String?,
)

/** A Wikidata entity, a Wikipedia article, a book's page, a web page, a podcast's minute: the same one supports several claims. */
@Entity
@Table(schema = "curation")
class Source(
    @Enumerated(EnumType.STRING) val kind: SourceDto.Kind,
    /** Its fields by kind (entity, revision, language, title, page, episode, minute, url), without the quote. */
    @JdbcTypeCode(SqlTypes.JSON) val locator: Map<String, Any>,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
}

/**
 * A decision: who moved a claim from one status and payload to another, and why. Never changed once logged.
 * [undoes] is the decision it reverts.
 */
@Entity
@Table(schema = "curation")
class Decision(
    val claim: String,
    @Column(name = "decided_by") val by: String,
    @Enumerated(EnumType.STRING) @Column(name = "from_status") val from: ClaimStatusDto?,
    @Enumerated(EnumType.STRING) @Column(name = "to_status") val to: ClaimStatusDto,
    @JdbcTypeCode(SqlTypes.JSON) val payloadBefore: PayloadDto?,
    @JdbcTypeCode(SqlTypes.JSON) val payloadAfter: PayloadDto,
    val note: String? = null,
    val undoes: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Generated
    @Column(insertable = false, updatable = false)
    val decidedAt: OffsetDateTime? = null
}
