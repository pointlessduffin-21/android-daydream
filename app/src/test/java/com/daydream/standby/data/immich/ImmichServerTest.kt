package com.daydream.standby.data.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichServerTest {

    private fun normalized(input: String) = ImmichServer.normalizeBaseUrl(input)?.toString()

    @Test fun `adds https when scheme missing`() = assertEquals("https://photos.example.com/", normalized("photos.example.com"))

    @Test fun `bare LAN address defaults to http`() {
        assertEquals("http://192.168.1.10:2283/", normalized("192.168.1.10:2283"))
        assertEquals("http://nas.local:2283/", normalized("nas.local:2283"))
        assertEquals("http://nas:2283/", normalized("nas:2283"))
    }

    @Test fun `explicit https on LAN is kept`() = assertEquals("https://192.168.1.10/", normalized("https://192.168.1.10"))

    @Test fun `keeps explicit http and port`() = assertEquals("http://192.168.1.10:2283/", normalized("http://192.168.1.10:2283"))

    @Test fun `strips trailing slash and api suffix`() {
        assertEquals("https://host/", normalized("https://host/api/"))
        assertEquals("https://host/", normalized("https://host/API"))
    }

    @Test fun `preserves sub-path deployments`() = assertEquals("https://host/immich/", normalized(" https://host/immich/api "))

    @Test fun `drops query and fragment`() = assertEquals("https://host/", normalized("https://host/?x=1#frag"))

    @Test fun `rejects blank and malformed input`() {
        assertNull(normalized(""))
        assertNull(normalized("   "))
        assertNull(normalized("not a url"))
        assertNull(normalized("ftp://host"))
    }

    @Test fun `from requires a well-formed api key`() {
        assertNull(ImmichServer.from("https://host", "  "))
        assertNull(ImmichServer.from("https://host", "abc\ndef"))
        assertNull(ImmichServer.from("https://host", "abc def"))
        assertNull(ImmichServer.from("https://host", "kéy"))
        assertEquals("key", ImmichServer.from("https://host", " key ")?.apiKey)
    }

    @Test fun `endpoint and preview urls are built under api`() {
        val server = ImmichServer.from("https://host/immich", "k")!!
        assertEquals("https://host/immich/api/albums", server.endpoint("albums").toString())
        assertEquals("https://host/immich/api/assets/abc-123/thumbnail?size=preview", server.previewUrl("abc-123").toString())
    }

    @Test fun `asset ids are path-encoded`() {
        val server = ImmichServer.from("https://host", "k")!!
        assertEquals("https://host/api/assets/..%2Fadmin/thumbnail?size=preview", server.previewUrl("../admin").toString())
    }

    @Test fun `insecure remote only for http to public hosts`() {
        assertTrue(ImmichServer.from("http://photos.example.com", "k")!!.isInsecureRemote)
        assertFalse(ImmichServer.from("https://photos.example.com", "k")!!.isInsecureRemote)
        assertFalse(ImmichServer.from("http://192.168.0.5:2283", "k")!!.isInsecureRemote)
        assertFalse(ImmichServer.from("http://nas.local", "k")!!.isInsecureRemote)
    }

    @Test fun `id validation rejects path tricks`() {
        assertTrue(ImmichServer.isValidId("3f2b9c1e-8a4d-4b7e-9c2a-1d5e6f7a8b9c"))
        listOf("", "..", "../x", "a/b", "a?b", "a b", "x".repeat(65)).forEach { assertFalse(it, ImmichServer.isValidId(it)) }
    }

    @Test fun `local host detection covers private ranges`() {
        listOf("::1", "fd12:3456::1", "fe80::1", "NAS.LOCAL", "nas", "immich", "localhost", "10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "box.lan", "x.home.arpa")
            .forEach { assertTrue(it, ImmichServer.isLocalHost(it)) }
        listOf(
            "8.8.8.8", "172.32.0.1", "172.15.0.1", "example.com", "100.128.0.1",
            // DNS names that merely embed a private IP must not count as local.
            "10.0.0.5.nip.io", "192.168.1.2.evil.com", "999.168.1.1",
            "2001:db8::1",
        )
            .forEach { assertFalse(it, ImmichServer.isLocalHost(it)) }
    }
}
