/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

        doSuccessUrlParsingTest("https://teamcity-github-enterprise.labs.intellij.net/Vlad/test-repo-1", "teamcity-github-enterprise.labs.intellij.net", "Vlad", "test-repo-1")
        doSuccessUrlParsingTest("git@teamcity-github-enterprise.labs.intellij.net:Vlad/test-repo-1.git", "teamcity-github-enterprise.labs.intellij.net", "Vlad", "test-repo-1")

        doSuccessUrlParsingTest("github.com/VladRassokhin/intellij-hcl", "github.com", "VladRassokhin", "intellij-hcl")
        doSuccessUrlParsingTest("github.com/VladRassokhin/intellij-hcl.git", "github.com", "VladRassokhin", "intellij-hcl")

        // Even non-GitHub sites could match
        doSuccessUrlParsingTest("git://git.csync.org/projects/csync.git", "git.csync.org", "projects", "csync")
        doSuccessUrlParsingTest("git://git.libssh.org/projects/libssh.git", "git.libssh.org", "projects", "libssh")
    }

    @Test
    fun testUrlsShouldNotParse() {
        doFailedUrlParsingTest("/media/devel/repositories/git-utf-8")
        doFailedUrlParsingTest("https://git-wip-us.apache.org/repos/asf/ant.git ")
    }

    @Test
    fun testIsSameUrl() {
        val GHE = "https://teamcity-github-enterprise.labs.intellij.net"
        val GH = "https://github.com"

        doTestSameUrl(GHE, GHE, true)
        doTestSameUrl(GH, GH, true)
        doTestSameUrl(GH, "github.com", true)
        doTestSameUrl("github.com", GH, true)
        doTestSameUrl("github.com", "github.com", true)

        doTestSameUrl(GHE, "teamcity-github-enterprise.labs.intellij.net", true)

        doTestSameUrl(GH, "", false)
        doTestSameUrl(GHE, "", false)

        doTestSameUrl(GH, "a", false)
        doTestSameUrl(GHE, "a", false)

        doTestSameUrl(GH, "git", false)
        doTestSameUrl(GHE, "git", false)

        doTestSameUrl(GH, "abra:cadab:ra", false)
        doTestSameUrl(GHE, "olo\b\nlo", false)
    }

    private fun doTestSameUrl(github: String, input: String, expected: Boolean) {
        Assert.assertEquals(Util.isSameUrl(input, github), expected)
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