<%@include file="/include-internal.jsp" %>
<%--
  ~ Copyright 2000-2021 JetBrains s.r.o.
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

<jsp:useBean id="repositoriesMap" type="java.util.Map" scope="request"/> <!-- Map of GitHubRepositoryInfo to OAuth connection -->
<jsp:useBean id="hasConnections" type="java.lang.Boolean" scope="request"/>
<jsp:useBean id="project" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>
<c:set var="reposCount" value="${fn:length(repositoriesMap)}"/>
<c:choose>
  <c:when test="${reposCount > 0}">
    <div class="grayNote reposCount">Found <strong>${fn:length(repositoriesMap)}</strong> <bs:plural txt="repository" val="${fn:length(repositoriesMap)}"/> in the project where GitHub webhook can be installed.</div>
    <ul class="menuList">
      <c:forEach items="${repositoriesMap}" var="entry">
        <li onclick="$j('#repository').val('${entry.key.id}'); $j('#installWebhook').attr('data-connection-id', '${entry.value.id}'); $j('#installWebhook').attr('data-connection-project-id', '${entry.value.project.externalId}'); $j('#installWebhook').attr('data-connection-server', '${entry.key.server}'); BS.GitHubWebHooks.SuitableRepositoriesPopup.hidePopup(0)">
          <c:out value="${entry.key.repositoryUrl}"/>
        </li>
      </c:forEach>
    </ul>
  </c:when>
  <c:when test="${not hasConnections}">
    <div>There are no OAuth connections <admin:editProjectLink projectId="${project.externalId}" addToUrl="&tab=oauthConnections">configured</admin:editProjectLink> in this project.</div>
  </c:when>
  <c:otherwise>
    <div>Could not find repositories where GitHub webhook could be installed.</div>
  </c:otherwise>
</c:choose>
