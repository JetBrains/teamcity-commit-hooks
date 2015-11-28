package org.jetbrains.teamcity.github

import org.testng.Assert
import org.testng.annotations.Test


class UtilTest {
    @Test
    fun testUrlParsing() {
        doSuccessUrlParsingTest("https://github.com/VladRassokhin/intellij-hcl.git", "github.com", "VladRassokhin", "intellij-hcl")
        doSuccessUrlParsingTest("git@github.com:VladRassokhin/intellij-hcl.git", "github.com", "VladRassokhin", "intellij-hcl")

        doSuccessUrlParsingTest("git@teamcity-github-enterprise:test/name.git", "teamcity-github-enterprise", "test", "name")
        doSuccessUrlParsingTest("git@teamcity-github-enterprise.labs.intellij.net:test/name.git", "teamcity-github-enterprise.labs.intellij.net", "test", "name")

        doSuccessUrlParsingTest("git@teamcity-github-enterprise:1test1Z.-_-./1name1Z.-_-..git", "teamcity-github-enterprise", "1test1Z.-_-.", "1name1Z.-_-.")

        doSuccessUrlParsingTest("http://teamcity-github-enterprise.labs.intellij.net/Vlad/test-repo-1", "teamcity-github-enterprise.labs.intellij.net", "Vlad", "test-repo-1")
        doSuccessUrlParsingTest("git@teamcity-github-enterprise.labs.intellij.net:Vlad/test-repo-1.git", "teamcity-github-enterprise.labs.intellij.net", "Vlad", "test-repo-1")
    }

    private fun doSuccessUrlParsingTest(url: String, server: String, owner: String, name: String) {
        val info = Util.parseGitRepoUrl(url)
        Assert.assertNotNull(info)
        info!!
        Assert.assertEquals(info.server, server)
        Assert.assertEquals(info.owner, owner)
        Assert.assertEquals(info.name, name)
    }

    private fun doFailedUrlParsingTest(url: String) {
        val info = Util.parseGitRepoUrl(url)
        Assert.assertNull(info)
    }
}