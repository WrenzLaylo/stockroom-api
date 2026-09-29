package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.common.ValidationException;

/** The demo accounts' domain can only be created by DemoDataSeeder, never through the API. */
public final class ReservedEmails {

    public static final String DEMO_DOMAIN = "@demo.stockroom.dev";

    private ReservedEmails() {}

    /** email is already trimmed and lowercased by the request record. */
    public static void requireNotReserved(String email) {
        if (email.endsWith(DEMO_DOMAIN)) {
            throw new ValidationException("email", "This email domain is reserved");
        }
    }
}
