<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>
<%--@elvariable id="json" type="java.lang.String"--%>
<script type="text/javascript">
    if (!window.opener.GitHubWebHookCallback) {
        alert("function window.opener.GitHubWebHookCallback is not defined");
    } else {
        var r = ${json};
        window.opener.GitHubWebHookCallback(r);
        window.close();
    }
</script>