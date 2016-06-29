<%@include file="/include-internal.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<jsp:useBean id="currentProject" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>
<jsp:useBean id="repository" type="java.lang.String" scope="request"/>
<jsp:useBean id="connectionId" type="java.lang.String" scope="request"/>
<jsp:useBean id="connectionProjectId" type="java.lang.String" scope="request"/>
<jsp:useBean id="cameFrom" type="jetbrains.buildServer.web.util.CameFromSupport" scope="request"/>

<%--@elvariable id="info" type="org.jetbrains.teamcity.github.GitHubRepositoryInfo"--%>
<%--@elvariable id="has_connections" type="java.lang.Boolean"--%>
<%--@elvariable id="has_tokens" type="java.lang.Boolean"--%>

<div class="editProjectPage">
    <form id="installWebhook">
        <input type="hidden" id="projectId" value="${currentProject.externalId}">
        <input type="hidden" id="connectionId" value="${connectionId}">
        <input type="hidden" id="connectionProjectId" value="${connectionProjectId}">
        <table class="runnerFormTable">
            <tr>
                <th><label for="repository">Repository url: <l:star/></label></th>
                <td>
                    <forms:textField name="repository" className="longField" maxlength="80" value="${empty repository ? '' : repository}"/>
                    <span class="error" id="errorRepository"></span>
                </td>
            </tr>
        </table>
        <div class="saveButtonsBlock">
            <forms:submit id="installWebhook" name="installWebhook" label="Install" onclick="BS.GitHubWebHooks.doInstallForm(this); return false;"/>
            <%--TODO: Add 'return back' cancel button--%>
            <forms:cancel cameFromSupport="${cameFrom}"/>
            <forms:saving id="installProgress" style="float: none; margin-left: 0.5em;" savingTitle="Installing Webhook..."/>
        </div>
    </form>
    <div id="installResult">
    </div>
</div>
<script type="text/javascript">
    (function () {
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
        <c:if test="${info != null}">
        BS.GitHubWebHooks.info['${info.identifier}'] = ${info.toJson()};
        BS.GitHubWebHooks.forcePopup['${info.server}'] = ${not has_connections or not has_tokens};
        </c:if>
    })();
</script>

