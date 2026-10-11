package dev.wholivedwhen.runs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import dev.wholivedwhen.curation.PersonFactsProposals
import dev.wholivedwhen.dataset.DatasetExport
import dev.wholivedwhen.dataset.DatasetMapping
import dev.wholivedwhen.drafting.StoryDrafting
import dev.wholivedwhen.repository.MomentRepository
import dev.wholivedwhen.support.ConflictException
import dev.wholivedwhen.support.InvalidRequestException
import dev.wholivedwhen.support.NotFoundException
import dev.wholivedwhen.wikidata.WikidataImport
import dev.wholivedwhen.workbench.api.model.RunDto
import dev.wholivedwhen.workbench.api.model.RunKindDto
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs imports, mappings, exports, proposals and drafts in the background, one at a time: they share Wikimedia's pace and the
 * dataset. Kept in memory: a run stopped by a restart is started again by hand, and an import resumes where it was.
 */
@Service
class Runs(
    private val wikidataImport: WikidataImport,
    private val datasetMapping: DatasetMapping,
    private val datasetExport: DatasetExport,
    private val personFacts: PersonFactsProposals,
    private val drafting: StoryDrafting,
    private val moments: MomentRepository,
) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private val runs = ConcurrentHashMap<Long, RunDto>()
    private val ids = AtomicLong()
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("run-", 1).factory())

    fun all(): List<RunDto> = runs.values.sortedByDescending { it.id }

    fun find(id: Long): RunDto = runs[id] ?: throw NotFoundException("No run $id")

    @Synchronized
    fun start(kind: RunKindDto, moment: String?): RunDto {
        if (kind == RunKindDto.STORY_DRAFT && (moment == null || !moments.existsById(moment))) {
            throw InvalidRequestException("A story draft needs a moment of the release")
        }
        runs.values.firstOrNull { it.status == RunDto.Status.RUNNING }?.let {
            throw ConflictException("Run ${it.id} (${it.kind.value}) is still running")
        }
        val run = RunDto(
            id = ids.incrementAndGet(), kind = kind, status = RunDto.Status.RUNNING, startedAt = now(),
            moment = moment.takeIf { kind == RunKindDto.STORY_DRAFT },
        )
        runs[run.id] = run
        executor.execute { finish(run) }
        return run
    }

    private fun finish(run: RunDto) {
        log.info("Run {}: {} started", run.id, run.kind.value)
        runs[run.id] = try {
            val result = work(run)
            log.info("Run {}: {}", run.id, result)
            run.copy(status = RunDto.Status.SUCCEEDED, endedAt = now(), result = result)
        } catch (e: Exception) {
            log.error("Run {} failed", run.id, e)
            run.copy(status = RunDto.Status.FAILED, endedAt = now(), result = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun work(run: RunDto): String = when (run.kind) {
        RunKindDto.WIKIDATA_IMPORT -> wikidataImport.run().let { "Import ${it.id} finished" }
        RunKindDto.DATASET_MAPPING -> datasetMapping.map().toString()
        RunKindDto.DATASET_EXPORT -> datasetExport.export().let { (dir, manifest) -> "${manifest.counts} of import ${manifest.import.run}, in $dir" }
        RunKindDto.PERSON_FACTS_CLAIMS -> personFacts.propose().toString()
        RunKindDto.STORY_DRAFT -> drafting.draft(run.moment!!)
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)
}
