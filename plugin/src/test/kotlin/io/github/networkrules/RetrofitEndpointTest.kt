package io.github.networkrules

import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement

/** Reads real (in-memory) Java Retrofit interfaces, with stubs for Retrofit, Gson and Moshi. */
class RetrofitEndpointTest : LightJavaCodeInsightFixtureTestCase() {
    // The test JVM's own JDK, so java.lang.String, java.util.List etc. resolve as they do in the IDE.
    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
    }

    override fun setUp() {
        VfsRootAccess.allowRootAccess(testRootDisposable, System.getProperty("java.home"))
        super.setUp()
        for (a in listOf("GET", "POST", "DELETE")) {
            myFixture.addClass("package retrofit2.http; public @interface $a { String value() default \"\"; }")
        }
        myFixture.addClass("package retrofit2.http; public @interface HTTP { String method(); String path() default \"\"; boolean hasBody() default false; }")
        myFixture.addClass("package retrofit2; public interface Call<T> {}")
        myFixture.addClass("package retrofit2; public class Response<T> {}")
        myFixture.addClass("package com.google.gson.annotations; public @interface SerializedName { String value(); }")
        myFixture.addClass("package com.squareup.moshi; public @interface Json { String name(); }")
        myFixture.addClass("package okhttp3; public class ResponseBody {}")
        myFixture.addClass("package io.reactivex; public abstract class Observable<T> {}")
        myFixture.addClass("package io.reactivex; public abstract class Completable {}")
        myFixture.addClass("package io.reactivex.rxjava3.core; public abstract class Observable<T> {}")
        myFixture.addClass("package retrofit2.adapter.rxjava2; public final class Result<T> {}")
        myFixture.addClass(
            """
            package app;
            import java.util.List; import java.util.Map;
            import com.google.gson.annotations.SerializedName;
            import com.squareup.moshi.Json;
            public class User {
                public static final String TABLE = "users";
                private transient int cache;
                public long id;
                @SerializedName("display_name") public String name;
                @Json(name = "is_admin") public Boolean admin;
                public Role role;
                public List<Address> addresses;
                public Map<String, Integer> scores;
                public User manager;
            }
            """.trimIndent(),
        )
        myFixture.addClass("package app; public enum Role { @com.google.gson.annotations.SerializedName(\"owner\") OWNER, MEMBER }")
        myFixture.addClass("package app; public class Address { public String city; public double lat; }")
        myFixture.addClass("package app; public class Page<T> { public int total; public java.util.List<T> items; }")
    }

    private fun api(body: String): String = """
        package app;
        import retrofit2.*; import retrofit2.http.*;
        public interface Api { $body }
    """.trimIndent()

    private fun endpoint(method: String): RetrofitEndpoint? {
        val cls = myFixture.findClass("app.Api")
        val psi: PsiMethod = cls.findMethodsByName(method, false).single()
        return ReadAction.compute<RetrofitEndpoint?, Exception> { RetrofitEndpoints.of(psi.toUElement() as UMethod) }
    }

    fun `test reads method and path and unwraps Call`() {
        myFixture.addClass(api("""@GET("users/{id}") Call<User> user(String id);"""))
        val e = endpoint("user")!!
        assertEquals("GET", e.method)
        assertEquals("users/{id}", e.path)
        assertEquals("app.User", e.responseType?.canonicalText)
    }

    fun `test builds sample json with serialized names`() {
        myFixture.addClass(api("""@GET("me") Call<Response<User>> me();"""))
        val json = SampleJson.of(endpoint("me")!!.responseType).asJsonObject
        assertEquals(
            setOf("id", "display_name", "is_admin", "role", "addresses", "scores", "manager"),
            json.keySet(),
        )
        assertEquals(0, json["id"].asInt)
        assertEquals("display_name", json["display_name"].asString)
        assertEquals(false, json["is_admin"].asBoolean)
        assertEquals("owner", json["role"].asString)
        assertEquals(JsonParser.parseString("""[{"city":"city","lat":0.0}]"""), json["addresses"])
        assertEquals(JsonParser.parseString("""{"key":0}"""), json["scores"])
        assertTrue("recursive type stops", json["manager"].isJsonNull)
    }

    fun `test substitutes generic wrappers`() {
        myFixture.addClass(api("""@GET("users") Call<Page<Address>> users();"""))
        val json = SampleJson.of(endpoint("users")!!.responseType)
        assertEquals(JsonParser.parseString("""{"total":0,"items":[{"city":"city","lat":0.0}]}"""), json)
    }

    fun `test lists at the top level`() {
        myFixture.addClass(api("""@GET("addresses") Call<java.util.List<Address>> addresses();"""))
        assertEquals(JsonParser.parseString("""[{"city":"city","lat":0.0}]"""), SampleJson.of(endpoint("addresses")!!.responseType))
    }

    fun `test HTTP annotation`() {
        myFixture.addClass(api("""@HTTP(method = "DELETE", path = "users/{id}", hasBody = true) Call<Void> remove(String id);"""))
        val e = endpoint("remove")!!
        assertEquals("DELETE", e.method)
        assertEquals("users/{id}", e.path)
        assertNull("Void has no body", e.responseType)
    }

    fun `test ResponseBody has no sample`() {
        myFixture.addClass(api("""@POST("upload") Call<okhttp3.ResponseBody> upload();"""))
        assertNull(endpoint("upload")!!.responseType)
    }

    fun `test non retrofit methods are ignored`() {
        myFixture.addClass(api("""Call<User> notAnEndpoint();"""))
        assertNull(endpoint("notAnEndpoint"))
    }

    fun `test constant path`() {
        myFixture.addClass("package app; public class Paths { public static final String ME = \"v1/me\"; }")
        myFixture.addClass(api("""@GET(Paths.ME) Call<User> me();"""))
        assertEquals("v1/me", endpoint("me")!!.path)
    }

    fun `test unwraps RxJava 2 Observable`() {
        myFixture.addClass(api("""@GET("example") io.reactivex.Observable<Address> example();"""))
        assertEquals(JsonParser.parseString("""{"city":"city","lat":0.0}"""), SampleJson.of(endpoint("example")!!.responseType))
    }

    fun `test unwraps RxJava 3 Observable`() {
        myFixture.addClass(api("""@GET("example") io.reactivex.rxjava3.core.Observable<Address> example();"""))
        assertEquals("app.Address", endpoint("example")!!.responseType?.canonicalText)
    }

    fun `test unwraps Observable of Response and Result`() {
        myFixture.addClass(
            api(
                """
                @GET("a") io.reactivex.Observable<Response<Address>> a();
                @GET("b") io.reactivex.Observable<retrofit2.adapter.rxjava2.Result<Page<Address>>> b();
                """,
            ),
        )
        assertEquals("app.Address", endpoint("a")!!.responseType?.canonicalText)
        assertEquals(
            JsonParser.parseString("""{"total":0,"items":[{"city":"city","lat":0.0}]}"""),
            SampleJson.of(endpoint("b")!!.responseType),
        )
    }

    fun `test Completable has no body`() {
        myFixture.addClass(api("""@POST("logout") io.reactivex.Completable logout();"""))
        assertNull(endpoint("logout")!!.responseType)
    }

    // ---- the service's base URL, from the code that builds it ----

    private fun addNetworkStubs() {
        myFixture.addClass("package app.net; public class BaseUrl { public final String secured; public BaseUrl(Object context) { secured = System.getenv(\"X\"); } }")
        myFixture.addClass("package app.net; public class NetworkBuild { public NetworkBuild(Object context, String baseUrl) {} public <T> T create(Class<T> c) { return null; } }")
        myFixture.addClass("package app; public interface DrivewiseApi { @retrofit2.http.GET(\"trips/{id}\") retrofit2.Call<Address> trip(String id); }")
        myFixture.addClass("package app; public interface OtherApi { @retrofit2.http.GET(\"x\") retrofit2.Call<Address> x(); }")
    }

    private fun serviceBase(service: String): ServiceBase? = ReadAction.compute<ServiceBase?, Exception> {
        ServiceBaseUrlResolver.find(myFixture.findClass(service))
    }

    private fun drivewiseParts() = listOf(
        EndpointLogic.UrlPart.Dynamic("new BaseUrl(context).secured"),
        EndpointLogic.UrlPart.Text("v1.0/drivewise/api"),
    )

    fun `test base url from a custom builder in a module`() {
        addNetworkStubs()
        myFixture.addClass(
            """
            package app.di;
            import app.*; import app.net.*;
            public class ClassicRemoteDataSourceModule {
                public DrivewiseApi provideDrivewise(Object context) {
                    return new NetworkBuild(context, new BaseUrl(context).secured + "v1.0/drivewise/api").create(DrivewiseApi.class);
                }
                public OtherApi provideOther(Object context) {
                    return new NetworkBuild(context, new BaseUrl(context).secured + "v2/other/").create(OtherApi.class);
                }
            }
            """.trimIndent(),
        )
        val found = serviceBase("app.DrivewiseApi")!!
        assertEquals(drivewiseParts(), found.parts)
        assertEquals("ClassicRemoteDataSourceModule.provideDrivewise (ClassicRemoteDataSourceModule.java)", found.location)
        assertEquals("*/v2/other/x", EndpointLogic.resolveService(null, serviceBase("app.OtherApi")!!.parts, "x").path)

        // End to end: the annotation path is joined after the service base.
        val endpoint = endpoint2("app.DrivewiseApi", "trip")
        assertEquals("*/v1.0/drivewise/api/trips/*", EndpointLogic.resolveService(null, found.parts, endpoint.path).path)
    }

    fun `test base url through a field and a constant`() {
        addNetworkStubs()
        myFixture.addClass(
            """
            package app.di;
            import app.*; import app.net.*;
            public class ClassicRemoteDataSourceModule {
                static final String DRIVEWISE = "v1.0/drivewise/api";
                private final NetworkBuild networkBuild;
                public ClassicRemoteDataSourceModule(Object context) {
                    networkBuild = null;
                }
                private final NetworkBuild build = new NetworkBuild(null, new BaseUrl(context()).secured + DRIVEWISE);
                Object context() { return null; }
                public DrivewiseApi provide() { return build.create(DrivewiseApi.class); }
            }
            """.trimIndent(),
        )
        val parts = serviceBase("app.DrivewiseApi")!!.parts
        assertEquals(EndpointLogic.UrlPart.Text("v1.0/drivewise/api"), parts.last())
        assertTrue(parts.first() is EndpointLogic.UrlPart.Dynamic)
    }

    fun `test base url through a local variable`() {
        addNetworkStubs()
        myFixture.addClass(
            """
            package app.di;
            import app.*; import app.net.*;
            public class Module {
                public DrivewiseApi provide(Object context) {
                    String base = new BaseUrl(context).secured;
                    String url = base + "v1.0/drivewise/api";
                    return new NetworkBuild(context, url).create(DrivewiseApi.class);
                }
            }
            """.trimIndent(),
        )
        assertEquals(drivewiseParts(), serviceBase("app.DrivewiseApi")!!.parts)
    }

    fun `test constant retrofit base url`() {
        myFixture.addClass("package retrofit2; public class Retrofit { public static class Builder { public Builder baseUrl(String u) { return this; } public Retrofit build() { return null; } } public <T> T create(Class<T> c) { return null; } }")
        myFixture.addClass("package app; public interface PlainApi { @retrofit2.http.GET(\"users\") retrofit2.Call<Address> users(); }")
        myFixture.addClass(
            """
            package app.di;
            public class Net {
                static final String HOST = "https://api.example.com/";
                public app.PlainApi api() {
                    return new retrofit2.Retrofit.Builder().baseUrl(HOST + "v3/").build().create(app.PlainApi.class);
                }
            }
            """.trimIndent(),
        )
        val parts = serviceBase("app.PlainApi")!!.parts
        assertEquals(listOf(EndpointLogic.UrlPart.Text("https://api.example.com/v3/")), parts)
        assertEquals("/v3/users", EndpointLogic.resolveService(null, parts, "users").path)
    }

    fun `test no usage means no service base`() {
        addNetworkStubs()
        assertNull(serviceBase("app.DrivewiseApi"))
    }

    private fun endpoint2(cls: String, method: String): RetrofitEndpoint {
        val psi = myFixture.findClass(cls).findMethodsByName(method, false).single()
        return ReadAction.compute<RetrofitEndpoint?, Exception> { RetrofitEndpoints.of(psi.toUElement() as UMethod) }!!
    }
}
