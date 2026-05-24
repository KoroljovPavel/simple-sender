package com.botfunnel;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Servlet-flip note: previously mocked MongoClient + RedisConnectionFactory at the slice level
// and exercised via WebTestClient. After the flip uses MockMvc + Testcontainers (inherited from
// AbstractIntegrationTest).
class HealthEndpointTest extends AbstractIntegrationTest {

    @Test
    void healthEndpointReturns200WithOkBody() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
