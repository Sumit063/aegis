package dev.aegis.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code config}: one runtime setting, versioned, with who changed it last. */
@Entity
@Table(name = "config")
public class ConfigEntity {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String value;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConfigEntity() {}

    public ConfigEntity(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public int getVersion() {
        return version;
    }

    public void change(String value, String updatedBy, Instant now) {
        this.value = value;
        this.version++;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }
}
