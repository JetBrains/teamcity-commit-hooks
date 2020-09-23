package org.jetbrains.teamcity.github

import jetbrains.buildServer.BuildType
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SimpleParameter
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase
import jetbrains.buildServer.serverSide.impl.ProjectEx
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.github.GHEOAuthProvider
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.serverSide.oauth.github.GitHubOAuthProvider
import jetbrains.buildServer.vcs.SVcsRootEx
import org.assertj.core.api.BDDAssertions.then
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

class GitHubWebHookSuggestionTest : BaseServerTestCase() {
    private var myOAuthConnectionsManager: OAuthConnectionsManager? = null

    @BeforeMethod(alwaysRun = true)
    override fun setUp() {
        super.setUp()
        myOAuthConnectionsManager = OAuthConnectionsManager(myServer, myWebLinks)
    }

    @Test
    fun testSimple() {
        val p1 = myFixture.createProject("P1")
        val bt1 = registerBuildType("BT1", p1, "Ant")
        val vcs = addGitVcsRoot(p1, "https://github.com/A/B")

        bt1.addVcsRoot(vcs)

        addGitHubConnection(p1)

        val consumer = report(p1.buildTypes)

        then(consumer.project[p1]).hasSize(1)
        then(consumer.vcsRoot[vcs]).hasSize(1)
        then(consumer.buildType[bt1]).hasSize(1)

        then(consumer.all).hasSize(1)
        then(consumer.all.first().additionalData["Project"]).isEqualTo(p1)
    }

    @Test
    fun testSimpleParametrizedRoot() {
        val p1 = myFixture.createProject("P1")
        val bt1 = registerBuildType("BT1", p1, "Ant")
        val vcs = addGitVcsRoot(p1, "https://github.com/%owner%/%name%")

        bt1.addVcsRoot(vcs)

        bt1.addParameter(SimpleParameter("owner", "A"))
        bt1.addParameter(SimpleParameter("name", "B"))

        addGitHubConnection(p1)

        val consumer = report(p1.buildTypes)

        then(consumer.project[p1]).hasSize(1)
        then(consumer.vcsRoot[vcs]).hasSize(1)
        then(consumer.buildType[bt1]).hasSize(1)

        then(consumer.all).hasSize(1)
        then(consumer.all.first().additionalData["Project"]).isEqualTo(p1)
    }

    @Test
    fun testVcsRootParametrizedInSubprojectShouldNotBeShownInParentProject() {
        val p1 = myFixture.createProject("P1")
        val p2 = myFixture.createProject("P2", p1)

        val bt2 = registerBuildType("BT2", p2, "Ant")

        val vcs = addGitVcsRoot(p1, "https://github.com/%owner%/%name%")

        bt2.addVcsRoot(vcs)
        bt2.addParameter(SimpleParameter("owner", "A"))
        bt2.addParameter(SimpleParameter("name", "B"))

        addGitHubConnection(p2)


        val consumer = report(p1.buildTypes)

        then(consumer.project.keys).containsOnly(p2)
        then(consumer.project[p2]).hasSize(1)

        then(consumer.vcsRoot).isEmpty() // Vcs Root belongs to parent project which does not have connection

        then(consumer.buildType.keys).containsOnly(bt2)
        then(consumer.buildType[bt2]).hasSize(1)

        then(consumer.all).hasSize(1)
        then(consumer.all.first().additionalData["Project"]).isEqualTo(p2)
    }

    @Test
    fun testVcsRootsParametrizedInSubprojectShouldNotBeShownInParentProject() {
        val p1 = myFixture.createProject("P1")
        val p2 = myFixture.createProject("P2", p1)
        val p3 = myFixture.createProject("P3", p1)

        val bt2 = registerBuildType("BT2", p2, "Ant")
        val bt3 = registerBuildType("BT2", p3, "Ant")

        val vcs = addGitVcsRoot(p1, "https://github.com/%owner%/%name%")

        bt2.addVcsRoot(vcs)
        bt2.addParameter(SimpleParameter("owner", "A"))
        bt2.addParameter(SimpleParameter("name", "B"))

        bt3.addVcsRoot(vcs)
        bt3.addParameter(SimpleParameter("owner", "A"))
        bt3.addParameter(SimpleParameter("name", "B"))

        addGitHubConnection(p2)
        addGitHubConnection(p3)

        for (consumer in listOf(report(p1.buildTypes),
                                report(p2.buildTypes),
                                report(p3.buildTypes))) {
            then(consumer.project.keys).hasSize(1)
            then(p1.projects).containsAll(consumer.project.keys)
            then(consumer.project.values.flatten()).hasSize(1)

            then(consumer.vcsRoot).isEmpty() // Vcs Root belongs to parent project which does not have connection

            then(consumer.buildType.keys).hasSize(1)
            then(listOf<BuildType>(bt2, bt3)).containsAll(consumer.buildType.keys)
            then(consumer.buildType.values.flatten()).hasSize(1)

            then(consumer.all).hasSize(1)
            then(consumer.all.map { it.additionalData["Project"] }).doesNotContain(p1).hasSize(1)
        }

        val consumer = report(p2.buildTypes).merge(report(p3.buildTypes))
        then(consumer.project.keys).containsOnlyElementsOf(p1.projects).hasSize(2)
        then(consumer.project.values.flatten().toSet()).hasSize(1)
        then(consumer.project.values.flatten()).hasSize(2)

        then(consumer.vcsRoot).isEmpty() // Vcs Root belongs to parent project which does not have connection

        then(consumer.buildType.keys).containsOnly(bt2, bt3)
        then(consumer.buildType.values.flatten().toSet()).hasSize(1)
        then(consumer.buildType.values.flatten()).hasSize(2)

        then(consumer.all).hasSize(1)
        then(consumer.all.map { it.additionalData["Project"] }).doesNotContain(p1).hasSize(1)
    }

    @Test
    fun testHealthItemShouldNotBeShownTwice() {
        val p1 = myFixture.createProject("P1")
        val p2 = myFixture.createProject("P2", p1)

        registerBuildType("BT1", p1, "Ant")

        val bt21 = registerBuildType("BT21", p2, "Ant")
        val bt22 = registerBuildType("BT22", p2, "Ant")

        val vcs = addGitVcsRoot(p1, "https://github.com/%owner%/%name%")

        p2.addParameter(SimpleParameter("owner", "A"))
        p2.addParameter(SimpleParameter("name", "B"))

        bt21.addVcsRoot(vcs)
        bt22.addVcsRoot(vcs)

        addGitHubConnection(p2)

        val allBTs = p1.buildTypes

        then(Util.getVcsRootsWhereHookCanBeInstalled(allBTs, myOAuthConnectionsManager!!).map { it.second }.toSet()).containsExactlyElementsOf((bt21.vcsRootInstances + bt22.vcsRootInstances).toSet())

        val consumer = report(allBTs)

        then(consumer.global).isEmpty()
        then(consumer.project.keys).containsOnly(p2)
        then(consumer.project[p2]).hasSize(1)

        then(consumer.vcsRoot).isEmpty() // Vcs Root belongs to parent project which does not have connection

        then(consumer.buildType.keys).containsOnly(bt21, bt22)
        then(consumer.buildType[bt21]).hasSize(1)
        then(consumer.buildType[bt22]!! + consumer.buildType[bt21]!!).hasSize(1)
    }


    private fun report(bts: List<SBuildType>): MockHealthStatusItemConsumer {
        val consumer = MockHealthStatusItemConsumer()
        GitHubWebHookSuggestion.report(bts, consumer, myOAuthConnectionsManager!!) { false }
        return consumer
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