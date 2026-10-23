package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.runs.Runs
import art.liminis.workbench.api.RunsApi
import art.liminis.workbench.api.model.NewRunDto
import art.liminis.workbench.api.model.RunDto

@RestController
class RunController(private val runs: Runs) : RunsApi {

    override fun listRuns(): List<RunDto> = runs.all()

    override fun startRun(newRunDto: NewRunDto): RunDto = runs.start(newRunDto.kind, newRunDto.moment, newRunDto.record)

    override fun getRun(id: Long): RunDto = runs.find(id)
}
