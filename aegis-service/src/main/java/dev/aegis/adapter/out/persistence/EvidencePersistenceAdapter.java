package dev.aegis.adapter.out.persistence;

import dev.aegis.application.port.out.EvidenceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Sealed values are strings anywhere in the payments' messages and the validations' data. */
@Component
class EvidencePersistenceAdapter implements EvidenceRepository {

    private static final String SEALED = "'strict $.** ? (@.type() == \"string\" && @ starts with \"enc:v1:\")'";
    private static final String ALL = """
            SELECT v #>> '{}' AS sealed, p.updated_at FROM payments p, jsonb_path_query(p.messages, %1$s) v
            UNION ALL
            SELECT v #>> '{}', w.updated_at FROM validations w, jsonb_path_query(w.data, %1$s) v""".formatted(SEALED);

    private final JdbcTemplate jdbc;

    EvidencePersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Long> countByKey() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT split_part(sealed, ':', 3), count(*) FROM (" + ALL + ") e GROUP BY 1 ORDER BY 1", row -> {
            counts.put(row.getString(1), row.getLong(2));
        });
        return counts;
    }

    @Override
    public List<String> recent(int limit) {
        return jdbc.queryForList(
                "SELECT sealed FROM (" + ALL + ") e ORDER BY updated_at DESC LIMIT ?", String.class, limit);
    }
}
