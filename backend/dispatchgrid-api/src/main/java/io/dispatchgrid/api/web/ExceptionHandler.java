package io.dispatchgrid.api.web;

import io.dispatchgrid.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ExceptionHandler {
  @org.springframework.web.bind.annotation.ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> domain(ApiException e, HttpServletRequest r) {
    var b = ResponseEntity.status(e.status());
    if (e instanceof RateLimitException rate)
      b.header("Retry-After", Long.toString(rate.retryAfterSeconds()));
    return b.body(Problems.detail(e.status(), e.getMessage(), r));
  }

  @org.springframework.web.bind.annotation.ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest()
        .body(Problems.detail(400, "Invalid request parameters or body", r));
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(409)
        .body(Problems.detail(409, "Resource conflicts with existing data", r));
  }
}
