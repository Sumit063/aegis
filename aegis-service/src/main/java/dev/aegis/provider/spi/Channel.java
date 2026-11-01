package dev.aegis.provider.spi;

/** How a provider message reached Aegis. */
public enum Channel {
    /** Server to server: webhooks and asynchronous responses ({@code POST /in/{connection}}). */
    SERVER,
    /** Carried by the customer's browser: Checkout callbacks ({@code POST /in/{connection}/callback}). */
    BROWSER
}
