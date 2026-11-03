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

    @Test fun `relative paths are appended to the base path`() {
        assertEquals(Endpoint("HTTPS", "api.example.com", "", "/v2/users/*", ""), EndpointLogic.resolve(base, "users/{id}"))
    }

    @Test fun `absolute paths replace the base path`() {
        assertEquals("/v1/profile", EndpointLogic.resolve(base, "/v1/profile").path)
    }

    @Test fun `full urls replace the base url`() {
        assertEquals(
            Endpoint("HTTP", "other.example.com", "81", "/x/*", ""),
            EndpointLogic.resolve(base, "http://other.example.com:81/x/{id}"),
        )
    }

    @Test fun `fixed query becomes a wildcard match`() {
        val e = EndpointLogic.resolve(base, "search?type=user")
        assertEquals("/v2/search", e.path)
        assertEquals("*type=user*", e.query)
    }

    @Test fun `empty path from a dynamic Url matches anything under the base`() {
        assertEquals("/v2/*", EndpointLogic.resolve(base, "").path)
    }

    @Test fun `no base url leaves the host empty`() {
        assertEquals(Endpoint("HTTPS", "", "", "/users/*/posts/*", ""), EndpointLogic.resolve(null, "users/{userId}/posts/{postId}"))
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

    // ---- service base URLs found in code ----

    private val env = EndpointLogic.UrlPart.Dynamic("BaseUrl(context).secured")
    private fun text(s: String) = EndpointLogic.UrlPart.Text(s)
    private val drivewise = listOf(env, text("v1.0/drivewise/api"))

    @Test fun `environment part becomes a wildcard when no environment is set`() {
        val e = EndpointLogic.resolveService(null, drivewise, "trips/{tripId}")
        assertEquals(Endpoint("HTTPS", "", "", "*/v1.0/drivewise/api/trips/*", ""), e)
    }

    @Test fun `environment host without a path still matches any environment prefix`() {
        val e = EndpointLogic.resolveService(EndpointLogic.parseBaseUrl("https://api.example.com/"), drivewise, "trips")
        assertEquals(Endpoint("HTTPS", "api.example.com", "", "*/v1.0/drivewise/api/trips", ""), e)
    }

    @Test fun `environment path from settings is used when set`() {
        val e = EndpointLogic.resolveService(EndpointLogic.parseBaseUrl("https://api.example.com/mobileapps/*/"), drivewise, "/trips")
        assertEquals("/mobileapps/*/v1.0/drivewise/api/trips", e.path)
    }

    @Test fun `annotation path is joined even when it starts with a slash`() {
        assertEquals("*/v1.0/drivewise/api/score", EndpointLogic.resolveService(null, drivewise, "/score").path)
        assertEquals("*/v1.0/drivewise/api/score", EndpointLogic.resolveService(null, listOf(env, text("v1.0/drivewise/api/")), "score").path)
    }

    @Test fun `a constant base url in code is used as is`() {
        val e = EndpointLogic.resolveService(null, listOf(text("https://api.example.com/v1/")), "users/{id}")
        assertEquals(Endpoint("HTTPS", "api.example.com", "", "/v1/users/*", ""), e)
    }

    @Test fun `later runtime parts become wildcards`() {
        val e = EndpointLogic.resolveService(null, listOf(env, text("v"), EndpointLogic.UrlPart.Dynamic("version"), text("/x")), "y?z=1")
        assertEquals("*/v*/x/y", e.path)
        assertEquals("*z=1*", e.query)
    }

    @Test fun `a full url annotation wins over the service base`() {
        assertEquals("other.example.com", EndpointLogic.resolveService(null, drivewise, "https://other.example.com/x").host)
    }

    @Test fun `describe shows the expression`() {
        assertEquals("BaseUrl(context).secured + \"v1.0/drivewise/api\"", EndpointLogic.describe(listOf(env, text("v1.0/"), text("drivewise/api"))))
    }
}
