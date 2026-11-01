package dev.aegis.lab.shop;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The flaws switched on right now. Starts from {@code shop.flaws}; the lab switches them per attack
 * ({@code PUT /flaws}) so one running shop can show every flaw alone.
 */
@Component
public class Flaws {

    private final AtomicReference<Set<Flaw>> current;

    @Autowired
    public Flaws(ShopProperties properties) {
        this(properties.flaws());
    }

    Flaws(Set<Flaw> initial) {
        current = new AtomicReference<>(copy(initial));
    }

    public boolean has(Flaw flaw) {
        return current.get().contains(flaw);
    }

    public Set<Flaw> current() {
        return current.get();
    }

    public void set(Set<Flaw> flaws) {
        current.set(copy(flaws));
    }

    private static Set<Flaw> copy(Set<Flaw> flaws) {
        return flaws == null || flaws.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flaws));
    }
}
