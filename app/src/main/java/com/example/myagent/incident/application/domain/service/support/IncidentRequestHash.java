package com.example.myagent.incident.application.domain.service.support;

import io.vavr.control.Try;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class IncidentRequestHash {
    private IncidentRequestHash() {
    }

    public static String calculate(Object request) {
        return Try.of(() -> {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }).getOrElseThrow(exception -> new IllegalStateException(
            "SHA-256 is not available",
            exception
        ));
    }
}
