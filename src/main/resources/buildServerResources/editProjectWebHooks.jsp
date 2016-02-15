<%@include file="/include-internal.jsp" %>
<%@ taglib prefix="admin" tagdir="/WEB-INF/tags/admin" %>
<%@ taglib prefix="admfn" uri="/WEB-INF/functions/admin" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="webHooksBean" type="org.jetbrains.teamcity.github.controllers.ProjectWebHooksBean" scope="request"/>

<bs:linkScript>
    /js/bs/systemProblemsMonitor.js
</bs:linkScript>
<%--@elvariable id="currentProject" type="jetbrains.buildServer.serverSide.SProject"--%>

<div class="editProjectPage">
    <c:set value="<%=jetbrains.buildServer.serverSide.systemProblems.StandardSystemProblemTypes.VCS_CONFIGURATION%>" var="problemType"/>
    <c:set var="cameFromUrl" value="${pageContext.request.requestURL.toString()}"/>
    <div id="roots" class="selection noMargin">
        <h2 class="noBorder">Git VCS Roots</h2>
        <l:tableWithHighlighting className="parametersTable" id="projectWebHooks" highlightImmediately="true">
            <tr>
                <th colspan="2">Name</th>
                <th>Url</th>
                <th>WebHook Status</th>
            </tr>
            <c:forEach items="${webHooksBean.roots}" var="entry">
                <%--@elvariable id="vcsRoot" type="jetbrains.buildServer.vcs.SVcsRoot"--%>
                <c:set var="vcsRoot" value="${entry.key}"/>
                <%--@elvariable id="webhook" type="org.jetbrains.teamcity.github.controllers.WebHooksStatus"--%>
                <c:set var="webhook" value="${entry.value}"/>
                <tr>
                    <td colspan="2" class="">
                        <span class="vcsRoot"><admin:vcsRootName editingScope="" cameFromUrl="${cameFromUrl}" vcsRoot="${vcsRoot}"/></span>
                        <c:if test="${vcsRoot.project.projectId != currentProject.projectId}">
                            belongs to <admin:projectName project="${vcsRoot.project}"/>
                        </c:if>
                        <div class="clearfix"></div>
                    </td>
                    <td>${vcsRoot.properties["url"]}</td>
                    <td>${webhook.status}</td>
                </tr>
            </c:forEach>
        </l:tableWithHighlighting>
    </div>

    <div id="instances" class="selection noMargin">
        <h2 class="noBorder">Git VCS Root Instances (in case of parametrized url)</h2>

        <l:tableWithHighlighting className="parametersTable" id="projectWebHooks" highlightImmediately="true">
            <tr>
                <th colspan="2">Name</th>
                <th>Url</th>
                <th>WebHook Status</th>
            </tr>
            <c:forEach items="${webHooksBean.instances}" var="entry">
                <%--@elvariable id="vcsRoot" type="jetbrains.buildServer.vcs.SVcsRoot"--%>
                <c:set var="vcsRoot" value="${entry.key}"/>
                <%--@elvariable id="map" type="java.util.Map<jetbrains.buildServer.vcs.VcsRootInstance,org.jetbrains.teamcity.github.controllers.WebHooksStatus>"--%>
                <c:set var="map" value="${entry.value}"/>
                <tr>
                    <td colspan="2" class="">
                        <span class="vcsRoot"><admin:vcsRootName editingScope="" cameFromUrl="${cameFromUrl}" vcsRoot="${vcsRoot}"/></span>
                        <c:if test="${vcsRoot.project.projectId != currentProject.projectId}">
                            belongs to <admin:projectName project="${vcsRoot.project}"/>
                        </c:if>
                        <div class="clearfix"></div>
                    </td>
                    <td>${vcsRoot.properties["url"]}</td>
                    <td></td>
                </tr>
                <c:forEach var="entry2" items="${map}">
                    <%--@elvariable id="instance" type="jetbrains.buildServer.vcs.VcsRootInstance"--%>
                    <c:set var="instance" value="${entry2.key}"/>
                    <%--@elvariable id="webhook" type="org.jetbrains.teamcity.github.controllers.WebHooksStatus"--%>
                    <c:set var="webhook" value="${entry2.value}"/>
                    <tr>
                        <td></td>
                        <td>${instance.name} in ${fn:length(instance.usages)} configuration<bs:s val="${fn:length(instance.usages)}"/></td>
                        <td>${instance.properties["url"]}</td>
                        <td>${webhook.status}</td>
                    </tr>
                </c:forEach>
            </c:forEach>
        </l:tableWithHighlighting>
    </div>
</div>