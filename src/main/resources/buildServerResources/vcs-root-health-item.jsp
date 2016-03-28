<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ taglib prefix="util" uri="/WEB-INF/functions/util" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

<%--@elvariable id="healthStatusItem" type="jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem"--%>
<%--@elvariable id="pluginResourcesPath" type="java.lang.String"--%>

<c:set var="GitHubInfo" value="${healthStatusItem.additionalData['GitHubInfo']}"/>
<c:set var="VcsRoot" value="${healthStatusItem.additionalData['VcsRoot']}"/>
<%--@elvariable id="GitHubInfo" type="org.jetbrains.teamcity.github.VcsRootGitHubInfo"--%>
<%--@elvariable id="VcsRoot" type="jetbrains.buildServer.vcs.SVcsRoot"--%>


<c:set var="id" value="hid_${util:forJSIdentifier(GitHubInfo.identifier)}"/>

<div id='${id}' class="suggestionItem" data-repository="${GitHubInfo}" data-server="${GitHubInfo.server}">
    Found VCS root <admin:vcsRootName vcsRoot="${VcsRoot}" editingScope="editProject:${VcsRoot.project.externalId}" cameFromUrl="${pageUrl}"/>
    belongs to <admin:projectName project="${VcsRoot.project}"/>
    referencing GitHub repository <a href="${GitHubInfo.repositoryUrl}">${GitHubInfo.repositoryUrl}</a> with configured WebHook:
    <div class="suggestionAction">
        <a href="#"
           onclick="BS.GitHubWebHooks.setVcsPoolInterval(this, '${VcsRoot.id}', '${VcsRoot.project.externalId}'); return false">Set Checking for changes interval to 1h </a>
        <%--<forms:button className="btn_mini" onclick="BS.GitHubWebHooks.addWebHook('${Type}', '${Id}'); return false">Add WebHook</forms:button>--%>
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
        BS.GitHubWebHooks.info['${GitHubInfo.identifier}'] = ${GitHubInfo.toJson()};
    })();
</script>