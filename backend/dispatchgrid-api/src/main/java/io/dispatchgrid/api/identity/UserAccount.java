package io.dispatchgrid.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected UserAccount() {}

  public UserAccount(UUID id, String email, String passwordHash) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }
}
