package com.example.myagent.incident.adapter.out.http;

import io.vavr.control.Try;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class HttpClientFactory {
    private HttpClientFactory() {
    }

    static HttpClient create(boolean tlsVerify) {
        var builder = HttpClient.newBuilder();
        if (!tlsVerify) {
            builder.sslContext(insecureSslContext());
            var sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(sslParameters);
        }
        return builder.build();
    }

    private static SSLContext insecureSslContext() {
        return Try.of(() -> {
            var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new InsecureTrustManager()}, new SecureRandom());
            return context;
        }).getOrElseThrow(exception -> new IllegalStateException(
            "Unable to configure demo TLS client",
            exception
        ));
    }

    private static final class InsecureTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authenticationType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authenticationType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
