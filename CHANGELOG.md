# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Release

- Maven Central publishing, for 2.0.0 with Hardcharger 2.0.0: `com.gizmodata:hardcharger-jdbc` is
  the driver jar (its version and module name now in its manifest) depending on
  `com.gizmodata:warp-sql-jdbc`, with sources and Javadoc jars, each file GPG-signed (`-P release`,
  through `central-publishing-maven-plugin` 0.12.0; uploads are USER_MANAGED unless
  `-Dcentral.autoPublish=true`; a release can't depend on a SNAPSHOT). The POM carries the
  developer, SCM and issue metadata Central requires. The version line moves to `2.0.0-SNAPSHOT`.
- `release.yml`: on a `v*` tag, waits until Maven Central serves the Warp SQL driver
  `warp-sql-jdbc.version` names, runs the full test suite on the tagged commit against it, then
  checks the signed bundle (`dev/check-central-bundle.sh`) and publishes it to Maven Central, and
  attaches both jars and their signatures to the GitHub Release. By hand from a `release/*` branch
  it is a dry run, or with `central_upload` a USER_MANAGED upload to validate and drop.
- CI takes a `-SNAPSHOT` Warp SQL driver from the submodule (and checks its version matches) and a
  released one from Maven Central. Builds skip documentation-only changes.

### Changed

- Built on warp-sql-jdbc c472b00 (1.0.0-SNAPSHOT, was 00ffbb2): its Maven artifact is now its
  standalone jar, with no dependencies of its own, so this driver brings two jars and nothing else.

### Documentation

- Trademark notices: Hardcharger™ and GizmoData™ on the README titles and first mentions, package
  metadata and the footer, which says both are trademarks of GizmoData LLC.

### Changed

- Built on warp-sql-jdbc 00ffbb2 (was e1e0866): client certificate sign-in. With
  `authType=client_certificate` (alias `mtls`), `clientCertificate` and `clientKey`, a
  `jdbc:hardcharger://` connection signs in with the mutual-TLS certificate alone, on a server
  with `--mtls-ca-cert` and `--mtls-certificate-sign-in`. `ClientCertificateIT` covers sign-in
  (an e-mail identity matching `--mtls-admin-identities` ignoring case), the options in the URL,
  a split result read with the bound token (every row, in order), an unknown identity
  (`unknown_certificate_identity`), a password beside the certificate (28000), a bound token over
  another certificate (`certificate_mismatch`) and its twin, and a server with certificate
  sign-in off (`mtls_identity`) and its password twin; six of its seven tests fail on e1e0866.
  The test certificates are warp-sql-jdbc's regenerated set (client certificates with e-mail SANs).

- Built on warp-sql-jdbc e1e0866 (was 7bb1d90): a large result is read in up to 8 partitions at
  once by default (`resultPartitions` 8, was 1). PartitionsIT checks the default, its twin
  (`resultPartitions=1`), and that a 2-million-row split result keeps every row in order.
- CI reads the warp-sql-jdbc submodule with a read-only deploy key (`WARP_SQL_JDBC_DEPLOY_KEY`)
  and pulls `hardcharger-server` from the private image with `GITHUB_TOKEN`: no personal token.

### Added

- `HardchargerDriver`: `jdbc:hardcharger://host[:port][/catalog][?key=value&...]` URLs, handed to
  the Warp SQL JDBC driver as `jdbc:warp://` URLs. The port defaults to 31337; TLS, the connection
  properties and their defaults are the Warp SQL driver's (`useEncryption=false` for plain HTTP).
- `DatabaseMetaData` names the driver "Hardcharger JDBC Driver" with this driver's version, and
  reports the `jdbc:hardcharger:` URL with passwords and tokens redacted.
- Malformed URLs are refused with SQLState 08001 and an "Invalid Hardcharger URL" message naming
  the URL as given.
- Two jars: the driver alone, and `hardcharger-jdbc.jar` with the Warp SQL driver and every
  dependency inside, for DBeaver and other tools.
- Integration tests against a real `hardcharger-server` (`HARDCHARGER_JDBC_TEST_SERVER_BINARY`),
  failing without one; CI on JDK 17 and 21, and a tag-triggered release workflow gated on them.
- DBeaver setup instructions.
