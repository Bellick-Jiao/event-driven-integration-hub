package com.bellick.hub.profile.model;

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
 * Latest customer snapshot persisted by the consumer (event-carried state
 * transfer): the "core system" stores what the event already carried, with no
 * callback to the source API.
 */
@Entity
@Table(name = "profile_store")
public class ProfileStore {

    @Id
    @Column(name = "customer_id")
    private String customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode snapshot;

    @Column(name = "version", nullable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ProfileStore() {
        // for JPA
    }

    public ProfileStore(String customerId, JsonNode snapshot, long version) {
        this.customerId = customerId;
        this.snapshot = snapshot;
        this.version = version;
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

    public long getVersion() {
        return version;
    }

    public void incrementVersion() {
        this.version++;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
