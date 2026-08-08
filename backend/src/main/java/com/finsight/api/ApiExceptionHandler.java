package com.finsight.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.finsight.application.AuthenticationService;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AuthenticationService.AuthenticationRequiredException.class)
    public ProblemDetail handleUnauthorized(AuthenticationService.AuthenticationRequiredException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        detail.setTitle("Authentication required");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(AuthenticationService.AuthenticationFailedException.class)
    public ProblemDetail handleAuthenticationFailed(AuthenticationService.AuthenticationFailedException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        detail.setTitle("Authentication failed");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(AuthenticationService.AuthenticationNotFoundException.class)
    public ProblemDetail handleAuthenticationNotFound(AuthenticationService.AuthenticationNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Account not found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(AuthenticationService.AuthenticationConflictException.class)
    public ProblemDetail handleAuthenticationConflict(AuthenticationService.AuthenticationConflictException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Account conflict");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(AuthenticationService.VerificationCodeRateLimitException.class)
    public ProblemDetail handleVerificationRateLimit(AuthenticationService.VerificationCodeRateLimitException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Verification code rate limited");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(CsrfService.CsrfException.class)
    public ProblemDetail handleCsrf(CsrfService.CsrfException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        detail.setTitle("Request validation failed");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid request");
        detail.setDetail(ex.getBindingResult().getAllErrors().get(0).getDefaultMessage());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Bad request");
        detail.setDetail(ex.getMessage());
        return detail;
    }
}
