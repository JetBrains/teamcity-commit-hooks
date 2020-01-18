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
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeHttpSession implements HttpSession {

  private static final AtomicInteger ourCounter = new AtomicInteger();

  private final String myId;
  private final long myCreationTime = System.currentTimeMillis();
  private final Map<String, Object> myAttrs = new ConcurrentHashMap<>();
  private volatile int myMaxInactiveInterval;
  private volatile ServletContext myServletContext;
  private volatile boolean myInvalidated;

  public FakeHttpSession() {
    myId = "" + ourCounter.incrementAndGet();
  }

  public void setServletContext(@NotNull ServletContext servletContext) {
    myServletContext = servletContext;
  }

  public long getCreationTime() {
    return myCreationTime;
  }

  public String getId() {
    return myId;
  }

  public long getLastAccessedTime() {
    return System.currentTimeMillis();
  }

  public ServletContext getServletContext() {
    return myServletContext;
  }

  public void setMaxInactiveInterval(int i) {
    myMaxInactiveInterval = i;
  }

  public int getMaxInactiveInterval() {
    return myMaxInactiveInterval;
  }

  public HttpSessionContext getSessionContext() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public Object getAttribute(String string) {
    checkValid();
    return myAttrs.get(string);
  }

  private void checkValid() {
    if (myInvalidated) {
      throw new IllegalStateException("Session invalidated");
    }
  }

  public boolean isInvalidated() {
    return myInvalidated;
  }

  public Object getValue(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public Enumeration<String> getAttributeNames() {
    checkValid();
    return new Hashtable<>(myAttrs).keys();
  }

  public String[] getValueNames() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void setAttribute(String string, Object object) {
    myAttrs.put(string, object);
  }

  public void putValue(String string, Object object) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void removeAttribute(String string) {
    myAttrs.remove(string);
  }

  public void removeValue(String string) {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }

  public void invalidate() {
    myAttrs.clear();
    myInvalidated = true;
  }

  public boolean isNew() {
    throw new UnsupportedOperationException("Not implemented in " + getClass().getName());
  }
}
