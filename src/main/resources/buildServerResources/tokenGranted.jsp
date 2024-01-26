<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="afn" uri="/WEB-INF/functions/authz" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="/include-internal.jsp" %>


<bs:externalPage>
  <jsp:attribute name="page_title">GitHub Authentication</jsp:attribute>
  <jsp:attribute name="head_include">
    <script type="text/javascript">
      if (window.opener) {
        window.close();
      }
    </script>
  </jsp:attribute>
  <jsp:attribute name="body_include">
    <div class="authenticated">
      Authentication successful! Please close this window.
    </div>
  </jsp:attribute>
</bs:externalPage>