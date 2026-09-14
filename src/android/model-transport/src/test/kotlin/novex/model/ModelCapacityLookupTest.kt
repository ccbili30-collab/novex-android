package novex.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ModelCapacityLookupTest {
    @Test fun catalogPathCannotChangeAuthority() {
        val source=URI("https://example.invalid//other.invalid/chat/completions")
        assertEquals("example.invalid",ModelCapacityLookup.catalogUrl(source)!!.host)
        assertEquals("//other.invalid/models",ModelCapacityLookup.catalogUrl(source)!!.path)
    }
    @Test fun exactModelAndExplicitCapacityOnly() {
        val json="""{"data":[{"id":"million","context_length":1000000},{"id":"small","context_length":32000,"top_provider":{"context_length":16000}}]}"""
        assertEquals(1000000L,ModelCapacityLookup.parse(json,"million"))
        assertEquals(16000L,ModelCapacityLookup.parse(json,"small"))
        assertNull(ModelCapacityLookup.parse(json,"million-alias"))
        for(value in listOf("null","0","-1","1024","1.5","\"1000000\"","9223372036854775808")) {
            assertNull(ModelCapacityLookup.parse("""{"data":[{"id":"m","context_length":$value}]}""","m"))
        }
        assertNull(ModelCapacityLookup.parse("""{"data":[{"id":"m","max_tokens":1000000}]}""","m"))
        assertNull(ModelCapacityLookup.parse("""{"data":[{"id":"m","context_length":64000},{"id":"m","context_length":128000}]}""","m"))
    }
    @Test fun readSameServiceWithoutGenerationOrRedirect() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val base="http://127.0.0.1:${server.address.port}"
        var status=200;var requests=0;var redirected=0
        server.createContext("/v1/models") {exchange ->
            requests++;assertEquals("GET",exchange.requestMethod)
            assertEquals("Bearer fixture-key",exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.set("Location","$base/elsewhere")
            val bytes="""{"data":[{"id":"m","context_length":1000000}]}""".toByteArray()
            exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        }
        server.createContext("/elsewhere"){redirected++;it.close()};server.start()
        try {
            val endpoint=ModelEndpoint(URI("$base/v1/chat/completions"),"fixture-key")
            assertEquals(CapacityLookupResult.Found(1000000,URI("$base/v1/models")),ModelCapacityLookup().read(endpoint,"m"))
            status=302;assertEquals(CapacityLookupResult.Rejected(302),ModelCapacityLookup().read(endpoint,"m"))
            status=429;assertEquals(CapacityLookupResult.Rejected(429),ModelCapacityLookup().read(endpoint,"m"))
            status=200;assertEquals(CapacityLookupResult.Unavailable,ModelCapacityLookup(maxBytes=8).read(endpoint,"m"))
            assertEquals(4,requests);assertEquals(0,redirected)
            assertEquals(CapacityLookupResult.Unknown,ModelCapacityLookup().read(ModelEndpoint(URI("$base/custom"),null),"m"))
            assertEquals(4,requests)
        }finally {server.stop(0)}
    }
}
