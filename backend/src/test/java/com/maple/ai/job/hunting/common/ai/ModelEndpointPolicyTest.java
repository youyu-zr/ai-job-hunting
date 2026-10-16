package com.maple.ai.job.hunting.common.ai;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEndpointPolicyTest {

    @Test
    void acceptsHttpsPublicEndpoint() {
        assertDoesNotThrow(() -> ModelEndpointPolicy.validateBaseUrl(
                "https://api.example.com/v1", false, false));
    }

    @Test
    void rejectsHttpUnlessExplicitlyEnabled() {
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "http://api.example.com/v1", false, false));
        assertDoesNotThrow(() -> ModelEndpointPolicy.validateBaseUrl(
                "http://api.example.com/v1", false, true));
    }

    @Test
    void rejectsCredentialsAndUnexpectedUrlParts() {
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "https://user:secret@api.example.com/v1", false, false));
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "https://api.example.com/v1?redirect=http://127.0.0.1", false, false));
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "https://api.example.com/v1#fragment", false, false));
    }

    @Test
    void rejectsPrivateIpLiteralsUnlessExplicitlyEnabled() {
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "https://127.0.0.1/v1", false, false));
        assertThrows(IllegalArgumentException.class, () -> ModelEndpointPolicy.validateBaseUrl(
                "https://[::1]/v1", false, false));
        assertDoesNotThrow(() -> ModelEndpointPolicy.validateBaseUrl(
                "http://127.0.0.1:1234/v1", true, true));
    }

    @Test
    void rejectsAbsoluteOrAuthorityCompletionPaths() {
        assertDoesNotThrow(() -> ModelEndpointPolicy.validateCompletionPath("/v1/chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpointPolicy.validateCompletionPath("https://127.0.0.1/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpointPolicy.validateCompletionPath("//127.0.0.1/admin"));
    }

    @Test
    void classifiesNonPublicAddressRangesAsBlocked() throws Exception {
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("192.0.2.1")));
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("::1")));
        assertFalse(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("fd00::1")));
        assertTrue(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(ModelEndpointPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void dnsResolverRejectsLoopbackResults() {
        assertThrows(java.net.UnknownHostException.class,
                () -> ModelEndpointPolicy.publicAddressResolver(false).resolve("127.0.0.1"));
    }

    @Test
    void rejectsInvalidAndOversizedUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpointPolicy.validateBaseUrl("not a url", false, false));
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpointPolicy.validateBaseUrl("https://", false, false));
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpointPolicy.validateBaseUrl("https://" + "a".repeat(2048), false, false));
    }

    @Test
    void parsesValidatedBaseUrl() {
        URI uri = ModelEndpointPolicy.validateBaseUrl("https://api.example.com/v1", false, false);
        assertEquals("api.example.com", uri.getHost());
    }
}
