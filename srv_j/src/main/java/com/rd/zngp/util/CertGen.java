package com.rd.zngp.util;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
     * Existing certificates are left untouched.
     */
    public static void ensureCert(String certFile, String keyFile) throws Exception {
        File cf = new File(certFile);
        File kf = new File(keyFile);

        if (cf.exists() && cf.isFile() && kf.exists() && kf.isFile()) {
            log.info("tls_cert_ready: cert={}, key={}", certFile, keyFile);
            return;
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
}