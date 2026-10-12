package com.leaveapp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

// Plain unit test: null when there are no cookies at all or none by that name; the value
// (first match) otherwise; name matching is exact and case-sensitive.
public class CookieUtilTest {

    /* Variables */

    private final MockHttpServletRequest req = new MockHttpServletRequest();

    /* Public Methods */

    @Test
    public void read_noCookiesAtAll_null() {
        assertNull(CookieUtil.read(req, "sid"));
    }

    @Test
    public void read_noCookieWithThatName_null() {
        req.setCookies(new Cookie("other", "x"), new Cookie("another", "y"));
        assertNull(CookieUtil.read(req, "sid"));
    }

    @Test
    public void read_matchingCookie_returnsValue() {
        req.setCookies(new Cookie("other", "x"), new Cookie("sid", "abc"));
        assertEquals("abc", CookieUtil.read(req, "sid"));
    }

    @Test
    public void read_duplicateNames_firstWins() {
        req.setCookies(new Cookie("sid", "first"), new Cookie("sid", "second"));
        assertEquals("first", CookieUtil.read(req, "sid"));
    }

    @Test
    public void read_nameIsCaseSensitive() {
        req.setCookies(new Cookie("SID", "abc"));
        assertNull(CookieUtil.read(req, "sid"));
    }

    @Test
    public void read_emptyValue_returnsEmptyString() {
        req.setCookies(new Cookie("sid", ""));
        assertEquals("", CookieUtil.read(req, "sid"));
    }
}
