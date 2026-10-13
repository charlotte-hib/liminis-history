package art.liminis.curation

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
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.ListIndexJdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

// The workbench's schema curation (db/curation), mapped for one claim at a time, with the types of ClaimModel.kt. The
// JSON columns are read and written with Spring's JsonMapper (CurationJson).

/** A claim: [payload] is what would ship, [proposal] what was last proposed, before any edit. Its id: [ClaimRules.idOf]. */
@Entity
@Table(schema = "curation")
class Claim(
    @Id val id: String,
    @Enumerated(EnumType.STRING) val type: ClaimType,
    @Enumerated(EnumType.STRING) var status: ClaimStatus,
    @Enumerated(EnumType.STRING) val origin: Origin,
    @JdbcTypeCode(SqlTypes.JSON) var payload: Payload,
    @JdbcTypeCode(SqlTypes.JSON) var proposal: Payload,
    @JdbcTypeCode(SqlTypes.JSON) var flags: List<FlagReason>,
) {

    /** The order claims were proposed in, given by the database. */
    @Generated
    @Column(insertable = false, updatable = false)
    val seq: Long = 0

    /**
     * Raised by Hibernate with every change to the claim, and checked when it writes it: the claim's ETag. Null until
     * the claim is first stored, which tells Spring Data to insert it.
     */
    @Version
    val version: Long? = null

    /** Its status, payloads and flags together, as [ClaimRules] reads and changes them. */
    var state: ClaimState
        get() = ClaimState(status, payload, proposal, flags)
        set(value) {
            status = value.status
            payload = value.payload
            proposal = value.proposal
            flags = value.flags
        }

    /** What the claim rests on, in the order the sources were added. */
    @ElementCollection
    @CollectionTable(schema = "curation", name = "claim_source", joinColumns = [JoinColumn(name = "claim")])
    @OrderColumn(name = "position")
    @ListIndexJdbcTypeCode(SqlTypes.SMALLINT)
    val sources: MutableList<ClaimSource> = mutableListOf()

    companion object {
        /** A new claim waiting for review, its id and type from what it is about. */
        fun candidate(payload: Payload, origin: Origin, flags: List<FlagReason>) =
            Claim(ClaimRules.idOf(payload), ClaimRules.typeOf(payload), ClaimStatus.CANDIDATE, origin, payload, payload, flags)
    }
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
    @Enumerated(EnumType.STRING) val kind: SourceKind,
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
    @Enumerated(EnumType.STRING) @Column(name = "from_status") val from: ClaimStatus?,
    @Enumerated(EnumType.STRING) @Column(name = "to_status") val to: ClaimStatus,
    @JdbcTypeCode(SqlTypes.JSON) val payloadBefore: Payload?,
    @JdbcTypeCode(SqlTypes.JSON) val payloadAfter: Payload,
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
