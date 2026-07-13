package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.domain.Order;
import com.pulse.repo.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_kpi_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=1000",
        "pulse.ratelimit.login.refill-per-minute=1000"
})
class SellerKpiTests {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper om;
    @Autowired private OrderRepository orderRepository;
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

    private long createProduct(String token, String name, long price, int stock) throws Exception {
        MvcResult res = mvc.perform(post("/products").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"priceCents\":" + price + ",\"stock\":" + stock + "}"))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long placeOrder(String buyerToken, long pid, int qty) throws Exception {
        MvcResult r = mvc.perform(post("/orders").header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + pid + ",\"quantity\":" + qty + "}"))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void kpi_requires_seller_role() throws Exception {
        String buyer = signupAndLogin("kpibuyer@x.com", "BUYER");
        mvc.perform(get("/seller/kpi").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());
    }

    @Test
    void kpi_aggregates_gmv_orders_and_cancel_rate_for_own_products_only() throws Exception {
        String s1 = signupAndLogin("s1kpi@x.com", "SELLER");
        String s2 = signupAndLogin("s2kpi@x.com", "SELLER");
        String buyer = signupAndLogin("bkpi@x.com", "BUYER");

        long p1 = createProduct(s1, "P1", 1000, 10);
        long p2 = createProduct(s1, "P2", 2000, 10);
        long pOther = createProduct(s2, "Pother", 9999, 10);

        // 4 orders on s1: 2 active + 2 will be cancelled
        long o1 = placeOrder(buyer, p1, 2);   // 2000
        long o2 = placeOrder(buyer, p2, 1);   // 2000
        long o3 = placeOrder(buyer, p1, 3);   // 3000 -> cancel
        long o4 = placeOrder(buyer, p2, 2);   // 4000 -> cancel
        // 1 order on s2 (must be ignored for s1 kpi)
        placeOrder(buyer, pOther, 1);

        mvc.perform(post("/orders/" + o3 + "/cancel").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());
        mvc.perform(post("/orders/" + o4 + "/cancel").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(get("/seller/kpi").header("Authorization", "Bearer " + s1))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals(4L, body.get("orderCount").asLong());
        assertEquals(2L, body.get("cancelledCount").asLong());
        // gmv excludes cancelled: 2000 + 2000 = 4000
        assertEquals(4000L, body.get("gmvCents").asLong());
        // cancel rate 2/4 = 0.5
        assertEquals(0.5, body.get("cancelRate").asDouble(), 0.0001);

        // s2 should see only its own metrics
        MvcResult r2 = mvc.perform(get("/seller/kpi").header("Authorization", "Bearer " + s2))
                .andExpect(status().isOk()).andReturn();
        JsonNode body2 = om.readTree(r2.getResponse().getContentAsString());
        assertEquals(1L, body2.get("orderCount").asLong());
        assertEquals(0L, body2.get("cancelledCount").asLong());
        assertEquals(9999L, body2.get("gmvCents").asLong());
    }

    @Test
    void kpi_returns_zeros_when_no_products() throws Exception {
        String s = signupAndLogin("sempty@x.com", "SELLER");
        MvcResult r = mvc.perform(get("/seller/kpi").header("Authorization", "Bearer " + s))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals(0L, body.get("orderCount").asLong());
        assertEquals(0L, body.get("gmvCents").asLong());
        assertEquals(0.0, body.get("cancelRate").asDouble());
    }

    @Test
    void kpi_window_filter_excludes_orders_outside_range() throws Exception {
        String seller = signupAndLogin("swin@x.com", "SELLER");
        String buyer = signupAndLogin("bwin@x.com", "BUYER");
        long pid = createProduct(seller, "PW", 1000, 10);
        placeOrder(buyer, pid, 1);
        placeOrder(buyer, pid, 2);

        // Future window: no orders should match
        String farFuture = "2099-01-01T00:00:00Z";
        String farFutureEnd = "2099-12-31T00:00:00Z";
        MvcResult r = mvc.perform(get("/seller/kpi")
                .param("from", farFuture).param("to", farFutureEnd)
                .header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals(0L, body.get("orderCount").asLong());
        assertEquals(0L, body.get("gmvCents").asLong());
        assertEquals(farFuture, body.get("from").asText());
        assertEquals(farFutureEnd, body.get("to").asText());

        // Default window (last 30d) — both orders in
        MvcResult r2 = mvc.perform(get("/seller/kpi").header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk()).andReturn();
        JsonNode b2 = om.readTree(r2.getResponse().getContentAsString());
        assertEquals(2L, b2.get("orderCount").asLong());
    }

    @Test
    void kpi_window_filter_includes_only_orders_inside_range_when_mixed() throws Exception {
        String seller = signupAndLogin("smix@x.com", "SELLER");
        String buyer = signupAndLogin("bmix@x.com", "BUYER");
        long pid = createProduct(seller, "PM", 1000, 10);

        long insideOrderId = placeOrder(buyer, pid, 1);   // 1000, will land inside the window
        long outsideOrderId = placeOrder(buyer, pid, 5);  // 5000, will be backdated outside the window

        Instant now = Instant.now();
        Instant from = now.minus(1, ChronoUnit.DAYS);
        Instant to = now.plus(1, ChronoUnit.DAYS);

        // Backdate one order to just before "from" so it must be excluded.
        Order outside = orderRepository.findById(outsideOrderId).orElseThrow();
        ReflectionTestUtils.setField(outside, "createdAt", from.minus(1, ChronoUnit.SECONDS));
        orderRepository.save(outside);

        // Keep the other order inside [from, to).
        Order inside = orderRepository.findById(insideOrderId).orElseThrow();
        ReflectionTestUtils.setField(inside, "createdAt", now);
        orderRepository.save(inside);

        MvcResult r = mvc.perform(get("/seller/kpi")
                .param("from", from.toString()).param("to", to.toString())
                .header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        assertEquals(1L, body.get("orderCount").asLong());
        assertEquals(1000L, body.get("gmvCents").asLong());
    }

    @Test
    void kpi_invalid_date_format_returns_400() throws Exception {
        String seller = signupAndLogin("sbad@x.com", "SELLER");
        mvc.perform(get("/seller/kpi").param("from", "not-a-date")
                .header("Authorization", "Bearer " + seller))
                .andExpect(status().isBadRequest());
    }
}
