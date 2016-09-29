package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.BuildTypeTemplate
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem
import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItemConsumer
import jetbrains.buildServer.vcs.SVcsRoot
import java.util.*

class MockHealthStatusItemConsumer : HealthStatusItemConsumer {
    val all = LinkedHashSet<HealthStatusItem>()

    val global = LinkedHashSet<HealthStatusItem>()
    val buildType = LinkedHashMap<SBuildType, MutableSet<HealthStatusItem>>()
    val template = LinkedHashMap<BuildTypeTemplate, MutableSet<HealthStatusItem>>()
    val project = LinkedHashMap<SProject, MutableSet<HealthStatusItem>>()
    val vcsRoot = LinkedHashMap<SVcsRoot, MutableSet<HealthStatusItem>>()


    override fun consumeGlobal(p0: HealthStatusItem) {
        global.add(p0)
        all.add(p0)
    }

    override fun consumeForBuildType(p0: SBuildType, p1: HealthStatusItem) {
        (buildType.getOrPut(p0) { HashSet<HealthStatusItem>() }).add(p1)
        all.add(p1)
    }

    override fun consumeForTemplate(p0: BuildTypeTemplate, p1: HealthStatusItem) {
        (template.getOrPut(p0) { HashSet<HealthStatusItem>() }).add(p1)
        all.add(p1)
    }

    override fun consumeForProject(p0: SProject, p1: HealthStatusItem) {
        (project.getOrPut(p0) { HashSet<HealthStatusItem>() }).add(p1)
        all.add(p1)
    }

    override fun consumeForVcsRoot(p0: SVcsRoot, p1: HealthStatusItem) {
        (vcsRoot.getOrPut(p0) { HashSet<HealthStatusItem>() }).add(p1)
        all.add(p1)
    }

    public fun merge(other: MockHealthStatusItemConsumer): MockHealthStatusItemConsumer {
        this.all.addAll(other.all);
        this.global.addAll(other.global);
        other.buildType.forEach { bt, set -> this.buildType.getOrPut(bt) { HashSet<HealthStatusItem>() }.addAll(set) }
        other.template.forEach { bt, set -> this.template.getOrPut(bt) { HashSet<HealthStatusItem>() }.addAll(set) }
        other.project.forEach { bt, set -> this.project.getOrPut(bt) { HashSet<HealthStatusItem>() }.addAll(set) }
        other.vcsRoot.forEach { bt, set -> this.vcsRoot.getOrPut(bt) { HashSet<HealthStatusItem>() }.addAll(set) }
        return this
    }

}