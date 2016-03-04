<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

<%--@elvariable id="healthStatusItem" type="jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem"--%>
<%--@elvariable id="pluginResourcesPath" type="java.lang.String"--%>
<%--@elvariable id="has_connections" type="java.lang.Boolean"--%>
<%--@elvariable id="has_tokens" type="java.lang.Boolean"--%>

<c:set var="GitHubInfo" value="${healthStatusItem.additionalData['GitHubInfo']}"/>
<c:set var="VcsRoot" value="${healthStatusItem.additionalData['VcsRoot']}"/>
<%--@elvariable id="GitHubInfo" type="org.jetbrains.teamcity.github.VcsRootGitHubInfo"--%>
<%--@elvariable id="VcsRoot" type="jetbrains.buildServer.vcs.SVcsRoot"--%>

<c:set var="Identifier" value="${GitHubInfo.identifier}"/>

<div id='hid_${Identifier}' class="suggestionItem" data-repository="${GitHubInfo}" data-server="${GitHubInfo.server}" data-identifier="${Identifier}">
    Found VCS root <admin:vcsRootName vcsRoot="${VcsRoot}" editingScope="editProject:${VcsRoot.project.externalId}" cameFromUrl="${pageUrl}"/>
    belongs to <admin:projectName project="${VcsRoot.project}"/>
    referencing GitHub repository <a href="${GitHubInfo.repositoryUrl}">${GitHubInfo.repositoryUrl}</a> without configured WebHook:
    <div class="suggestionAction">
        <c:choose>
            <c:when test="${has_connections}">
                <a href="#" class="addNew" onclick="BS.GitHubWebHooks.doAction('add', this, '${GitHubInfo}', '${VcsRoot.project.externalId}', ${not has_tokens}); return false">Add WebHook</a>
            </c:when>
            <c:otherwise>
                <span>There no GitHub OAuth connections found for GitHub server '${GitHubInfo.server}'</span>
                <a href="#" class="addNew" onclick="BS.GitHubWebHooks.addConnection(this, '${VcsRoot.project.externalId}', '${GitHubInfo.server}'); return false">Add OAuth connection</a>
            </c:otherwise>
        </c:choose>
        <%--<forms:button className="btn_mini" onclick="BS.GitHubWebHooks.addWebHook('${Type}', '${Id}'); return false">Add WebHook</forms:button>--%>
    </div>
</div>

<script type="text/javascript">
    (function () {
        if (typeof BS.GitHubWebHooks === 'undefined') {
            $j('#hid_${Identifier}').append("<script type='text/javascript' src='<c:url value="${pluginResourcesPath}gh-webhook.js"/>'/>");
        }
        if (typeof BS.ServerInfo === 'undefined') {
            BS.ServerInfo = {
                url : '${serverSummary.rootURL}'
            };
        }
        if (typeof BS.RequestInfo === 'undefined') {
            BS.RequestInfo = {
                context_path : '${pageContext.request.contextPath}'
            };
        }
        BS.GitHubWebHooks.info['${Identifier}'] = ${GitHubInfo.toJson()};
        BS.GitHubWebHooks.forcePopup['${GitHubInfo.server}'] = ${not has_connections or not has_tokens};
    })();
</script>