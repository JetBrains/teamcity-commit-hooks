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

@file:Suppress("OverridingDeprecatedMember", "DEPRECATION")

package org.jetbrains.teamcity.github.util

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.servlet.ServletContext
import javax.servlet.http.HttpSession
import javax.servlet.http.HttpSessionContext

class FakeHttpSession : HttpSession {

    private val myId: String
    private val myCreationTime = System.currentTimeMillis()
    private val myAttrs = ConcurrentHashMap<String, Any>()
    @Volatile private var myMaxInactiveInterval: Int = 0
    @Volatile private var myServletContext: ServletContext? = null
    @Volatile
    var isInvalidated: Boolean = false
        private set

    init {
        myId = "" + ourCounter.incrementAndGet()
    }

    override fun getCreationTime(): Long {
        return myCreationTime
    }

    override fun getId(): String {
        return myId
    }

    override fun getLastAccessedTime(): Long {
        return System.currentTimeMillis()
    }

    override fun getServletContext(): ServletContext? {
        return myServletContext
    }

    override fun setMaxInactiveInterval(i: Int) {
        myMaxInactiveInterval = i
    }

    override fun getMaxInactiveInterval(): Int {
        return myMaxInactiveInterval
    }

    override fun getSessionContext(): HttpSessionContext {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    override fun getAttribute(string: String): Any? {
        checkValid()
        return myAttrs[string]
    }

    private fun checkValid() {
        if (isInvalidated) {
            throw IllegalStateException("Session invalidated")
        }
    }

    override fun getValue(string: String): Any {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    override fun getAttributeNames(): Enumeration<String> {
        checkValid()
        return Hashtable(myAttrs).keys()
    }

    override fun getValueNames(): Array<String> {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    override fun setAttribute(string: String, `object`: Any) {
        myAttrs.put(string, `object`)
    }

    override fun putValue(string: String, `object`: Any) {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    override fun removeAttribute(string: String) {
        myAttrs.remove(string)
    }

    override fun removeValue(string: String) {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    override fun invalidate() {
        myAttrs.clear()
        isInvalidated = true
    }

    override fun isNew(): Boolean {
        throw UnsupportedOperationException("Not implemented in " + javaClass.name)
    }

    companion object {
        private val ourCounter = AtomicInteger()
    }
}