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
    <h2 class="noBorder">GitHub Webhooks</h2>
    <p>Only GitHub Git VCS roots are shown</p>
    <span class="smallNote" style="margin-left: 0">To configure known GitHub Enterprise servers, see <a
            href="<c:url value="/admin/editProject.html?projectId=${currentProject.externalId}&tab=oauthConnections"/>">OAuth Integrations page</a></span>
    <c:set var="cameFromUrl" value="${param['cameFromUrl']}"/>

    <form action="<c:url value='/admin/editProject.html'/>" method="get" id="webHooksFilterForm" onsubmit="BS.Util.show('spinner'); return true;">
        <div class="actionBar">
            <span class="nowrap">
                <label class="firstLabel" for="keyword">Filter: </label>
                <forms:textField name="keyword" value="${webHooksBean.form.keyword}" size="20"/>
            </span>
            <forms:filterButton/>
            <forms:resetFilter resetHandler="if($('webHooksFilterForm').keyword.value != '') {$('webHooksFilterForm').keyword.value='';$('webHooksFilterForm').submit();}"/>
         <span style="margin-left: 20px">
                <forms:checkbox name="recursive" checked="${webHooksBean.form.recursive}" onclick="$('webHooksFilterForm').submit();"/>
                <label for="recursive" style="margin: 0;">Show Webhooks from subprojects</label>
            </span>
            <span id="spinner" class="spinner" style="display: none"><i class="icon-refresh icon-spin"></i> Refreshing...</span>
        </div>
        <input type="hidden" name="projectId" value="${webHooksBean.project.externalId}"/>
        <input type="hidden" name="tab" value="editProjectWebHooks"/>
    </form>

    <div id="webHooksTable" class="selection noMargin">
        <c:if test="${not empty webHooksBean.visibleHooks}">
        <l:tableWithHighlighting className="parametersTable" id="projectVcsRoots" highlightImmediately="true">
            <tr>
                <th>Repository
                    <span class="spinner" style="display: none"><i class="icon-refresh icon-spin"></i> Refreshing...</span>
                </th>
                <th class="edit"><a href="#" onclick="BS.GitHubWebHooks.checkAll(this, '${webHooksBean.project.externalId}', ${webHooksBean.form.recursive}); return false">Check All</a></th>
                <th class="edit">Status</th>
                <th class="usages">Usages</th>
            </tr>
            <c:forEach items="${webHooksBean.visibleHooks}" var="entry">
                <c:set var="uniq_hash" value="${entry.hashCode()}"/>
                <c:set var="totalUsages" value="${entry.value.totalUsagesCount}"/>
                <tr data-repository="${entry.key}" data-project-id="${currentProject.externalId}">
                    <td><div>
                        <span class="webHook"><a href="${entry.key.repositoryUrl}">${entry.key}</a></span>
                        <span style="float: right" data-view="link"></span>
                    </div></td>
                    <td class="edit" data-view="actions"></td>
                    <td class="edit" data-view="status"></td>
                    <td>
                        <a href="#" onclick="return BS.AdminActions.toggleWebHookUsages(this, '${uniq_hash}');"><c:out default="" value="${totalUsages}"/>
                            usage<bs:s val="${totalUsages}"/></a>
                    </td>
                </tr>
                <c:if test="${totalUsages > 0}">
                    <tr id="webhook_${uniq_hash}_usages" class="usages usageHl" style="display: none">
                        <!--Do not use colspan because Chrome fails to compute border-top-style-->
                        <td>
                            <c:forEach items="${entry.value.usagesMap}" var="e2">
                                <%--@elvariable id="root" type="jetbrains.buildServer.vcs.SVcsRoot"--%>
                                <c:set var="root" value="${e2.key}"/>
                                <%--@elvariable id="usages" type="org.jetbrains.teamcity.github.controllers.VcsRootUsages"--%>
                                <c:set var="usages" value="${e2.value}"/>
                                <c:set var="uniq_hash" value="${usages.hashCode()}"/>
                                <div class="">
                                    <span class="vcsRoot"><admin:vcsRootName editingScope="" cameFromUrl="${cameFromUrl}" vcsRoot="${root}"/></span>
                                    <c:if test="${root.project.projectId != currentProject.projectId}">
                                        belongs to <admin:projectName project="${root.project}"/>
                                    </c:if>
                                    <c:choose>
                                        <c:when test="${usages.total == 0}"><span title="This VCS root is not used">no usages</span></c:when>
                                        <c:otherwise>
                                            <a href="#" onclick="return BS.AdminActions.toggleVcsRootInstanceUsages(this, '${root.id}_${uniq_hash}');"><c:out default=""
                                                                                                                                                              value="${usages.total}"/>
                                                usage<bs:s val="${usages.total}"/></a>
                                        </c:otherwise>
                                    </c:choose>
                                </div>
                                <c:if test="${usages.total > 0}">
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
                        <td></td>
                    </tr>
                </c:if>
            </c:forEach>
        </l:tableWithHighlighting>
        </c:if>
    </div>

    <script type="text/javascript">
        (function () {
            <c:forEach items="${webHooksBean.enforcePopupData}" var="entry">
            BS.GitHubWebHooks.forcePopup['${entry.key}'] = ${entry.value};
            </c:forEach>
            <c:forEach items="${webHooksBean.visibleHooks}" var="entry">
            BS.GitHubWebHooks.data['${entry.key}'] = ${webHooksBean.getDataJson(entry.key).toString()};
            </c:forEach>
            BS.GitHubWebHooks.renderTable($j('#webHooksTable'));
            BS.PeriodicalRefresh.start(5, function () {
                BS.GitHubWebHooks.refreshTable($j('#webHooksTable'));
            });
        })();
    </script>

    <bs:pager place="bottom"
              urlPattern="editProject.html?tab=editProjectWebHooks&projectId=${webHooksBean.project.externalId}&page=[page]&keyword=${webHooksBean.form.keyword}&recursive=${webHooksBean.form.recursive}"
              pager="${webHooksBean.pager}"/>
</div>