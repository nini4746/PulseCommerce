package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.event.OrderCancelledEvent;
import com.pulse.event.OrderPlacedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_evt_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345"
})
class OrderEventTests {

    @TestConfiguration
    static class CaptureConfig {
        AtomicInteger placed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();

        @Bean
        Object placedListener() {
            return new Object() {
                @EventListener
                public void on(OrderPlacedEvent e) { placed.incrementAndGet(); }
            };
        }

        @Bean
        Object cancelledListener() {
            return new Object() {
                @EventListener
                public void on(OrderCancelledEvent e) { cancelled.incrementAndGet(); }
            };
        }
    }

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper om;
    @Autowired private CaptureConfig capture;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        capture.placed.set(0);
        capture.cancelled.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private String signupAndLogin(String email, String role) throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\",\"role\":\"" + role + "\"}")).andExpect(status().isCreated());
        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\"}")).andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void place_and_cancel_publish_domain_events() throws Exception {
        String seller = signupAndLogin("se@x.com", "SELLER");
        String buyer = signupAndLogin("by@x.com", "BUYER");
        MvcResult prodRes = mvc.perform(post("/products")
                        .header("Authorization", "Bearer " + seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"priceCents\":100,\"stock\":5}"))
                .andExpect(status().isCreated()).andReturn();
        long pid = om.readTree(prodRes.getResponse().getContentAsString()).get("id").asLong();

        MvcResult orderRes = mvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isCreated()).andReturn();
        long oid = om.readTree(orderRes.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/orders/" + oid + "/cancel")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());

        assertEquals(1, capture.placed.get());
        assertEquals(1, capture.cancelled.get());
    }
}
