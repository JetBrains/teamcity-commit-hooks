package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SimpleParameter
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase
import jetbrains.buildServer.serverSide.impl.ProjectEx
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.github.GHEOAuthProvider
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.serverSide.oauth.github.GitHubOAuthProvider
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.SVcsRootEx
import jetbrains.buildServer.vcs.VcsRoot
import org.assertj.core.api.BDDAssertions.then
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.util.*

class IntegrationUtilTest : BaseServerTestCase() {
    private var myOAuthConnectionsManager: OAuthConnectionsManager? = null

    @BeforeMethod(alwaysRun = true)
    override fun setUp() {
        super.setUp()
        myOAuthConnectionsManager = OAuthConnectionsManager(myServer)
    }

    @Test
    fun testSuitableParametrizedVcsRootDetected() {
        val project = createProject("P1")
        val vcs = addGitVcsRoot(project, "https://github.com/%owner%/%name%")
        val buildType = registerBuildType("BT1", project, "Ant")
        buildType.addVcsRoot(vcs)

        buildType.addParameter(SimpleParameter("owner", "A"))
        buildType.addParameter(SimpleParameter("name", "B"))


        val roots = getSuitableVcsRoots(project)
        then(roots).containsExactly(vcs)

        addGitHubConnection(project)

        val suitable = getVcsRootsWhereHookCanBeInstalled(project)
        then(suitable).containsExactlyElementsOf(buildType.vcsRootInstances)
    }

    @Test
    fun testSuitableVcsRootDetected() {
        val project = createProject("P1")
        val vcs = addGitVcsRoot(project, "https://github.com/Owner/Name")
        val buildType = registerBuildType("BT1", project, "Ant")
        buildType.addVcsRoot(vcs)

        val roots = getSuitableVcsRoots(project)
        then(roots).containsExactly(vcs)

        addGitHubConnection(project)

        val suitable = getVcsRootsWhereHookCanBeInstalled(project)
        then(suitable).containsExactly(vcs)
    }

    private fun getSuitableVcsRoots(project: ProjectEx): ArrayList<SVcsRoot> {
        val roots = ArrayList<SVcsRoot>()
        Util.findSuitableRoots(project, true) {
            roots.add(it)
            true
        }
        return roots
    }

    private fun getVcsRootsWhereHookCanBeInstalled(project: ProjectEx): List<VcsRoot> {
        return Util.getVcsRootsWhereHookCanBeInstalled(project, myOAuthConnectionsManager!!, false)
    }

    private fun addGitHubConnection(project: ProjectEx): OAuthConnectionDescriptor {
        return myOAuthConnectionsManager!!.addConnection(project, GitHubOAuthProvider.TYPE, mapOf(
                GitHubConstants.CLIENT_ID_PARAM to "CID",
                GitHubConstants.CLIENT_SECRET_PARAM to "CS"
        ))
    }

    private fun addGHEConnection(project: ProjectEx, url: String): OAuthConnectionDescriptor {
        return myOAuthConnectionsManager!!.addConnection(project, GHEOAuthProvider.TYPE, mapOf(
                GitHubConstants.GITHUB_URL_PARAM to url,
                GitHubConstants.CLIENT_ID_PARAM to "CID",
                GitHubConstants.CLIENT_SECRET_PARAM to "CS"
        ))
    }

    private fun addGitVcsRoot(project: ProjectEx?, url: String): SVcsRootEx {
        val vcs = createVcsRoot(Constants.VCS_NAME_GIT, project)
        vcs.properties = mapOf(Constants.VCS_PROPERTY_GIT_URL to url)
        return vcs
    }
}