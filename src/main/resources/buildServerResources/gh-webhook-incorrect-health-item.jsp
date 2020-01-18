<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>

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
