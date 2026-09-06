package com.thinhbui303.observability.core.service;

import java.util.Locale;

public final class SlugBuilder {

    private SlugBuilder() {
    }

    public static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isEmpty() ? "service" : slug;
    }
}