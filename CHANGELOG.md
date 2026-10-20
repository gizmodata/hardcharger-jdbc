# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation

- The README banner is the new Hardcharger™ logo, the bull mascot lockup in a light and a dark
  version that follows the reader's color scheme, in place of the GizmoData banner card.
- Trademark notices: Hardcharger™ and GizmoData™ on the README titles and first mentions, package
  metadata and the footer, which says both are trademarks of GizmoData LLC.

### Changed

- Built on warp-sql-jdbc 6c714ba (was 00ffbb2): **Warp SQL protocol 1.0.0**, so it needs a
  Hardcharger server that speaks 1.0.0 (its `spec/v1.0` branch); there are no fallbacks for the
  draft's names. What changes through `jdbc:hardcharger://`: a server that requires client
  certificates refuses a connection without one as `certificate_required` (SQLState 28000,
  `SQLInvalidAuthorizationSpecException`; it was `client_certificate_required`, and a handshake
  refusal was an unreachable server, 08xxx); `DatabaseMetaData.isReadOnly` reads Hardcharger's
  `server_read_only`; codes the driver makes up begin with `client_` (`client_http_error`,
  `client_protocol_error`); `WarpProblem.cancelReason()` says why a statement was cancelled;
  `WarpConnection.getTransactionState()` (through `unwrap`) is the server's view of the session's
  transaction; after `invalid_token` with `token_revoked`, `credentials_changed` or
  `principal_dropped` the driver no longer signs in again on its own. New live tests:
  `ProtocolV1IT` (the session's transaction across SQL `BEGIN`/`ROLLBACK`; `isReadOnly` on a
  `--read-only` server and its read-write twin) and `ClientCertificateIT`'s certificate-less
  connection with its twin; the read-only and certificate tests fail on 00ffbb2 (the transaction
  one doesn't compile there).

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
