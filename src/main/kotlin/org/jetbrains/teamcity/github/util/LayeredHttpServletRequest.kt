@file:Suppress("OverridingDeprecatedMember")

package org.jetbrains.teamcity.github.util

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper
import javax.servlet.http.HttpSession

class LayeredHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    @Volatile private var mySession: FakeHttpSession? = null
    private val myAttributes = ConcurrentHashMap<String, Any>()

    override fun getSession() = getSession(true)!!

    override fun getSession(create: Boolean): HttpSession? {
        val session = mySession
        if (session != null && session.isInvalidated) {
            mySession = null
        }

        if (mySession == null && create) {
            mySession = FakeHttpSession()
        }

        return mySession
    }


    override fun getAttribute(string: String): Any? {
        return myAttributes[string] ?: super.getAttribute(string)
    }

    override fun getAttributeNames(): Enumeration<String> {
        return Collections.enumeration(myAttributes.keys + super.getAttributeNames().toList())
    }

    override fun removeAttribute(string: String) {
        myAttributes.remove(string) ?: super.removeAttribute(string)
    }

    override fun setAttribute(string: String, value: Any) {
        myAttributes.put(string, value)
    }

    override fun getRequestedSessionId(): String? {
        return null
    }

    override fun isRequestedSessionIdValid(): Boolean {
        return false
    }

    override fun isRequestedSessionIdFromCookie(): Boolean {
        return false
    }

    override fun isRequestedSessionIdFromURL(): Boolean {
        return false
    }

    override fun isRequestedSessionIdFromUrl(): Boolean {
        return false
    }

    /**
     * API 3.1
     */
    @Suppress("unused")
    override fun changeSessionId(): String? {
        return null
    }

}