package dev.aegis.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Test
    void storesAmountInMinorUnits() {
        Money price = Money.of(49_900, "INR");

        assertThat(price.minorUnits()).isEqualTo(49_900);
        assertThat(price.currency()).isEqualTo(INR);
        assertThat(price).hasToString("49900 INR");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of(-1, "INR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void rejectsMissingCurrency() {
        assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money captured = Money.of(49_900, "INR");
        Money refunded = Money.of(9_900, "INR");

        assertThat(captured.minus(refunded)).isEqualTo(Money.of(40_000, "INR"));
        assertThat(refunded.plus(refunded)).isEqualTo(Money.of(19_800, "INR"));
    }

    @Test
    void subtractingMoreThanAvailableIsRejected() {
        assertThatThrownBy(() -> Money.of(100, "INR").minus(Money.of(101, "INR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixingCurrenciesIsRejected() {
        assertThatThrownBy(() -> Money.of(100, "INR").plus(Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.of(200, "INR").isGreaterThan(Money.of(100, "INR"))).isTrue();
        assertThat(Money.of(100, "INR").isGreaterThan(Money.of(100, "INR"))).isFalse();
        assertThat(Money.zero(INR).isZero()).isTrue();
    }
}
