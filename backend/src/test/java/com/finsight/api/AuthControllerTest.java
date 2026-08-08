package com.finsight.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest(properties = "finsight.stock-universe.free-provider-enabled=false")
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void registrationCreatesHttpOnlySessionUsedByWatchlist() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(bootstrap.getResponse().getContentAsString());
        String csrf = body.get("csrfToken").asText();

        MvcResult verification = mockMvc.perform(post("/api/auth/verification-code")
                        .cookie(new Cookie(CsrfService.COOKIE, csrf))
                        .header(CsrfService.HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String code = verificationCode(verification.getResponse().getContentAsString());

        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .cookie(new Cookie(CsrfService.COOKIE, csrf))
                        .header(CsrfService.HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"research2026\",\"verificationCode\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andReturn();

        String setCookie = registration.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(AuthCookieSupport.SESSION_COOKIE + "=").contains("HttpOnly").contains("SameSite=Lax");
        String session = cookieValue(setCookie, AuthCookieSupport.SESSION_COOKIE);

        mockMvc.perform(get("/api/watchlist").cookie(new Cookie(AuthCookieSupport.SESSION_COOKIE, session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void watchlistRejectsAnonymousAndCsrfLessWrites() throws Exception {
        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"research2026\"}"))
                .andExpect(status().isForbidden());
    }

    private String cookieValue(String setCookie, String name) {
        String prefix = name + "=";
        for (String part : setCookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        throw new AssertionError("Cookie not found: " + name);
    }

    private String verificationCode(String response) throws Exception {
        String message = objectMapper.readTree(response).get("message").asText();
        Matcher matcher = Pattern.compile("(\\d{6})").matcher(message);
        if (!matcher.find()) {
            throw new AssertionError("Verification code was not exposed in development mode");
        }
        return matcher.group(1);
    }
}
