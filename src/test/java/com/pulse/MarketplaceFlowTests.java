package com.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
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
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MarketplaceFlowTests {

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private ObjectMapper om;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private String signupAndLogin(String email, String password, String role) throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private long loginAdminAndGetId() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@pulse.local\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("userId").asLong();
    }

    private String adminToken() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@pulse.local\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private long createProduct(String sellerToken, String name, long price, int stock) throws Exception {
        MvcResult res = mvc.perform(post("/products")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"priceCents\":" + price + ",\"stock\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void signup_login_buy_full_flow() throws Exception {
        String seller = signupAndLogin("seller1@x.com", "passpass1", "SELLER");
        String buyer = signupAndLogin("buyer1@x.com", "passpass1", "BUYER");
        long pid = createProduct(seller, "Apple", 1000, 5);

        MvcResult res = mvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode order = om.readTree(res.getResponse().getContentAsString());
        assertEquals(2000, order.get("totalCents").asLong());
        assertEquals("PLACED", order.get("status").asText());

        mvc.perform(get("/products/" + pid))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode p = om.readTree(result.getResponse().getContentAsString());
                    assertEquals(3, p.get("stock").asInt());
                });
    }

    @Test
    void buyer_cannot_create_product() throws Exception {
        String buyer = signupAndLogin("buyer2@x.com", "passpass1", "BUYER");
        mvc.perform(post("/products")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"priceCents\":100,\"stock\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void seller_cannot_order() throws Exception {
        String seller = signupAndLogin("seller3@x.com", "passpass1", "SELLER");
        long pid = createProduct(seller, "X", 100, 1);
        mvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_cannot_be_signed_up() throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@x.com\",\"password\":\"passpass1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_can_suspend_seller_and_seller_cannot_login() throws Exception {
        String sellerToken = signupAndLogin("seller4@x.com", "passpass1", "SELLER");
        // find seller id via creating a product (token sub)
        long pid = createProduct(sellerToken, "X", 100, 1);
        // We don't directly know sellerId, but we can query products to get sellerId.
        MvcResult pres = mvc.perform(get("/products/" + pid)).andExpect(status().isOk()).andReturn();
        long sellerId = om.readTree(pres.getResponse().getContentAsString()).get("sellerId").asLong();

        String admin = adminToken();
        mvc.perform(post("/admin/sellers/" + sellerId + "/suspend")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // suspended seller can no longer login
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"seller4@x.com\",\"password\":\"passpass1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void non_admin_cannot_suspend() throws Exception {
        String sellerToken = signupAndLogin("seller5@x.com", "passpass1", "SELLER");
        long pid = createProduct(sellerToken, "X", 100, 1);
        long sellerId = om.readTree(mvc.perform(get("/products/" + pid)).andReturn().getResponse().getContentAsString())
                .get("sellerId").asLong();
        mvc.perform(post("/admin/sellers/" + sellerId + "/suspend")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancel_order_restocks() throws Exception {
        String seller = signupAndLogin("seller6@x.com", "passpass1", "SELLER");
        String buyer = signupAndLogin("buyer6@x.com", "passpass1", "BUYER");
        long pid = createProduct(seller, "Item", 500, 3);

        MvcResult ord = mvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andReturn();
        long oid = om.readTree(ord.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/orders/" + oid + "/cancel")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk());

        long stockAfter = om.readTree(mvc.perform(get("/products/" + pid)).andReturn()
                        .getResponse().getContentAsString())
                .get("stock").asLong();
        assertEquals(3, stockAfter);
    }

    @Test
    void other_buyer_cannot_cancel() throws Exception {
        String seller = signupAndLogin("seller7@x.com", "passpass1", "SELLER");
        String buyer1 = signupAndLogin("buyer7a@x.com", "passpass1", "BUYER");
        String buyer2 = signupAndLogin("buyer7b@x.com", "passpass1", "BUYER");
        long pid = createProduct(seller, "X", 100, 5);
        long oid = om.readTree(mvc.perform(post("/orders")
                                .header("Authorization", "Bearer " + buyer1)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":" + pid + ",\"quantity\":1}"))
                        .andReturn().getResponse().getContentAsString())
                .get("id").asLong();
        mvc.perform(post("/orders/" + oid + "/cancel")
                        .header("Authorization", "Bearer " + buyer2))
                .andExpect(status().isForbidden());
    }
}
