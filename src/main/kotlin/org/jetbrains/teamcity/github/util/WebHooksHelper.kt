package org.jetbrains.teamcity.github.util

import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.vcs.ChangesCheckingService
import jetbrains.buildServer.vcs.OperationRequestor
import jetbrains.buildServer.vcs.VcsRootInstance
import org.eclipse.egit.github.core.Repository

class WebHooksHelper(private val projectManager: ProjectManager, private val changesCheckingService: ChangesCheckingService) {

    fun findRelevantVcsRootInstances(repository: Repository): Collection<VcsRootInstance> {
        if (repository.cloneUrl.isNullOrEmpty())
            return emptyList()

        val repoUrls = setOf(repository.gitUrl, repository.cloneUrl, repository.sshUrl).filterNotNull().map { normalizeGitUrl(it) }

        val prefilteredVcsRoots = projectManager.allVcsRoots
            .filter {vcsRoot ->
                isParamRefOrMatches(vcsRoot.vcsName) {
                    it == "jetbrains.git"
                }
                && isParamRefOrMatches(normalizeGitUrl(vcsRoot.getProperty("url"))) {
                    repoUrls.contains(it)
                }
            }
        return prefilteredVcsRoots
            .flatMap { it.usagesInConfigurations.map { buildType -> buildType.getVcsRootInstanceForParent(it)} }
            .toSet().filterNotNull()
            .filter { repoUrls.contains(normalizeGitUrl(it.getProperty("url"))) }
    }

    private fun isParamRefOrMatches(value: String?, condition: (String) -> Boolean): Boolean = value != null && (value.contains("%") || condition(value))

    fun checkForChanges(vcsRoots: Collection<VcsRootInstance>) {
        changesCheckingService.forceCheckingFor(vcsRoots, OperationRequestor.COMMIT_HOOK)
    }

    private fun normalizeGitUrl(url: String?) = url?.let {
        removePrefix(removePrefix(url, "://"), "@")
            .removeSuffix(".git").lowercase()
    }

    private fun removePrefix(url: String, prefixEnding: String): String {
        val idx = url.indexOf(prefixEnding)
        return if (idx < 0) url else url.substring(idx + prefixEnding.length)
    }

}
