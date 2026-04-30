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
        "spring.datasource.url=jdbc:h2:mem:pulse_clm_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=1000",
        "pulse.ratelimit.login.refill-per-minute=1000"
})
class ClaimTests {

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
    void cancel_with_reason_records_reason_no_refund_on_unpaid() throws Exception {
        String seller = signupAndLogin("clmS1@x.com", "SELLER");
        String buyer = signupAndLogin("clmB1@x.com", "BUYER");
        long pid = createProduct(seller, "X", 500, 5);
        long oid = placeOrder(buyer, pid, 1);

        MvcResult r = mvc.perform(post("/orders/" + oid + "/cancel")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"BUYER_CHANGED_MIND\",\"note\":\"oops\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals("CANCELLED", body.get("status").asText());
        assertEquals("BUYER_CHANGED_MIND", body.get("cancelReason").asText());
        assertEquals("oops", body.get("cancelNote").asText());
        // unpaid order: no refund flow
        assertEquals("NONE", body.get("refundStatus").asText());
    }

    @Test
    void cancel_paid_order_creates_refund_request() throws Exception {
        String seller = signupAndLogin("clmS2@x.com", "SELLER");
        String buyer = signupAndLogin("clmB2@x.com", "BUYER");
        long pid = createProduct(seller, "Y", 700, 5);
        long oid = placeOrder(buyer, pid, 2);
        mvc.perform(post("/orders/" + oid + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        MvcResult r = mvc.perform(post("/orders/" + oid + "/cancel")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"DELIVERY_DELAYED\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals("CANCELLED", body.get("status").asText());
        assertEquals("REQUESTED", body.get("refundStatus").asText());
        assertEquals("DELIVERY_DELAYED", body.get("cancelReason").asText());
    }

    @Test
    void seller_can_approve_then_complete_refund() throws Exception {
        String seller = signupAndLogin("clmS3@x.com", "SELLER");
        String buyer = signupAndLogin("clmB3@x.com", "BUYER");
        long pid = createProduct(seller, "Z", 800, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"QUALITY_ISSUE\"}"))
                .andExpect(status().isOk());

        MvcResult r1 = mvc.perform(post("/orders/" + oid + "/refund")
                .header("Authorization", "Bearer " + seller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED",
                om.readTree(r1.getResponse().getContentAsString()).get("refundStatus").asText());

        MvcResult r2 = mvc.perform(post("/orders/" + oid + "/refund")
                .header("Authorization", "Bearer " + seller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"REFUND\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("REFUNDED",
                om.readTree(r2.getResponse().getContentAsString()).get("refundStatus").asText());
    }

    @Test
    void seller_can_reject_refund() throws Exception {
        String seller = signupAndLogin("clmS4@x.com", "SELLER");
        String buyer = signupAndLogin("clmB4@x.com", "BUYER");
        long pid = createProduct(seller, "W", 100, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isOk());
        MvcResult r = mvc.perform(post("/orders/" + oid + "/refund")
                .header("Authorization", "Bearer " + seller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("REJECTED",
                om.readTree(r.getResponse().getContentAsString()).get("refundStatus").asText());
    }

    @Test
    void admin_can_force_refund_flow() throws Exception {
        String seller = signupAndLogin("clmS5@x.com", "SELLER");
        String buyer = signupAndLogin("clmB5@x.com", "BUYER");
        long pid = createProduct(seller, "A", 100, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isOk());
        String admin = adminToken();
        mvc.perform(post("/orders/" + oid + "/refund").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/refund").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REFUND\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void other_seller_cannot_refund() throws Exception {
        String s1 = signupAndLogin("clmS6@x.com", "SELLER");
        String s2 = signupAndLogin("clmS6b@x.com", "SELLER");
        String buyer = signupAndLogin("clmB6@x.com", "BUYER");
        long pid = createProduct(s1, "B", 100, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + oid + "/refund").header("Authorization", "Bearer " + s2)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancel_with_invalid_reason_400() throws Exception {
        String seller = signupAndLogin("clmS7@x.com", "SELLER");
        String buyer = signupAndLogin("clmB7@x.com", "BUYER");
        long pid = createProduct(seller, "C", 100, 5);
        long oid = placeOrder(buyer, pid, 1);
        mvc.perform(post("/orders/" + oid + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOT_A_REASON\"}"))
                .andExpect(status().isBadRequest());
    }
}
