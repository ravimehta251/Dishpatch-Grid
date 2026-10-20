package io.dispatchgrid.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class Problems {
  private Problems() {}

  public static ProblemDetail detail(int status, String message, HttpServletRequest request) {
    var result = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message);
    result.setInstance(URI.create(request.getRequestURI()));
    result.setProperty("correlationId", request.getAttribute("correlationId"));
    return result;
  }

  public static void write(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    mapper.writeValue(response.getOutputStream(), detail(status, message, request));
  }
}
