package com.example.myagent.command.application.domain.service.support;

import io.vavr.control.Try;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class TextDigest {
    private TextDigest() {
    }

    public static String sha256(String value) {
        return Try.of(() -> {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }).getOrElseThrow(exception -> new IllegalStateException(
            "SHA-256 is not available",
            exception
        ));
    }
}
