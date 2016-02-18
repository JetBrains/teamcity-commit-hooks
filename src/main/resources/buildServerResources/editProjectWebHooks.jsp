<%@include file="/include-internal.jsp" %>
<%@ taglib prefix="admin" tagdir="/WEB-INF/tags/admin" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<jsp:useBean id="webHooksBean" type="org.jetbrains.teamcity.github.controllers.ProjectWebHooksBean" scope="request"/>

<bs:linkScript>
    /js/bs/systemProblemsMonitor.js
    ${pluginResourcesPath}gh-webhook.js
</bs:linkScript>
<bs:linkCSS>
    ${pluginResourcesPath}webhook.css
</bs:linkCSS>
<%--@elvariable id="currentProject" type="jetbrains.buildServer.serverSide.SProject"--%>

<div class="editProjectPage">
    <h2 class="noBorder">GitHub WebHooks</h2>
    <c:set value="<%=jetbrains.buildServer.serverSide.systemProblems.StandardSystemProblemTypes.VCS_CONFIGURATION%>" var="problemType"/>
    <c:set var="cameFromUrl" value="${param['cameFromUrl']}"/>
    <div id="webHooksTable" class="selection noMargin">
        <l:tableWithHighlighting className="parametersTable" id="projectVcsRoots" highlightImmediately="true">
            <tr>
                <th>Url</th>
                <th class="edit">Status</th>
                <th class="usages">Usages</th>
            </tr>
            <c:forEach items="${webHooksBean.hooks}" var="entry">
                <c:set var="uniq_hash" value="${entry.hashCode()}"/>
                <c:set var="totalUsages" value="${entry.value.totalUsagesCount}"/>
                <tr>
                    <td><div><span class="webHook"><a href="${entry.key.repositoryUrl}">${entry.key}</a></span></div></td>
                    <td class="edit">
                            <%--@elvariable id="webhook" type="org.jetbrains.teamcity.github.controllers.WebHooksStatus"--%>
                        <c:set var="webhook" value="${entry.value.status}"/>
                        <%@include file="webhook-status.jspf" %>
                    </td>
                    <td>
                        <a href="#" onclick="return BS.AdminActions.toggleWebHookUsages(this, '${uniq_hash}');"><c:out default="" value="${totalUsages}"/>
                            usage<bs:s val="${totalUsages}"/></a>
                    </td>
                </tr>
                <c:if test="${totalUsages > 0}">
                    <tr id="webhook_${uniq_hash}_usages" class="usages usageHl" style="display: none">
                        <td>
                            <c:forEach items="${entry.value.roots}" var="root">
                                <%--@elvariable id="root" type="jetbrains.buildServer.vcs.SVcsRoot"--%>
                                <div>
                                    <span class="vcsRoot"><admin:vcsRootName editingScope="" cameFromUrl="${cameFromUrl}" vcsRoot="${root}"/></span>
                                    <c:if test="${root.project.projectId != currentProject.projectId}">
                                        belongs to <admin:projectName project="${root.project}"/>
                                    </c:if>
                                        <%--<div class="clearfix"></div>--%>
                                </div>
                            </c:forEach>
                            <c:forEach items="${entry.value.instances}" var="e2">
                                <%--@elvariable id="root" type="jetbrains.buildServer.vcs.SVcsRoot"--%>
                                <c:set var="root" value="${e2.key}"/>
                                <%--@elvariable id="usages" type="org.jetbrains.teamcity.github.controllers.VcsRootUsages"--%>
                                <c:set var="usages" value="${entry.value.getVcsRootUsages(root)}"/>
                                <c:set var="uniq_hash" value="${usages.hashCode()}"/>
                                <div class="">
                                    <span class="vcsRoot"><admin:vcsRootName editingScope="" cameFromUrl="${cameFromUrl}" vcsRoot="${root}"/></span>
                                    <c:if test="${root.project.projectId != currentProject.projectId}">
                                        belongs to <admin:projectName project="${root.project}"/>
                                </c:if>
                                    parametrized in
                                    <c:choose>
                                        <c:when test="${usages == null || usages.total == 0}"><span title="This VCS root is not used">no usages</span></c:when>
                                        <c:otherwise>
                                            <a href="#" onclick="return BS.AdminActions.toggleVcsRootInstanceUsages(this, '${root.id}_${uniq_hash}');"><c:out default=""
                                                                                                                                                              value="${usages.total}"/>
                                                usage<bs:s
                                                        val="${usages.total}"/></a>
                                        </c:otherwise>
                                    </c:choose>
                                </div>
                                <c:if test="${usages != null && usages.total > 0}">
                                    <div id="instance_${root.id}_${uniq_hash}_usages" class="usages usageHl" style="display: none">
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
                                                                <%--onclick="BS.SystemProblemsPopup.showDetails('${btSettings.buildTypeId}', '${problemType}', '${root.id}', true, this); return false;"/>--%>
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
                                    </div>
                                </c:if>
                            </c:forEach>
                        </td>
                        <td></td>
                        <td></td>
                    </tr>
                </c:if>
            </c:forEach>
        </l:tableWithHighlighting>
    </div>
</div>