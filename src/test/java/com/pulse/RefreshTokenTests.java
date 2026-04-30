package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_rt_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.jwt.ttl-ms=900000",
        "pulse.refresh.ttl-ms=86400000",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=1000",
        "pulse.ratelimit.login.refill-per-minute=1000"
})
class RefreshTokenTests {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper om;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private JsonNode signupAndLogin(String email) throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\",\"role\":\"BUYER\"}"))
                .andExpect(status().isCreated());
        MvcResult r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void login_returns_access_and_refresh_tokens() throws Exception {
        JsonNode body = signupAndLogin("rt1@x.com");
        assertTrue(body.has("accessToken"));
        assertTrue(body.has("refreshToken"));
        assertTrue(body.has("token"), "back-compat alias 'token' present");
        assertEquals(body.get("token").asText(), body.get("accessToken").asText());
        assertFalse(body.get("refreshToken").asText().isBlank());
    }

    @Test
    void refresh_rotates_token_and_invalidates_old() throws Exception {
        JsonNode body = signupAndLogin("rt2@x.com");
        String r1 = body.get("refreshToken").asText();

        MvcResult res = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rotated = om.readTree(res.getResponse().getContentAsString());
        String r2 = rotated.get("refreshToken").asText();
        assertNotEquals(r1, r2);

        // r2 (the freshly issued token) is usable
        MvcResult res2 = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r2 + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String r3 = om.readTree(res2.getResponse().getContentAsString()).get("refreshToken").asText();
        assertNotEquals(r2, r3);

        // r2 now revoked: replaying it must fail (and triggers theft handling)
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r2 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_with_invalid_token_returns_401() throws Exception {
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokes_all_active_refresh_tokens() throws Exception {
        JsonNode body = signupAndLogin("rt3@x.com");
        String access = body.get("accessToken").asText();
        String refresh = body.get("refreshToken").asText();

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_without_auth_returns_401() throws Exception {
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reuse_of_old_refresh_token_revokes_entire_chain() throws Exception {
        JsonNode body = signupAndLogin("rt_theft@x.com");
        String r1 = body.get("refreshToken").asText();

        // Rotate once: r1 -> r2 (r1 now revoked but not expired)
        MvcResult res = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String r2 = om.readTree(res.getResponse().getContentAsString()).get("refreshToken").asText();

        // Attacker (or stale client) replays r1 — theft detection triggers
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isUnauthorized());

        // r2 (the legitimate current token) must now ALSO be invalidated
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r2 + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
