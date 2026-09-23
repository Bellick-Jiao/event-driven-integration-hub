package com.bellick.hub.loader.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Wide warehouse row: the "data platform" stores the event-carried snapshot
 * verbatim (event-carried state transfer), one row per customer.
 */
@Entity
@Table(name = "data_warehouse")
public class DataWarehouse {

    @Id
    @Column(name = "customer_id")
    private String customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode snapshot;

    @UpdateTimestamp
    @Column(name = "loaded_at")
    private Instant loadedAt;

    protected DataWarehouse() {
        // for JPA
    }

    public DataWarehouse(String customerId, JsonNode snapshot) {
        this.customerId = customerId;
        this.snapshot = snapshot;
    }

    public String getCustomerId() {
        return customerId;
    }

    public JsonNode getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(JsonNode snapshot) {
        this.snapshot = snapshot;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }
}
