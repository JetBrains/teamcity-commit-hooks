package org.jetbrains.teamcity.github.util

import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.serverSide.versionedSettings.VersionedSettingsManager
import jetbrains.buildServer.vcs.ChangesCheckingService
import jetbrains.buildServer.vcs.OperationRequestor
import jetbrains.buildServer.vcs.VcsRootInstance
import org.eclipse.egit.github.core.Repository

class WebHooksHelper(private val projectManager: ProjectManager,
                     private val versionedSettingsManager: VersionedSettingsManager,
                     private val securityContext: SecurityContext,
                     private val changesCheckingService: ChangesCheckingService) {

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
        val authorityHolder = securityContext.authorityHolder
        return prefilteredVcsRoots
            .flatMap {
                vcsRoot ->
                    vcsRoot.usagesInConfigurations
                        .filter { buildType -> authorityHolder.isPermissionGrantedForProject(buildType.projectId, Permission.VIEW_BUILD_CONFIGURATION_SETTINGS) }
                        .map { buildType -> buildType.getVcsRootInstanceForParent(vcsRoot) } +
                    versionedSettingsManager.getProjectsByOwnSettingsRoot(vcsRoot)
                        .filter { project -> authorityHolder.isPermissionGrantedForProject(project.projectId, Permission.VIEW_BUILD_CONFIGURATION_SETTINGS) }
                        .map { project -> versionedSettingsManager.getVersionedSettingsVcsRootInstance(project) }
            }.toSet().filterNotNull()
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
