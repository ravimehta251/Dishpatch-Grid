package io.dispatchgrid.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dispatchgrid.api.web.Problems;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  FilterRegistrationBean<TenantAuthenticationFilter> preventDoubleFilter(
      TenantAuthenticationFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, TenantAuthenticationFilter filter, ObjectMapper mapper) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasRole("MONITORING")
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, error) ->
                            Problems.write(
                                mapper, request, response, 401, "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            Problems.write(mapper, request, response, 403, "Access denied")))
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
