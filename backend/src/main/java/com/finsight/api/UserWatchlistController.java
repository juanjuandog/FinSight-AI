package com.finsight.api;

import com.finsight.application.UserWatchlistService;
import com.finsight.application.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import com.finsight.domain.model.UserWatchlistItem;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/watchlist")
public class UserWatchlistController {
    private final UserWatchlistService userWatchlistService;
    private final AuthenticationService authenticationService;
    private final AuthCookieSupport cookies;
    private final CsrfService csrfService;

    public UserWatchlistController(
            UserWatchlistService userWatchlistService,
            AuthenticationService authenticationService,
            AuthCookieSupport cookies,
            CsrfService csrfService
    ) {
        this.userWatchlistService = userWatchlistService;
        this.authenticationService = authenticationService;
        this.cookies = cookies;
        this.csrfService = csrfService;
    }

    @GetMapping
    public List<UserWatchlistItem> list(HttpServletRequest request) {
        return userWatchlistService.list(currentUserId(request));
    }

    @PostMapping("/{symbol}")
    public List<UserWatchlistItem> add(
            HttpServletRequest request,
            @PathVariable String symbol
    ) {
        csrfService.require(request);
        return userWatchlistService.add(currentUserId(request), symbol);
    }

    @DeleteMapping("/{symbol}")
    public List<UserWatchlistItem> remove(
            HttpServletRequest request,
            @PathVariable String symbol
    ) {
        csrfService.require(request);
        return userWatchlistService.remove(currentUserId(request), symbol);
    }

    private String currentUserId(HttpServletRequest request) {
        return authenticationService.requireUser(cookies.readSessionToken(request)).id();
    }
}
