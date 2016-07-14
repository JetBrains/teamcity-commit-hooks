<%@ include file="/include-internal.jsp" %>
<jsp:useBean id="project" scope="request" type="jetbrains.buildServer.serverSide.SProject"/>
<l:li>
    <c:set var="cameFromUrl" value="${pageUrl}"/>
    <c:set var="projectUrl"><admin:editProjectLink projectId="${project.externalId}" withoutLink="true"/></c:set>
    <a href="${projectUrl}&tab=installWebHook&cameFromUrl=${util:urlEscape(cameFromUrl)}" title="Install webhook...">Install webhook...</a>
</l:li>
