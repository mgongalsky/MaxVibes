package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result

/**
 * Port for collecting compilation/analysis errors directly from the IDE.
 */
interface IdeErrorsPort {
    /**
     * Returns compiler/daemon errors collected from currently open files.
     */
    suspend fun getCompilerErrors(): Result<List<IdeError>, Exception>

    /**
     * Returns ERROR-severity problems in the given project-relative files, waiting for
     * the code-analysis daemon to settle first: initial delay, then polling until two
     * consecutive snapshots are identical, capped by [timeoutMs].
     *
     * Files that are not currently open are loaded for analysis on a best-effort basis.
     */
    suspend fun getErrorsForFiles(
        relativePaths: List<String>,
        timeoutMs: Long = 12_000
    ): Result<List<IdeError>, Exception>
}
