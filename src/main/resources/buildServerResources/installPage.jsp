<%@include file="/include-internal.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
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

<jsp:useBean id="currentProject" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>
<jsp:useBean id="repository" type="java.lang.String" scope="request"/>
<jsp:useBean id="connectionId" type="java.lang.String" scope="request"/>
<jsp:useBean id="connectionProjectId" type="java.lang.String" scope="request"/>
<jsp:useBean id="cameFrom" type="jetbrains.buildServer.web.util.CameFromSupport" scope="request"/>

<%--@elvariable id="info" type="org.jetbrains.teamcity.github.GitHubRepositoryInfo"--%>
<%--@elvariable id="has_connections" type="java.lang.Boolean"--%>
<%--@elvariable id="has_tokens" type="java.lang.Boolean"--%>

<h2 class="noBorder">Install GitHub Webhook</h2>
<bs:smallNote>
  GitHub webhook notifies TeamCity server when a commit is pushed to repository.
  As a result TeamCity needs to poll GitHub repository for changes less frequently and detects commits made in repository almost instantly.
</bs:smallNote>

<div class="successMessage" id="webhookMessage"></div>

<form id="installWebhook"
      data-connection-id="<c:out value="${connectionId}"/>"
      data-connection-project-id="<c:out value="${connectionProjectId}"/>"
      data-connection-server="<c:out value="${empty info ? '' : info.server}"/>">

    <table class="runnerFormTable">
        <tr>
            <th><label for="repository">GitHub repository URL: <l:star/></label></th>
            <td>
                <forms:textField name="repository" className="longField" maxlength="1024" value="${repository}"/>
                <span class="icon-magic"
                      onclick="BS.GitHubWebHooks.SuitableRepositoriesPopup.showPopup(this, '${currentProject.externalId}')"
                      title="Click to choose a GitHub repository where a webhook can be installed"></span>
                <span class="error" id="webhookError"></span>
            </td>
        </tr>
    </table>

    <div class="saveButtonsBlock">
        <input type="hidden" id="projectId" value="${currentProject.externalId}">
        <forms:submit id="installWebhookSubmit" name="installWebhookSubmit" label="Install" onclick="BS.GitHubWebHooks.doInstallForm(this); return false;"/>
        <forms:cancel cameFromSupport="${cameFrom}"/>
        <forms:saving id="installProgress" style="float: none; margin-left: 0.5em;" savingTitle="Installing GitHub webhook..."/>
    </div>
</form>

<div id="installResult"></div>

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
        BS.GitHubWebHooks.info['${util:forJSIdentifier(info.id)}'] = ${info.toJson()};
        BS.GitHubWebHooks.forcePopup['${info.server}'] = ${not has_connections or not has_tokens};
        </c:if>

        BS.GitHubWebHooks.SuitableRepositoriesPopup = new BS.Popup("suitableReposPopup", {
          url: window['base_uri'] + "/webhooks/github/suitableRepositoriesPopup.html",
          method: "get",
          hideOnMouseOut: false,
          hideOnMouseClickOutside: true,
          shift: {x: 0, y: 20}
        });

        BS.GitHubWebHooks.SuitableRepositoriesPopup.showPopup = function(nearestElem, projectId) {
            this.options.parameters = "projectId=" + projectId;
            this.showPopupNearElement(nearestElem);
        }
    })();

    $j(document).ready(function() {
      if (BS.Repositories != null) {
        BS.Repositories.installControls($('repository'), function(repoInfo, cre) {
          $('repository').value = repoInfo.repositoryUrl;
        });
      }
    });
</script>

