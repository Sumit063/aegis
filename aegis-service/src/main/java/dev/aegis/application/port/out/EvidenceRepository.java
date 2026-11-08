package dev.aegis.application.port.out;

import java.util.List;
import java.util.Map;

/** Finds sealed evidence wherever it is stored, so operators can prove it is still readable. */
public interface EvidenceRepository {

    /** Number of sealed values per key ID, across all payments and validations. */
    Map<String, Long> countByKey();

    /** Up to {@code limit} sealed values from the most recently active records. */
    List<String> recent(int limit);
}
