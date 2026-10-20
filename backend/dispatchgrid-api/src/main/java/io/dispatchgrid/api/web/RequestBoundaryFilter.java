package io.dispatchgrid.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Read at most one MiB plus one byte, including chunked requests, before JSON parsing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBoundaryFilter extends OncePerRequestFilter {
  private static final int MAX_BYTES = 1_048_576;
  private final ObjectMapper mapper;

  public RequestBoundaryFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlation = request.getHeader("X-Correlation-Id");
    if (correlation == null || !correlation.matches("[a-zA-Z0-9._-]{1,100}"))
      correlation = UUID.randomUUID().toString();
    request.setAttribute("correlationId", correlation);
    response.setHeader("X-Correlation-Id", correlation);
    response.setHeader("Cache-Control", "no-store");
    MDC.put("correlationId", correlation);
    try {
      if (request.getContentLengthLong() > MAX_BYTES) {
        Problems.write(mapper, request, response, 413, "Request exceeds one MiB");
        return;
      }
      if (java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
          Problems.write(mapper, request, response, 413, "Request exceeds one MiB");
          return;
        }
        chain.doFilter(new CachedRequest(request, body), response);
      } else chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
      MDC.remove("tenantId");
    }
  }

  private static final class CachedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var stream = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return stream.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
          return stream.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
          return stream.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }
}
