

package org.jetbrains.teamcity.impl.fakes;

import org.jetbrains.annotations.NotNull;

import javax.servlet.ServletContext;

public class FakeHttpRequestsFactory {

  @NotNull
  private final ServletContext myServletContext;

  public FakeHttpRequestsFactory(@NotNull final ServletContext servletContext) {
    myServletContext = servletContext;
  }

  @NotNull
  public FakeHttpServletRequest get(@NotNull String path, @NotNull String query) {
    FakeHttpServletRequest request = new FakeHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURL("http://localhost" + myServletContext.getContextPath() + path);
    request.setRequestURI(path);
    request.setPathInfo(path);
    request.setQueryString(query);
    request.setContextPath(myServletContext.getContextPath());
    request.setServletContext(myServletContext);
    return request;
  }
}