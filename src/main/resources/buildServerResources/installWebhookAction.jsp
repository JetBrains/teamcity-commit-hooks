<%@ include file="/include-internal.jsp" %>
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

<jsp:useBean id="project" scope="request" type="jetbrains.buildServer.serverSide.SProject"/>
<l:li>
    <c:set var="cameFromUrl" value="${pageUrl}"/>
    <c:set var="projectUrl"><admin:editProjectLink projectId="${project.externalId}" withoutLink="true"/></c:set>
    <a href="${projectUrl}&tab=installWebHook&cameFromUrl=${util:urlEscape(cameFromUrl)}" title="Install GitHub webhook...">Install GitHub webhook...</a>
</l:li>
