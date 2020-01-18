/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
