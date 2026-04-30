package com.pulse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_rl_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=3",
        "pulse.ratelimit.login.refill-per-minute=1"
})
class RateLimitTests {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void login_rate_limited_after_capacity_exceeded() throws Exception {
        String body = "{\"email\":\"none@x.com\",\"password\":\"passpass1\"}";
        // first 3 attempts may return 401 (invalid creds) but pass the limiter
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
        }
        // 4th request should be rate limited (429)
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(r -> {
                    int s = r.getResponse().getStatus();
                    if (s != 429) {
                        throw new AssertionError("expected 429, got " + s);
                    }
                });
    }
}
