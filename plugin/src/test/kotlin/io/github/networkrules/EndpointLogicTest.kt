package io.github.networkrules

import io.github.networkrules.EndpointLogic.BaseUrl
import io.github.networkrules.EndpointLogic.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointLogicTest {
    private val base = EndpointLogic.parseBaseUrl("https://api.example.com/v2/")!!

    @Test fun `parses base urls`() {
        assertEquals(BaseUrl("HTTPS", "api.example.com", "", "/v2/"), base)
        assertEquals(BaseUrl("HTTP", "10.0.2.2", "8080", "/"), EndpointLogic.parseBaseUrl("http://10.0.2.2:8080"))
        assertEquals(BaseUrl("HTTPS", "api.example.com", "", "/v2/"), EndpointLogic.parseBaseUrl("https://api.example.com/v2"))
        assertEquals(BaseUrl("HTTPS", "\${API_HOST}", "", "/"), EndpointLogic.parseBaseUrl("https://\${API_HOST}/"))
        assertNull(EndpointLogic.parseBaseUrl("api.example.com"))
        assertNull(EndpointLogic.parseBaseUrl(""))
    }

    @Test fun `path starts with a wildcard so any base path matches`() {
        assertEquals(Endpoint("HTTPS", "api.example.com", "", "*/users/*", ""), EndpointLogic.resolve(base, "users/{id}"))
        assertEquals("*/v1/profile", EndpointLogic.resolve(base, "/v1/profile").path)
        assertEquals("*/users/*/posts/*", EndpointLogic.resolve(null, "users/{userId}/posts/{postId}").path)
    }

    @Test fun `the base url's path is not used`() {
        val withPath = EndpointLogic.parseBaseUrl("https://api.example.com/mobileapps/x/int/")
        assertEquals("*/trips", EndpointLogic.resolve(withPath, "trips").path)
    }

    @Test fun `no base url leaves the host empty`() {
        assertEquals(Endpoint("HTTPS", "", "", "*/trips/*", ""), EndpointLogic.resolve(null, "trips/{id}"))
    }

    @Test fun `full urls are used as is`() {
        assertEquals(
            Endpoint("HTTP", "other.example.com", "81", "/x/*", ""),
            EndpointLogic.resolve(base, "http://other.example.com:81/x/{id}"),
        )
    }

    @Test fun `fixed query becomes a wildcard match`() {
        val e = EndpointLogic.resolve(base, "search?type=user")
        assertEquals("*/search", e.path)
        assertEquals("*type=user*", e.query)
    }

    @Test fun `empty path from a dynamic Url matches anything`() {
        assertEquals("*", EndpointLogic.resolve(base, "").path)
    }

    @Test fun `rule names and methods`() {
        assertEquals("GET users/{id}", EndpointLogic.ruleName("get", "users/{id}"))
        assertEquals("GET (dynamic URL)", EndpointLogic.ruleName("GET", ""))
        assertEquals("PATCH", EndpointLogic.studioMethod("patch"))
        assertEquals("ANY", EndpointLogic.studioMethod("PROPFIND"))
    }

    @Test fun `unique names`() {
        assertEquals("A", EndpointLogic.uniqueName("A", setOf("B")))
        assertEquals("A (3)", EndpointLogic.uniqueName("A", setOf("A", "A (2)")))
    }
}
