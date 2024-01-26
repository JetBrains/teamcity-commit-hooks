

@file:Suppress("OverridingDeprecatedMember", "DEPRECATION")

package org.jetbrains.teamcity.github.util

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.servlet.ServletContext
import javax.servlet.http.HttpSession
import javax.servlet.http.HttpSessionContext

class FakeHttpSession : HttpSession {

    private val myId: String = "" + ourCounter.incrementAndGet()
    private val myCreationTime = System.currentTimeMillis()
    private val myAttrs = ConcurrentHashMap<String, Any>()
    @Volatile private var myMaxInactiveInterval: Int = 0
    @Volatile private var myServletContext: ServletContext? = null
    @Volatile
    var isInvalidated: Boolean = false
        private set

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
        myAttrs[string] = `object`
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