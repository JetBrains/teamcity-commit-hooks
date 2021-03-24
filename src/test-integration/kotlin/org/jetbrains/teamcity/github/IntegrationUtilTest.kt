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
import jetbrains.buildServer.vcs.VcsRootInstance
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
        then(suitable).containsExactlyElementsOf(buildType.vcsRootInstances)
    }

    @Test
    fun testSuitableVcsRootIsDetectedFromSubproject() {
        val p1 = myFixture.createProject("P1")
        val p2 = myFixture.createProject("P2", p1)

        val bt1 = registerBuildType("BT1", p1, "Ant")
        val bt2 = registerBuildType("BT2", p2, "Ant")

        val vcs = addGitVcsRoot(p1, "https://github.com/Owner/Name")

        bt1.addVcsRoot(vcs)
        bt2.addVcsRoot(vcs)

        then(getSuitableVcsRoots(p1)).containsExactly(vcs)
        then(getSuitableVcsRoots(p2)).containsExactly(vcs)

        // Note: there's no connection in P1
        addGitHubConnection(p2)

        then(getVcsRootsWhereHookCanBeInstalled(p2)).containsExactlyElementsOf(bt2.vcsRootInstances)

        then(getVcsRootsWhereHookCanBeInstalled(p1)).isEmpty()
    }

    @Test
    fun testGetOAuthServers() {
        val p1 = myFixture.createProject("P1")
        val p2 = myFixture.createProject("P2", p1)
        var servers: Set<String>

        servers = Util.getOAuthServers(p1, myOAuthConnectionsManager!!)
        then(servers).isEmpty()
        servers = Util.getOAuthServers(p2, myOAuthConnectionsManager!!)
        then(servers).isEmpty()

        addGitHubConnection(p1)
        servers = Util.getOAuthServers(p1, myOAuthConnectionsManager!!)
        then(servers).containsOnly("github.com")
        servers = Util.getOAuthServers(p2, myOAuthConnectionsManager!!)
        then(servers).containsOnly("github.com")

        addGHEConnection(p2, "https://teamcity-github-enterprise.labs.intellij.net/")
        servers = Util.getOAuthServers(p1, myOAuthConnectionsManager!!)
        then(servers).containsOnly("github.com")
        servers = Util.getOAuthServers(p2, myOAuthConnectionsManager!!)
        then(servers).containsOnly("github.com", "teamcity-github-enterprise.labs.intellij.net")

    }

    @Test
    fun testParametrizedRootInstanceDetectedProperly() {
        val p1 = myFixture.createProject("P1")
        val bt1 = registerBuildType("BT1", p1, "Ant")

        val vcs = addGitVcsRoot(p1, "https://github.com/%owner%/%name%")
        bt1.addVcsRoot(vcs)
        bt1.addParameter(SimpleParameter("owner", "A"))
        bt1.addParameter(SimpleParameter("name", "B"))

        addGitHubConnection(p1)

        val list = Util.getVcsRootsWhereHookCanBeInstalled(listOf(bt1), myOAuthConnectionsManager!!).map { it.second }
        then(list).containsExactlyElementsOf(bt1.vcsRootInstances)
    }

    private fun getSuitableVcsRoots(project: ProjectEx): Set<SVcsRoot> {
        val roots = LinkedHashSet<SVcsRoot>()
        Util.findSuitableRoots(project, true) {
            roots.add(it)
            true
        }
        return roots
    }

    @Test
    fun testRootProjectFullName() {
        val p1 = createProject("P1")
        println(p1.fullName)
        println(p1.parentProject!!.fullName)
    }

    private fun getVcsRootsWhereHookCanBeInstalled(project: ProjectEx): List<VcsRootInstance> {
        return Util.getVcsRootsWhereHookCanBeInstalled(project, myOAuthConnectionsManager!!)
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