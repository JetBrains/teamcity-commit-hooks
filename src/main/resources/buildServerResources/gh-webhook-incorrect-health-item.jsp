<%@ page import="jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemDisplayMode" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

<jsp:useBean id="showMode" type="jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemDisplayMode" scope="request"/>
<jsp:useBean id="healthStatusReportUrl" type="java.lang.String" scope="request"/>
<jsp:useBean id="pageUrl" type="java.lang.String" scope="request"/>

<c:set var="inplaceMode" value="<%=HealthStatusItemDisplayMode.IN_PLACE%>"/>
<c:set var="cameFromUrl" value="${showMode eq inplaceMode ? pageUrl : healthStatusReportUrl}"/>

<%--@elvariable id="healthStatusItem" type="jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem"--%>
<%--@elvariable id="pluginResourcesPath" type="java.lang.String"--%>
<%--@elvariable id="has_connections" type="java.lang.Boolean"--%>
<%--@elvariable id="has_tokens" type="java.lang.Boolean"--%>
<%--@elvariable id="Project" type="jetbrains.buildServer.serverSide.SProject"--%>

<c:set var="GitHubInfo" value="${healthStatusItem.additionalData['GitHubInfo']}"/>
<c:set var="HookInfo" value="${healthStatusItem.additionalData['HookInfo']}"/>
<c:set var="Reason" value="${healthStatusItem.additionalData['Reason']}"/>
<%--@elvariable id="GitHubInfo" type="org.jetbrains.teamcity.github.GitHubRepositoryInfo"--%>
<%--@elvariable id="HookInfo" type="org.jetbrains.teamcity.github.WebHooksStorage.HookInfo"--%>
<%--@elvariable id="Reason" type="java.lang.String"--%>

<c:set var="id" value="hid_i_${util:forJSIdentifier(GitHubInfo.id)}"/>

<div id='${id}' class="suggestionItem"
     data-repository="<c:out value="${GitHubInfo.id}"/>"
     data-server="<c:out value="${GitHubInfo.server}"/>"
     data-project-id="<c:out value="${Project.externalId}"/>">
    <a href="<c:out value="${GitHubInfo.repositoryUrl}/settings/hooks/${HookInfo.id}"/>">Webhook</a>
    for the GitHub repository <a href="${GitHubInfo.repositoryUrl}"><c:out value="${GitHubInfo.id}"/></a>
    is misconfigured: <c:out value="${Reason}"/>
    <div class="suggestionAction">
        <c:set var="projectUrl"><admin:editProjectLink projectId="${Project.externalId}" withoutLink="true"/></c:set>
        <forms:addLink
                href="${projectUrl}&tab=installWebHook&repository=${util:urlEscape(GitHubInfo.id)}&cameFromUrl=${util:urlEscape(cameFromUrl)}">Re-install GitHub webhook</forms:addLink>
        <br>
        <a class="" onclick="return BS.GitHubWebHooks.doAction('delete', this);">Remove webhook</a>
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