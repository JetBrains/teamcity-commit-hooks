/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.Converter;
import org.jetbrains.annotations.NotNull;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeHttpServletResponse implements HttpServletResponse {

  private volatile ByteArrayOutputStream myStream = new ByteArrayOutputStream();
  private volatile String myContentType;
  private volatile String myRedirectURL;
  private final List<Cookie> myCookies = new CopyOnWriteArrayList<>();
  private final ListMultimap<String, Object> myHeaders = Multimaps.synchronizedListMultimap(LinkedListMultimap.create());
  private volatile int myStatus;
  private volatile String myStatusText;
  private volatile String myEncoding = "UTF-8";
  private volatile PrintWriter myPrintWriter;

  @NotNull
  protected ListMultimap<String, Object> getHeadersMap() {
    return myHeaders;
  }

  public void addCookie(Cookie cookie) {
    if (cookie.getMaxAge() != 0) {
      myCookies.add(cookie);
    }
    else {
      Iterator<Cookie> it = myCookies.iterator();
      while (it.hasNext()) {
        Cookie storedCookie = it.next();
        if (storedCookie.getName().equals(cookie.getName())) {
          it.remove();
        }
      }
    }
  }

  public void addDateHeader(String name, long value) {
    myHeaders.put(name, value);
  }

  public void addHeader(String name, String value) {
    myHeaders.put(name, value);
  }

  public void addIntHeader(String name, int value) {
    myHeaders.put(name, value);
  }

  public boolean containsHeader(String name) {
    return myHeaders.containsKey(name);
  }

  public String getHeader(String name) {
    final List<Object> objects = myHeaders.get(name);
    return objects.isEmpty() ? null : (String)objects.get(0);
  }

  public Collection<String> getHeaders(final String s) {
    return CollectionsUtil.convertCollection(myHeaders.get(s), new Converter<String, Object>() {
      public String createFrom(@NotNull final Object source) {
        return (String)source;
      }
    });
  }

  public Collection<String> getHeaderNames() {
    return myHeaders.keySet();
  }

  public String encodeRedirectURL(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public String encodeRedirectUrl(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public String encodeURL(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public String encodeUrl(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void sendError(int i) {
    myStatus = i;
  }

  public void sendError(int i, String string) {
    myStatus = i;
    myStatusText = string;
  }

  public void sendRedirect(String string) {
    myRedirectURL = string;
  }

  public String getRedirectURL() {
    return myRedirectURL;
  }

  public void setDateHeader(String name, long value) {
    myHeaders.removeAll(name);
    myHeaders.put(name, new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).format(new Date(value)));
  }

  public void setHeader(String name, String value) {
    myHeaders.removeAll(name);
    myHeaders.put(name, value);
  }

  public void setIntHeader(String name, int value) {
    myHeaders.removeAll(name);
    myHeaders.put(name, value);
  }

  public void setStatus(int status) {
    myStatus = status;
  }

  public void setStatus(int status, String string) {
    myStatus = status;
    myStatusText = string;
  }

  public int getStatus() {
    return myStatus;
  }

  public String getStatusText() {
    return myStatusText;
  }

  public void flushBuffer() {
    if (myPrintWriter != null) {
      myPrintWriter.flush();
    }
  }

  public int getBufferSize() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public String getCharacterEncoding() {
    return myEncoding;
  }

  public String getContentType() {
    return myContentType;
  }

  public Locale getLocale() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public ServletOutputStream getOutputStream() throws IOException {
    return new ServletOutputStream() {
      @Override
      public boolean isReady() {
        return false;
      }

      @Override
      public void setWriteListener(final WriteListener listener) {

      }

      @Override
      public void write(int b) {
        myStream.write(b);
      }
    };
  }

  public PrintWriter getWriter() throws IOException {
    if (myPrintWriter == null) {
      myPrintWriter = new PrintWriter(new OutputStreamWriter(myStream, myEncoding), true);
    }
    return myPrintWriter;
  }

  public boolean isCommitted() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void reset() {
    myStream = new ByteArrayOutputStream();
    try {
      myPrintWriter = new PrintWriter(new OutputStreamWriter(myStream, myEncoding), true);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
    myContentType = null;
    myCookies.clear();
    myStatus = -1;
    myStatusText = null;
    myHeaders.clear();
  }

  public void resetBuffer() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void setBufferSize(int i) {
  }

  public void setCharacterEncoding(String encoding) {
    myEncoding = encoding;
  }

  public void setContentLength(int i) {
  }

  @Override
  public void setContentLengthLong(final long length) {

  }

  public void setContentType(String string) {
    myContentType = string;
  }

  public void setLocale(Locale locale) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public List<Cookie> getCookies() {
    return myCookies;
  }

  public String getReturnedContent() {
    if (myPrintWriter != null) {
      myPrintWriter.flush();
    }
    try {
      return myStream.toString(myEncoding);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public byte[] getReturnedBytes() {
    return myStream.toByteArray();
  }

  public void clearContent() {
    if (myPrintWriter != null) {
      myPrintWriter.flush();
    }
    myStream.reset();
  }

}
