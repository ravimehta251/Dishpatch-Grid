package io.dispatchgrid.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    scanBasePackages = {"io.dispatchgrid.common", "io.dispatchgrid.worker"},
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
