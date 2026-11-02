package io.github.p3chys.cooperativearchi.github;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.util.HttpSupport;

/**
 * HTTPS trust for our own connections: the Java runtime's certificates plus, on Windows, the Windows certificate
 * store. Archi runs on its own bundled Java, which does not see the root certificate that a company proxy or
 * antivirus installs into Windows to inspect HTTPS traffic, so every call failed with "PKIX path building failed".
 * Only cooperativeArchi's connections use this; Archi's (and other plugins') JGit and Java defaults are untouched.
 */
public final class SystemTrust {

    private static final X509ExtendedTrustManager TRUST = load();
    private static final SSLContext CONTEXT = context(TRUST);

    private SystemTrust() {
    }

    /** For java.net.http clients. */
    public static SSLContext sslContext() {
        return CONTEXT;
    }

    /** For JGit commands ({@code setTransportConfigCallback}): HTTPS transports verify with this trust. */
    public static TransportConfigCallback transport() {
        return SystemTrust::configure;
    }

    static X509ExtendedTrustManager trustManager() {
        return TRUST;
    }

    private static void configure(Transport t) {
        if (t instanceof TransportHttp http) {
            HttpConnectionFactory f = http.getHttpConnectionFactory();
            http.setHttpConnectionFactory(new Factory(f == null ? new JDKHttpConnectionFactory() : f));
        }
    }

    private static X509ExtendedTrustManager load() {
        List<X509ExtendedTrustManager> all = new ArrayList<>();
        add(all, null); // the Java runtime's cacerts (or javax.net.ssl.trustStore)
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                KeyStore windows = KeyStore.getInstance("Windows-ROOT");
                windows.load(null, null);
                add(all, windows);
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                // no readable Windows store (locked-down or virtual desktops): Java's certificates alone, as before
            }
        }
        return all.size() == 1 ? all.get(0) : new AnyOf(all);
    }

    private static void add(List<X509ExtendedTrustManager> to, KeyStore ks) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) {
                    to.add(x);
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            if (ks == null) {
                throw new IllegalStateException("No default HTTPS trust", e);
            }
        }
    }

    private static SSLContext context(X509ExtendedTrustManager tm) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { tm }, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot set up HTTPS", e);
        }
    }

    /** Trusts a server if any of the stores does; the error of the first store otherwise. */
    static final class AnyOf extends X509ExtendedTrustManager {

        private interface Check {
            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private final List<X509ExtendedTrustManager> stores;

        AnyOf(List<X509ExtendedTrustManager> stores) {
            this.stores = List.copyOf(stores);
        }

        private void any(Check c) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager tm : stores) {
                try {
                    c.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket s) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, s));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, e));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket s) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, s));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, e));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return stores.stream().flatMap(tm -> Arrays.stream(tm.getAcceptedIssuers())).toArray(X509Certificate[]::new);
        }
    }

    /** JGit connections that verify HTTPS with our trust; everything else as the wrapped factory does it. */
    private record Factory(HttpConnectionFactory delegate) implements HttpConnectionFactory2 {

        @Override
        public HttpConnection create(URL url) throws IOException {
            return trusting(url, delegate.create(url));
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            return trusting(url, delegate.create(url, proxy));
        }

        private static HttpConnection trusting(URL url, HttpConnection c) throws IOException {
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                try {
                    c.configure(null, new TrustManager[] { TRUST }, null);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Cannot set up HTTPS", e);
                }
            }
            return c;
        }

        @Override
        public GitSession newSession() {
            if (delegate instanceof HttpConnectionFactory2 f) {
                return f.newSession();
            }
            // what JGit does for factories without sessions (http.sslVerify=false)
            return new GitSession() {
                @Override
                public HttpConnection configure(HttpConnection c, boolean sslVerify) throws IOException {
                    if (!sslVerify && "https".equalsIgnoreCase(c.getURL().getProtocol())) {
                        HttpSupport.disableSslVerify(c);
                    }
                    return c;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
