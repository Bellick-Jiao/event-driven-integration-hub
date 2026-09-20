package com.bellick.hub.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * The business aggregate: a customer profile.
 *
 * <p>{@code version} is an optimistic lock (JPA {@link Version}) so concurrent
 * updates are safe — an important banking-domain concern.
 */
@Entity
@Table(name = "customer",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_customer_id", columnNames = "customer_id"))
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status")
    private KycStatus kycStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "addresses", columnDefinition = "jsonb")
    private List<Address> addresses;

    @Version
    @Column(name = "version")
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Customer() {
        // for JPA
    }

    public Customer(String customerId, String name, String email, String phone,
                    KycStatus kycStatus, List<Address> addresses) {
        this.customerId = customerId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.kycStatus = kycStatus;
        this.addresses = addresses == null ? List.of() : addresses;
    }

    /** Applies a partial (PATCH) update. Null fields are left untouched. */
    public void applyPatch(String email, String phone, KycStatus kycStatus, List<Address> addresses) {
        if (email != null) this.email = email;
        if (phone != null) this.phone = phone;
        if (kycStatus != null) this.kycStatus = kycStatus;
        if (addresses != null) this.addresses = addresses;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public KycStatus getKycStatus() {
        return kycStatus;
    }

    public List<Address> getAddresses() {
        return addresses;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
