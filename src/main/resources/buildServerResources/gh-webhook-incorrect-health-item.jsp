<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

<%--@elvariable id="healthStatusItem" type="jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem"--%>

<c:set var="GitHubInfo" value="${healthStatusItem.additionalData['GitHubInfo']}"/>
<c:set var="HookInfo" value="${healthStatusItem.additionalData['HookInfo']}"/>
<c:set var="Reason" value="${healthStatusItem.additionalData['Reason']}"/>
<%--@elvariable id="GitHubInfo" type="org.jetbrains.teamcity.github.GitHubRepositoryInfo"--%>
<%--@elvariable id="HookInfo" type="org.jetbrains.teamcity.github.WebHooksStorage.HookInfo"--%>
<%--@elvariable id="Reason" type="java.lang.String"--%>

<div class="suggestionItem">
    A problem has been detected with <a href="${HookInfo.UIUrl}" target="_blank">webhook</a>
    for the GitHub repository <a href="${GitHubInfo.repositoryUrl}"><c:out value="${GitHubInfo.id}"/></a>.
    Please check the <a href="${HookInfo.UIUrl}" target="_blank">webhook page</a> for details or contact your system administrator.
    Reason: ${Reason}
</div>
