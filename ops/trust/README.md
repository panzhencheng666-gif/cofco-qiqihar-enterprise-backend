# Application trust roots

`cfca-ev-root.pem` is the self-signed CFCA EV ROOT certificate distributed in
the macOS System Root Certificates keychain. It is included so Linux and macOS
runtime installations use the same reviewed trust anchor without changing the
JDK-wide `cacerts` file.

- Subject and issuer: `C=CN, O=China Financial Certification Authority, CN=CFCA EV ROOT`
- Serial: `184ACCD6`
- Validity: 2012-08-08 03:07:01 UTC through 2029-12-31 03:07:01 UTC
- SHA-256: `5C:C3:D7:8E:4E:1D:5E:45:54:7A:04:E6:87:3E:64:F9:0C:F9:53:6D:1C:CC:2E:F8:00:F3:55:C4:C5:FD:70:FD`

`scripts/prepare-runtime-truststore.sh` verifies this identity and validity,
copies the selected JDK's default truststore, then imports this root into the
application copy. The application startup uses that copy only; it never edits
the JDK or operating-system trust stores.
