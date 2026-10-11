package com.maple.ai.job.hunting.common.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AIConfigHelperTest {

    @Test
    void userTimeoutIsClampedToSafeBounds() {
        assertEquals(60, AIConfigHelper.boundedTimeoutSeconds(null));
        assertEquals(1, AIConfigHelper.boundedTimeoutSeconds(0));
        assertEquals(1, AIConfigHelper.boundedTimeoutSeconds(-500));
        assertEquals(120, AIConfigHelper.boundedTimeoutSeconds(121));
        assertEquals(120, AIConfigHelper.boundedTimeoutSeconds(Integer.MAX_VALUE));
        assertEquals(30, AIConfigHelper.boundedTimeoutSeconds(30));
    }

    @Test
    void modelResponseBodyIsBoundedEvenWithoutContentLength() throws IOException {
        byte[] body = new byte[AIConfigHelper.MAX_RESPONSE_BYTES + 1];
        var response = AIConfigHelper.limitResponseBody(new MockClientHttpResponse(body, HttpStatus.OK));

        assertThrows(IOException.class, () -> response.getBody().readAllBytes());
        response.close();
    }

    @Test
    void modelResponseAtTheLimitCanBeRead() throws IOException {
        byte[] body = new byte[AIConfigHelper.MAX_RESPONSE_BYTES];
        var response = AIConfigHelper.limitResponseBody(new MockClientHttpResponse(body, HttpStatus.OK));

        assertEquals(AIConfigHelper.MAX_RESPONSE_BYTES, response.getBody().readAllBytes().length);
        response.close();
    }
}
