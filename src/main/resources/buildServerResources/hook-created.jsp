<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>
<%--@elvariable id="isSuccessful" type="java.lang.Boolean"--%>
<%--@elvariable id="info" type="org.jetbrains.teamcity.github.VcsRootGitHubInfo"--%>
<script type="text/javascript">
    if (!window.opener.GitHubWebHookCallback) {
        alert("function window.opener.GitHubWebHookCallback is not defined");
    } else {
        var r = ${info.toJson()};
        window.opener.GitHubWebHookCallback(r, ${isSuccessful});
        window.close();
    }
</script>