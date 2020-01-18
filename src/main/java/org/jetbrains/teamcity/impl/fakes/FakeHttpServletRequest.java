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

import com.google.common.collect.Maps;
import com.intellij.util.enumeration.EmptyEnumeration;
import com.intellij.util.enumeration.SingleEnumeration;
import jetbrains.buildServer.util.CaseInsensitiveStringComparator;
import org.jetbrains.annotations.NotNull;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeHttpServletRequest implements HttpServletRequest {
  private final Map<String, Object> myAttributes = new ConcurrentHashMap<>();
  private final Map<String, List<String>> myParameters = Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, String> myHeaders = Maps.synchronizedNavigableMap(new TreeMap<>(CaseInsensitiveStringComparator.INSTANCE));
  private final List<Cookie> myCookies = new CopyOnWriteArrayList<>();
  private volatile FakeHttpSession mySession;
  private volatile String myRequestedSessionId;
  private volatile String myQueryString;
  private volatile String myContextPath = "";
  private volatile String myRequestURI;
  private volatile String myRequestURL;
  private volatile String myServletPath = "";
  private volatile String myServerName = "localhost";
  private volatile String myLocalName = "localhost";
  private volatile int myServerPort = 80;
  private volatile int myLocalPort = 80;
  private volatile String myMethod;
  private volatile String myLocalAddr = "127.0.0.1";
  private volatile String myPathInfo;
  private volatile ServletInputStream myInputStream;
  private volatile String myRemoteHost = "localhost";
  private volatile String myRemoteAddr = "127.0.0.1";
  private volatile int myRemotePort = 80;
  private volatile ServletContext myServletContext;

  public FakeHttpServletRequest() {
    setInputStream(new ByteArrayInputStream(new byte[0]));
  }

  public String getAuthType() {
    return null;
  }

  public String getContextPath() {
    return myContextPath;
  }

  public void setContextPath(final String contextPath) {
    myContextPath = contextPath;
  }

  public Cookie[] getCookies() {
    return myCookies.toArray(new Cookie[myCookies.size()]);
  }

  public long getDateHeader(String name) {
    String value = getHeader(name);
    if (value == null) {
      return (-1L);
    }

    // Attempt to convert the date header in a variety of formats
    Long result = internalParseDate(value, formats);
    if (result != null) {
      return result;
    }
    throw new IllegalArgumentException(value);
  }

  protected SimpleDateFormat formats[] = {
    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
    new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
    new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US)
  };

  /**
   * Parse date with given formatters.
   */
  private static Long internalParseDate (String value, DateFormat[] formats) {
    Date date = null;
    for (int i = 0; (date == null) && (i < formats.length); i++) {
      try {
        date = formats[i].parse(value);
      } catch (ParseException e) {
        // Ignore
      }
    }
    if (date == null) {
      return null;
    }
    return date.getTime();
  }

  public String getHeader(String string) {
    return myHeaders.get(string);
  }

  public Enumeration<String> getHeaderNames() {
    return Collections.enumeration(myHeaders.keySet());
  }

  public Enumeration<String> getHeaders(String string) {
    final String header = myHeaders.get(string);
    //noinspection unchecked
    return header == null ? EmptyEnumeration.INSTANCE : new SingleEnumeration(header);
  }

  public int getIntHeader(String name) {
    String value = getHeader(name);
    if (value == null) {
      return -1;
    }

    return Integer.parseInt(value);
  }

  public String getMethod() {
    return myMethod;
  }

  public void setMethod(final String method) {
    myMethod = method;
  }

  public String getPathInfo() {
    return myPathInfo;
  }

  public String getPathTranslated() {
    return myPathInfo;
  }

  public String getQueryString() {
    return myQueryString;
  }

  public void setQueryString(final String queryString) {
    myQueryString = queryString;
  }

  public String getRemoteUser() {
    return null;
  }

  public String getRequestedSessionId() {
    final String requestedSessionId = myRequestedSessionId;
    if (requestedSessionId != null) {
      return requestedSessionId;
    }

    final FakeHttpSession session = mySession;
    return session != null ? session.getId() : null;
  }

  public void setRequestedSessionId(final String requestedSessionId) {
    myRequestedSessionId = requestedSessionId;
  }

  public String getRequestURI() {
    return myContextPath + myRequestURI;
  }

  public void setRequestURI(final String contextPath, final String requestURI) {
    myContextPath = contextPath;
    myRequestURI = requestURI;
  }

  public void setRequestURI(final String requestURI) {
    myRequestURI = requestURI;
  }

  public void setServletPath(final String servletPath) {
    myServletPath = servletPath;
  }

  public void setRequestURL(final String requestURL) {
    myRequestURL = requestURL;
  }

  public StringBuffer getRequestURL() {
    return new StringBuffer(myRequestURL);
  }

  public String getServletPath() {
    return myServletPath;
  }

  public HttpSession getSession() {
    return getSession(true);
  }

  @Override
  public String changeSessionId() {
    return null;
  }

  public void setSession(final FakeHttpSession session) {
    mySession = session;
    if (session != null) {
      myRequestedSessionId = session.getId();
    }
  }

  public HttpSession getSession(boolean b) {
    if (mySession != null && mySession.isInvalidated()) {
      mySession = null;
    }

    if (mySession == null && b) {
      mySession = createSession();
    }

    return mySession;
  }

  @NotNull
  protected FakeHttpSession createSession() {
    return new FakeHttpSession();
  }

  public Principal getUserPrincipal() {
    return null;
  }

  public boolean isRequestedSessionIdFromCookie() {
    return true;
  }

  public boolean isRequestedSessionIdFromURL() {
    return false;
  }

  public boolean isRequestedSessionIdFromUrl() {
    return false;
  }

  public boolean authenticate(final HttpServletResponse httpServletResponse) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void login(final String s, final String s1) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void logout() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public Collection<Part> getParts() throws IllegalStateException {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public Part getPart(final String s) throws IllegalStateException {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  @Override
  public <T extends HttpUpgradeHandler> T upgrade(final Class<T> httpUpgradeHandlerClass) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public boolean isRequestedSessionIdValid() {
    return true;
  }

  public boolean isUserInRole(String string) {
    return false;
  }

  public Object getAttribute(String string) {
    return myAttributes.get(string);
  }

  public Enumeration<String> getAttributeNames() {
    return Collections.enumeration(myAttributes.keySet());
  }

  public String getCharacterEncoding() {
    return null;
  }

  public int getContentLength() {
    return -1;
  }

  @Override
  public long getContentLengthLong() {
    return -1;
  }

  public String getContentType() {
    return getHeader("Content-Type");
  }

  public void setInputStream(final InputStream is) {
    myInputStream = new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return false;
      }

      @Override
      public boolean isReady() {
        return false;
      }

      @Override
      public void setReadListener(final ReadListener listener) {

      }

      @Override
      public int read() throws IOException {
        return is.read();
      }
    };
  }

  public ServletInputStream getInputStream() {
    return myInputStream;
  }

  public String getLocalAddr() {
    return myLocalAddr;
  }

  public Locale getLocale() {
    return Locale.ENGLISH;
  }

  public Enumeration<Locale> getLocales() {
    return Collections.enumeration(Collections.singleton(Locale.ENGLISH));
  }

  public String getLocalName() {
    return myLocalName;
  }

  public void setLocalName(final String localName) {
    myLocalName = localName;
  }

  public int getLocalPort() {
    return myLocalPort;
  }

  public ServletContext getServletContext() {
    return myServletContext;
  }

  public AsyncContext startAsync() {
    throw new IllegalStateException("Not implemented in " + getClass().getName());
  }

  public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse) {
    throw new IllegalStateException("Not implemented in " + getClass().getName());
  }

  public boolean isAsyncStarted() {
    return false;
  }

  public boolean isAsyncSupported() {
    return false;
  }

  public AsyncContext getAsyncContext() {
    throw new IllegalStateException("Not implemented in " + getClass().getName());
  }

  public DispatcherType getDispatcherType() {
    return DispatcherType.REQUEST;
  }


  public String getParameter(String string) {
    final List<String> values = myParameters.get(string);
    if (values == null || values.size() == 0) {
      return null;
    }

    return values.get(0);
  }

  public Map<String, String[]> getParameterMap() {
    final HashMap<String, String[]> result = new HashMap<String, String[]>();
    synchronized (myParameters) {
      for (String s : myParameters.keySet()) {
        result.put(s, myParameters.get(s).toArray(new String[myParameters.get(s).size()]));
      }
    }

    return result;
  }

  public Enumeration<String> getParameterNames() {
    synchronized (myParameters) {
      return Collections.enumeration(myParameters.keySet());
    }
  }

  public String[] getParameterValues(String string) {
    final List<String> strings = myParameters.get(string);
    if (strings == null) return null;
    return strings.toArray(new String[strings.size()]);
  }

  public String getProtocol() {
    return "HTTP/1.1";
  }

  public BufferedReader getReader() throws IOException {
    return new BufferedReader(new InputStreamReader(myInputStream));
  }

  public String getRealPath(String string) {
    return myServletContext != null  ? myServletContext.getRealPath(string) : string;
  }

  public String getRemoteAddr() {
    return myRemoteAddr;
  }

  public String getRemoteHost() {
    return myRemoteHost;
  }

  public int getRemotePort() {
    return myRemotePort;
  }

  public RequestDispatcher getRequestDispatcher(String string) {
    return new FakeRequestDispatcher();
  }

  public String getScheme() {
    return "http";
  }

  public void setHost(final String hostHeaderValue) {
    setHeader("Host", hostHeaderValue);

    String[] parts = hostHeaderValue.split(":");
    setServerName(parts[0]);
    if (parts.length > 1) {
      setServerPort(Integer.parseInt(parts[1]));
    }
  }

  public String getServerName() {
    return myServerName;
  }

  public void setServerName(final String serverName) {
    myServerName = serverName;
  }

  public int getServerPort() {
    return myServerPort;
  }

  public boolean isSecure() {
    return false;
  }

  public void removeAttribute(String string) {
    myAttributes.remove(string);
  }

  public void setAttribute(String string, Object object) {
    myAttributes.put(string, object);
  }

  public void clearAttributes() {
    myAttributes.clear();
  }

  public void setCharacterEncoding(String encoding) {
  }

  public void setHeader(final String s, final String s1) {
    myHeaders.put(s, s1);
  }

  public void addCookie(final Cookie cookie) {
    myCookies.add(cookie);
  }

  public void setParameters(Object ... params) {
    myParameters.clear();
    addParameters(params);
  }

  public void addParameters(final Object ... params) {
    for (int i = 0; i < params.length; i+=2) {
      String param = params[i].toString();

      List<String> values = myParameters.get(param);
      if (values == null) {
        values = new ArrayList<String>();
        myParameters.put(param, values);
      }

      values.add(params[i+1].toString());
    }
  }

  public void setLocalAddr(final String addr) {
    myLocalAddr = addr;
  }

  public void addToPathInfo(Object ...components) {
    for (Object component : components) {
      myPathInfo += "/" + component;
    }
  }

  public void setRemoteHost(final String remoteHost) {
    myRemoteHost = remoteHost;
  }

  public void setRemoteAddr(final String remoteAddr) {
    myRemoteAddr = remoteAddr;
  }

  public void setRemotePort(final int remotePort) {
    myRemotePort = remotePort;
  }

  public void setPathInfo(final String pathInfo) {
    myPathInfo = pathInfo;
  }

  public void setLocalPort(final int localPort) {
    myLocalPort = localPort;
  }

  public void setServerPort(final int serverPort) {
    myServerPort = serverPort;
  }

  public void setServletContext(final ServletContext servletContext) {
    myServletContext = servletContext;
  }
}
