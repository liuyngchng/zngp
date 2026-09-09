package com.rd.zngp.util;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Self-signed certificate generation utility.
 */
public class CertGen {

    private static final Logger log = LoggerFactory.getLogger(CertGen.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Ensure certificate/key pair exists at the given paths.
     * If either file is missing, generate a new self-signed certificate.
     * Existing certificates without a SAN extension are regenerated so modern
     * browsers will accept them.
     */
    public static void ensureCert(String certFile, String keyFile) throws Exception {
        File cf = new File(certFile);
        File kf = new File(keyFile);

        if (cf.exists() && cf.isFile() && kf.exists() && kf.isFile()) {
            if (hasSubjectAlternativeName(cf)) {
                log.info("tls_cert_ready: cert={}, key={}", certFile, keyFile);
                return;
            }
            log.warn("tls_cert_missing_san: regenerating cert={} (and key={})", certFile, keyFile);
            if (!cf.delete() || !kf.delete()) {
                throw new IOException("failed to delete old certificate/key");
            }
        }

        // Ensure parent directories exist
        File parent = cf.getParentFile();
        if (parent != null) parent.mkdirs();
        parent = kf.getParentFile();
        if (parent != null) parent.mkdirs();

        log.info("generating_self_signed_certificate...");
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        if (host == null || host.isEmpty()) host = "localhost";

        X500Name issuer = new X500Name("CN=" + host + ", O=ZNGP");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 3600_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 10 * 24 * 3600_000L);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic());

        // Add Subject Alternative Name extension (required by modern browsers).
        // The IP SAN is resolved from the cert host; localhost is always included.
        certBuilder.addExtension(Extension.subjectAlternativeName, false, buildSanExtension());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(holder);

        // Write certificate (PEM)
        try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(cf))) {
            pw.writeObject(cert);
        }

        // Write private key (PEM)
        try (JcaPEMWriter pw = new JcaPEMWriter(new FileWriter(kf))) {
            pw.writeObject(keyPair.getPrivate());
        }

        log.info("self_signed_cert_generated: cert={}, key={}", certFile, keyFile);
    }

    /**
     * Build a SAN extension containing DNS entries for "localhost" and the
     * machine hostname, plus IP entries for 127.0.0.1 / ::1 / 0.0.0.0.
     */
    private static GeneralNames buildSanExtension() throws Exception {
        java.util.List<GeneralName> names = new java.util.ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        names.add(new GeneralName(GeneralName.iPAddress, "::1"));
        names.add(new GeneralName(GeneralName.iPAddress, "0.0.0.0"));

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = null;
        }
        if (host != null && !host.isEmpty() && !"localhost".equalsIgnoreCase(host)) {
            names.add(new GeneralName(GeneralName.dNSName, host));
        }

        // Add the machine's non-loopback addresses as IP SANs so the cert
        // works when accessed via the LAN IP.
        try {
            for (InetAddress addr : InetAddress.getAllByName(host == null ? "localhost" : host)) {
                if (addr != null && !addr.isLoopbackAddress() && !addr.isAnyLocalAddress()) {
                    names.add(new GeneralName(GeneralName.iPAddress, addr.getHostAddress()));
                }
            }
        } catch (Exception ignored) {
            // best-effort; the localhost entries above already cover local use
        }

        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    /**
     * Return true if the given PEM certificate file contains a Subject
     * Alternative Name extension.
     */
    private static boolean hasSubjectAlternativeName(File certFile) {
        try (Reader r = new FileReader(certFile);
             PEMParser parser = new PEMParser(r)) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder) {
                X509CertificateHolder holder = (X509CertificateHolder) obj;
                return holder.getExtension(Extension.subjectAlternativeName) != null;
            }
        } catch (Exception e) {
            log.warn("tls_cert_parse_failed: {}", certFile, e);
        }
        return false;
    }
}