<%@include file="/include-internal.jsp" %>
<jsp:useBean id="repositoriesMap" type="java.util.Map" scope="request"/> <!-- Map of GitHubRepositoryInfo to OAuth connection -->
<c:set var="reposCount" value="${fn:length(repositoriesMap)}"/>
<c:choose>
  <c:when test="${reposCount > 0}">
    <div class="grayNote reposCount">Found <strong>${fn:length(repositoriesMap)}</strong> <bs:plural txt="repository" val="${fn:length(repositoriesMap)}"/> in the project where GitHub webhook can be installed.</div>
    <ul class="menuList">
      <c:forEach items="${repositoriesMap}" var="entry">
        <li onclick="$j('#repository').val('${entry.key}'); $j('#installWebhook').attr('data-connection-id', '${entry.value.id}'); $j('#installWebhook').attr('data-connection-project-id', '${entry.value.project.externalId}'); $j('#installWebhook').attr('data-connection-server', '${entry.key.server}'); BS.GitHubWebHooks.SuitableRepositoriesPopup.hidePopup(0)">
          <c:out value="${entry.key.server}"/>
        </li>
      </c:forEach>
    </ul>
  </c:when>
  <c:otherwise>
    <div>Could not find repositories where GitHub webhook could be installed.</div>
  </c:otherwise>
</c:choose>
