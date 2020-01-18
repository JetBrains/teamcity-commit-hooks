<%@ page import="jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemDisplayMode" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ taglib prefix="util" uri="/WEB-INF/functions/util" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

<%--
  ~ Copyright 2000-2020 JetBrains s.r.o.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  --%>

<jsp:useBean id="showMode" type="jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemDisplayMode" scope="request"/>
<jsp:useBean id="healthStatusReportUrl" type="java.lang.String" scope="request"/>
<jsp:useBean id="pageUrl" type="java.lang.String" scope="request"/>

<c:set var="inplaceMode" value="<%=HealthStatusItemDisplayMode.IN_PLACE%>"/>
<c:set var="cameFromUrl" value="${showMode eq inplaceMode ? pageUrl : healthStatusReportUrl}"/>

<%--@elvariable id="healthStatusItem" type="jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem"--%>
<%--@elvariable id="pluginResourcesPath" type="java.lang.String"--%>
<%--@elvariable id="has_connections" type="java.lang.Boolean"--%>
<%--@elvariable id="has_tokens" type="java.lang.Boolean"--%>

<c:set var="GitHubInfo" value="${healthStatusItem.additionalData['GitHubInfo']}"/>
<c:set var="Project" value="${healthStatusItem.additionalData['Project']}"/>
<%--@elvariable id="GitHubInfo" type="org.jetbrains.teamcity.github.GitHubRepositoryInfo"--%>
<%--@elvariable id="Project" type="jetbrains.buildServer.serverSide.SProject"--%>

<c:set var="id" value="hid_${util:forJSIdentifier(GitHubInfo.id)}"/>

<div id='${id}' class="suggestionItem" data-repository="${GitHubInfo.id}" data-server="${GitHubInfo.server}">
    Install webhook into the GitHub repository <a href="${GitHubInfo.repositoryUrl}"><c:out value="${GitHubInfo.id}"/></a> to speedup collecting of the changes
    and reduce overhead on the GitHub server.
    <div class="suggestionAction">
        <c:set var="projectUrl"><admin:editProjectLink projectId="${Project.externalId}" withoutLink="true"/></c:set>
        <forms:addLink href="${projectUrl}&tab=installWebHook&repository=${util:urlEscape(GitHubInfo.id)}&cameFromUrl=${util:urlEscape(cameFromUrl)}">Install GitHub webhook</forms:addLink>
    </div>
</div>

<script type="text/javascript">
    (function () {
        if (typeof BS.GitHubWebHooks === 'undefined') {
            $j('#${id}').append("<script type='text/javascript' src='<c:url value="${pluginResourcesPath}gh-webhook.js"/>'/>");
        }
        if (typeof BS.ServerInfo === 'undefined') {
            BS.ServerInfo = {
                url: '${serverSummary.rootURL}'
            };
        }
        if (typeof BS.RequestInfo === 'undefined') {
            BS.RequestInfo = {
                context_path: '${pageContext.request.contextPath}'
            };
        }
        BS.GitHubWebHooks.info['${util:forJSIdentifier(GitHubInfo.id)}'] = ${GitHubInfo.toJson()};
        BS.GitHubWebHooks.forcePopup['${GitHubInfo.server}'] = ${not has_connections or not has_tokens};
    })();
</script>