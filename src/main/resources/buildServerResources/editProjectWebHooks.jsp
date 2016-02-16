<%@include file="/include-internal.jsp" %>
<%@ taglib prefix="admin" tagdir="/WEB-INF/tags/admin" %>
<%@ taglib prefix="admfn" uri="/WEB-INF/functions/admin" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<jsp:useBean id="webHooksBean" type="org.jetbrains.teamcity.github.controllers.ProjectWebHooksBean" scope="request"/>

<bs:linkScript>
    /js/bs/systemProblemsMonitor.js
    ${pluginResourcesPath}gh-webhook.js
</bs:linkScript>
<%--@elvariable id="currentProject" type="jetbrains.buildServer.serverSide.SProject"--%>

<div class="editProjectPage">
    <c:set value="<%=jetbrains.buildServer.serverSide.systemProblems.StandardSystemProblemTypes.VCS_CONFIGURATION%>" var="problemType"/>
    <c:set var="cameFromUrl" value="${param['cameFromUrl']}"/>
    <div id="webHooksTable" class="selection noMargin">
        <l:tableWithHighlighting className="parametersTable" id="projectVcsRoots" highlightImmediately="true">
            <tr>
                <th colspan="2">Name</th>
                <th>Url</th>
                <th>WebHook Status</th>
            </tr>
            <c:if test="${not empty webHooksBean.roots}">
                <tr>
                    <th colspan="4"><b>Git VCS Root<bs:s val="${fn:length(webHooksBean.roots)}"/></b></th>
                </tr>
            </c:if>
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
            <c:if test="${not empty webHooksBean.instances}">
                <tr>
                    <th colspan="4"><b>Git VCS Root<bs:s val="${fn:length(webHooksBean.instances)}"/> with parametrized url</b></th>
                </tr>
            </c:if>
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
                    <%--@elvariable id="usages" type="org.jetbrains.teamcity.github.controllers.VcsRootInstanceUsagesBean"--%>
                    <c:set var="usages" value="${webHooksBean.usages[instance]}"/>
                    <tr>
                        <td style="border-right: none"></td>
                        <td>
                                ${instance.name} parametrized in
                            <c:choose>
                                <c:when test="${usages== null || usages.total == 0}"><span title="This VCS root is not used">no usages</span></c:when>
                                <c:otherwise>
                                    <a href="#" onclick="return BS.AdminActions.toggleVcsRootInstanceUsages(this, '${instance.id}');"><c:out default="" value="${usages.total}"/>
                                        usage<bs:s
                                                val="${usages.total}"/></a>
                                </c:otherwise>
                            </c:choose>
                        </td>
                        <td>${instance.properties["url"]}</td>
                        <td>${webhook.status}</td>
                    </tr>
                    <c:if test="${usages != null && usages.total > 0}">
                        <%-- Root Instance Usages --%>
                        <tr id="instance_${instance.id}_usages" class="usages usageHl" style="display: none">
                            <td style="border-right: none"></td>
                            <td colspan="3">
                                <c:if test="${not empty usages.templates}">
                                    <div class="templateUsages">
                                        <div>
                                            Used in <b>${fn:length(usages.templates)}</b> template<bs:s val="${fn:length(usages.templates)}"/>:
                                        </div>
                                        <ul>
                                            <c:forEach items="${usages.templates}" var="btSettings" varStatus="pos">
                                                <li>
                                                    <c:set var="canEdit" value="${afn:permissionGrantedForProject(btSettings.project, 'EDIT_PROJECT')}"/>
                                                    <c:choose>
                                                        <c:when test="${canEdit}">
                                                            <admin:editTemplateLink step="vcsRoots" templateId="${btSettings.externalId}"><c:out
                                                                    value="${btSettings.fullName}"/></admin:editTemplateLink>
                                                        </c:when>
                                                        <c:otherwise><c:out value="${btSettings.fullName}"/></c:otherwise>
                                                    </c:choose>
                                                </li>
                                            </c:forEach>
                                        </ul>
                                    </div>
                                </c:if>

                                <c:if test="${not empty usages.buildTypes}">
                                    <div class="btUsages">
                                        <div>
                                            Used in <b>${fn:length(usages.buildTypes)}</b> build configuration<bs:s val="${fn:length(usages.buildTypes)}"/>:
                                        </div>
                                        <ul>
                                            <c:forEach items="${usages.buildTypes}" var="btSettings" varStatus="pos">
                                                <li>
                      <span class="buildConfigurationLink">
                        <c:set var="canEdit"
                               value="${afn:permissionGrantedForBuildType(btSettings, 'EDIT_PROJECT') and (not btSettings.templateBased or btSettings.templateAccessible)}"/>
                        <c:choose>
                            <c:when test="${canEdit}">
                                <admin:editBuildTypeLinkFull step="vcsRoots" buildType="${btSettings}"/>
                            </c:when>
                            <c:otherwise><c:out value="${btSettings.fullName}"/></c:otherwise>
                        </c:choose>
                      </span>

                                                        <%--<bs:systemProblemCountLabel problemsCount="${problemsCountInfo.countPerBuildType[btSettings]}"--%>
                                                        <%--onclick="BS.SystemProblemsPopup.showDetails('${btSettings.buildTypeId}', '${problemType}', '${vcsRoot.id}', true, this); return false;"/>--%>
                                                </li>
                                            </c:forEach>
                                        </ul>
                                    </div>
                                </c:if>

                                <c:if test="${not empty usages.versionedSettings}">
                                    <div>
                                        <div>
                                            Used in <b>${fn:length(usages.versionedSettings)}</b> project<bs:s val="${fn:length(usages.versionedSettings)}"/> to store settings:
                                        </div>
                                        <ul>
                                            <c:forEach items="${usages.versionedSettings}" var="proj">
                                                <c:set var="canEdit" value="${afn:permissionGrantedForProject(proj, 'EDIT_PROJECT')}"/>
                                                <li>
                                                    <c:choose>
                                                        <c:when test="${canEdit}">
                                                            <admin:editProjectLink projectId="${proj.externalId}" addToUrl="&tab=versionedSettings"><c:out
                                                                    value="${proj.fullName}"/></admin:editProjectLink>
                                                        </c:when>
                                                        <c:otherwise>
                                                            <c:out value="${proj.fullName}"/>
                                                        </c:otherwise>
                                                    </c:choose>
                                                </li>
                                            </c:forEach>
                                        </ul>
                                    </div>
                                </c:if>
                                <c:if test="${empty usages.templates and empty usages.buildTypes}">
                                    <div class="templateUsages"><i>The VCS root is not used in any build configuration or template.</i></div>
                                </c:if>
                            </td>
                    </tr>
                    </c:if>
                </c:forEach>
            </c:forEach>
        </l:tableWithHighlighting>
    </div>
</div>