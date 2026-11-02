package io.github.p3chys.cooperativearchi.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class SystemTrustTest {

    /** Trusts everything, or nothing with the given error. */
    private static X509ExtendedTrustManager store(String error) {
        return new X509ExtendedTrustManager() {
            private void check() throws CertificateException {
                if (error != null) {
                    throw new CertificateException(error);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
                check();
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
                check();
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
                check();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
                check();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
                check();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
                check();
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Test
    void trustsWhatAnyStoreTrusts() {
        var trust = new SystemTrust.AnyOf(List.of(store("PKIX path building failed"), store(null)));
        assertThatCode(() -> trust.checkServerTrusted(new X509Certificate[0], "RSA", (SSLEngine) null))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsTheFirstStoresError() {
        var trust = new SystemTrust.AnyOf(List.of(store("from Java"), store("from Windows")));
        assertThatThrownBy(() -> trust.checkServerTrusted(new X509Certificate[0], "RSA", (Socket) null))
                .isInstanceOf(CertificateException.class).hasMessage("from Java");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void includesTheWindowsCertificateStore() throws Exception {
        KeyStore windows = KeyStore.getInstance("Windows-ROOT");
        windows.load(null, null);
        List<X509Certificate> roots = Collections.list(windows.aliases()).stream()
                .map(a -> {
                    try {
                        return (X509Certificate) windows.getCertificate(a);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        assertThat(roots).isNotEmpty();
        assertThat(SystemTrust.trustManager().getAcceptedIssuers()).contains(roots.toArray(X509Certificate[]::new));
    }
}
