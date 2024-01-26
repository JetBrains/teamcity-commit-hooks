<%@include file="/include-internal.jsp"%>


<jsp:useBean id="currentProject" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>

<div class="editProjectPage">
<jsp:include page="/admin/project/webhooks/edit.html?projectId=${currentProject.externalId}" >
  <jsp:param name="cameFromUrl" value="${pageUrl}"/>
</jsp:include>
</div>