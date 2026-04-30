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
        "spring.datasource.url=jdbc:h2:mem:pulse_dsp_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=1000",
        "pulse.ratelimit.login.refill-per-minute=1000"
})
class DisputeThreadTests {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper om;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private String signupAndLogin(String email, String role) throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
        MvcResult r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"passpass1\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String adminToken() throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@pulse.local\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createProduct(String token, String name, long price, int stock) throws Exception {
        MvcResult res = mvc.perform(post("/products").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"priceCents\":" + price + ",\"stock\":" + stock + "}"))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long placeOrder(String buyer, long pid, int qty) throws Exception {
        MvcResult r = mvc.perform(post("/orders").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + pid + ",\"quantity\":" + qty + "}"))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void buyer_and_seller_can_post_and_read_thread_admin_can_view() throws Exception {
        String seller = signupAndLogin("dspS1@x.com", "SELLER");
        String buyer = signupAndLogin("dspB1@x.com", "BUYER");
        long pid = createProduct(seller, "X", 500, 5);
        long oid = placeOrder(buyer, pid, 1);

        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"item arrived broken\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + seller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"please send photos\"}"))
                .andExpect(status().isCreated());

        // Buyer reads
        MvcResult r = mvc.perform(get("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        JsonNode arr = om.readTree(r.getResponse().getContentAsString());
        assertEquals(2, arr.size());
        assertEquals("BUYER", arr.get(0).get("senderRole").asText());
        assertEquals("SELLER", arr.get(1).get("senderRole").asText());
        assertEquals("item arrived broken", arr.get(0).get("body").asText());

        // Admin can view too
        String admin = adminToken();
        MvcResult r2 = mvc.perform(get("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, om.readTree(r2.getResponse().getContentAsString()).size());
    }

    @Test
    void other_buyer_cannot_read_or_post() throws Exception {
        String seller = signupAndLogin("dspS2@x.com", "SELLER");
        String buyer = signupAndLogin("dspB2@x.com", "BUYER");
        String stranger = signupAndLogin("dspB2x@x.com", "BUYER");
        long pid = createProduct(seller, "Y", 500, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hello\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden());
        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + stranger)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"i'm sneaking in\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void other_seller_cannot_post() throws Exception {
        String s1 = signupAndLogin("dspS3@x.com", "SELLER");
        String s2 = signupAndLogin("dspS3b@x.com", "SELLER");
        String buyer = signupAndLogin("dspB3@x.com", "BUYER");
        long pid = createProduct(s1, "Z", 500, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + s2)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"not my order\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_cannot_post_only_read() throws Exception {
        String seller = signupAndLogin("dspS4@x.com", "SELLER");
        String buyer = signupAndLogin("dspB4@x.com", "BUYER");
        long pid = createProduct(seller, "W", 500, 5);
        long oid = placeOrder(buyer, pid, 1);
        String admin = adminToken();
        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"administrative comment\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void empty_body_returns_400() throws Exception {
        String seller = signupAndLogin("dspS5@x.com", "SELLER");
        String buyer = signupAndLogin("dspB5@x.com", "BUYER");
        long pid = createProduct(seller, "Q", 500, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/dispute/messages")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
