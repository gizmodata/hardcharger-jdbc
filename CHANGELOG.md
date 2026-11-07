# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.4.2] - 2026-11-06

On the Chargewire JDBC driver 2.2.4.

### Fixed

- **Closing a connection could start a stopped GizmoData Cloud cluster** (Chargewire JDBC driver
  2.2.4). The driver renews its access token with the saved credentials shortly before it
  expires, on whatever request comes next, and that included closes and cancels. A sign-in is
  indistinguishable from a new connection, so an application that closed a connection in the
  last minutes of its token's life started a cluster stopped with auto-resume, for nobody. Closes
  and cancels now go with the token the driver holds and never sign in, and a close refused with
  `invalid_token` is done (the session went with its token). Statements and metadata renew as
  before.

## [2.4.1] - 2026-11-05

On the Chargewire JDBC driver 2.2.3.

### Fixed

- **A host name with a final dot failed every TLS connection** (Chargewire JDBC driver 2.2.3).
  2.4.0 sends a GizmoData Cloud name written fully qualified
  (`jdbc:hardcharger://mycluster.us-east-1.aws.gizmodata.com.`) to port 443, but Java's TLS stack
  refuses an SNI name with a trailing dot, so the connection then failed with a raw
  `IllegalArgumentException`. The driver now connects to the name without one final dot, and SNI
  and the certificate check use that name. `TlsIT` connects to `localhost.` over TLS.

## [2.4.0] - 2026-11-05

On the Chargewire JDBC driver 2.2.2.

### Added

- **A GizmoData Cloud host without a port goes to port 443.** A `jdbc:hardcharger://` URL whose
  host is `gizmodata.com` or ends in `.gizmodata.com` (any case, one trailing dot allowed) and
  names no port now connects to 443, where GizmoData Cloud clusters answer, instead of 31337. The
  match is on the domain boundary: `evilgizmodata.com` and `gizmodata.com.example.org` keep
  31337, as does every other host, and a port in the URL always wins. The Hardcharger CLI and web
  UI follow the same rule from Hardcharger 2.8.0.

### Fixed

- **A lost session's statement ran again on the new session after the old one had run `USE`,
  `SET`, `RESET` or `PRAGMA`** (Chargewire JDBC driver 2.2.2), so it could run in another catalog
  or schema, or under other settings. It now fails with SQLState `08007` without running, and the
  message says the old session's settings or `USE` are gone: after `SET`, `RESET` or `PRAGMA`
  always (the driver does not restore settings), and after `USE` until the driver has read the
  session after a later statement (then the new session opens in the catalog and schema it read,
  and the statement runs again). Only a statement's first word counts (`UPDATE … SET` does not).

## [2.3.1] - 2026-11-05

On the Chargewire JDBC driver 2.2.1.

### Fixed

- **A statement could still run again on its own in autocommit after a SQL `BEGIN`** (Chargewire
  JDBC driver 2.2.1). Hardcharger through 2.7.0 (Chargewire 2.2.0 and earlier) answers a statement
  without rows before it copies the session's transaction state into the session document, so a
  session read right after `BEGIN` (`isValid()`, `getCatalog()`, `getSchema()`,
  `getTransactionState()`) could still say `none`; a statement that then found the session lost
  (idle eviction, a restart) ran again in autocommit on the new session and committed on its own,
  while the transaction's earlier statements had been rolled back. Now, after a statement whose text
  may open a transaction or change how one opens (`BEGIN`, `START`, `EXECUTE` of a prepared
  statement, anything naming autocommit), a session read saying `none` doesn't count
  (`getTransactionState()` reports `UNKNOWN`) until a statement whose text may end one (`COMMIT`,
  `ROLLBACK`, `END`, `ABORT`), a new session or a change of autocommit, and such a statement fails
  with SQLState `08007` without running. Hardcharger releases on Chargewire 2.2.1 fix the server
  side; the driver holds against the servers already deployed.

## [2.3.0] - 2026-11-05

For GizmoData Cloud's auto-resume (Chargewire 2.2.0, spec §11.2), on the Chargewire JDBC driver 2.2.0.

### Added

- **A stopped GizmoData Cloud cluster that resumes on sign-in** (Chargewire JDBC driver 2.2.0):
  while the cluster starts, its address answers `server_resuming` (HTTP 503 with `Retry-After`),
  and the driver waits the `Retry-After` (3 s without one, at least 1 s, at most 60 s) and sends the
  same request again, the sign-in included, until the new `resumeTimeout` property (seconds,
  default `600`; `0` turns the retries off) runs out; then the error has SQLState `08001` and code
  `server_resuming`. Every request but discovery waits at least 30 s for its answer, because a
  resuming server may hold one for up to 25 s. `isValid(timeout)` and a statement's query timeout
  stop the retries at their own limit, and `Statement.cancel()` ends them. The README's "A stopped
  cluster that resumes" documents it; `ServerResumingIT` runs it through a stand-in in front of a
  real server, with `resumeTimeout` in the URL and in the properties.

## [2.2.0] - 2026-11-04

For Hardcharger 2.7.0 (Chargewire 2.1.0).

### Changed

- Built on the Chargewire JDBC driver 2.1.0. From it: a statement that finds its session lost runs
  again in a new session only when the driver knows no transaction was open in the old one (with
  autocommit on, the session's state read after the last statement; a SQL `BEGIN` shows only
  there), and otherwise fails with SQLState `08007` without running, so it can't commit on its
  own while the transaction's earlier statements were rolled back. A session Hardcharger ended for a
  transaction left idle (`--idle-in-transaction-session-timeout`; `session_ended`, reason
  `idle_in_transaction`) is replaced, and the statement fails with SQLState `40000`
  (`SQLTransactionRollbackException`) without running; before, the connection was closed.

### Fixed

- `getIndexInfo()` names a nested schema (DuckDB 2.0's `CREATE SCHEMA a.b`) by its path, `a.b`, as
  Hardcharger 2.7.0's other metadata calls do, and takes the path as its schema argument. Before, it
  named the schema by its leaf name, and a top-level schema whose name contains a dot (`"a.b"`) by
  its bare name, so `getIndexInfo(null, "a.b", ...)` returned the dotted schema's indexes instead of
  the nested schema's.

## [2.1.0] - 2026-10-29

### Changed

- The protocol Hardcharger speaks is now called Chargewire, and this driver is built on the
  Chargewire JDBC driver 2.0.0 (`com.gizmodata:chargewire-jdbc`, package `com.gizmodata.chargewire.jdbc`).
  `jdbc:hardcharger://` URLs, their options and `HardchargerDriver` are unchanged, and connection
  URLs written for earlier versions keep working. Code that reaches the underlying driver uses its
  new class names: `unwrap(ChargewireConnection.class)`, `ChargewireProblem.codeOf(error)`, and
  `com.gizmodata.chargewire.jdbc.ChargewireDriver`, which the standalone jar registers for
  `jdbc:chargewire://` URLs. The standalone jar's relocated dependencies moved to
  `com.gizmodata.chargewire.jdbc.shaded`.
- The driver still works with Hardcharger servers released before the rename (2.2.0 and earlier):
  at the default `basePath` it falls back to such a server's spelling of the protocol.
- CI fetches the submodule anonymously (the driver's repository is public) and needs no deploy key.

### Added

- `LegacyCompatibilityIT`: connections to a server from before the rename
  (`HARDCHARGER_JDBC_TEST_LEGACY_SERVER`, `HARDCHARGER_JDBC_TEST_LEGACY_SERVER_PASSWORD`; CI runs
  `ghcr.io/gizmodata/hardcharger:2.2.0` for it), queries, prepared statements, a batch insert and
  index metadata on it, and the earlier URL forms against a current server. It fails, never skips,
  without that server, and fails when the configured server is a current one.

## [2.0.1] - 2026-10-21

### Fixed

- `DatabaseMetaData.getIndexInfo()` now reads Hardcharger's explicitly created indexes from
  `duckdb_indexes()`, so DBeaver and other JDBC tools keep indexes visible after refresh. Reports
  uniqueness and ordered columns, including names containing commas or escaped quotes, with
  exact catalog/schema/table filtering and JDBC ordering. A null table allows schema-wide reads.
  Closing the metadata result also closes its backing statement.

### Added

- Regression coverage for the GizmoSQL DBeaver QA cases: missing/incorrect credentials fail at
  connect, the configured username is honored, plaintext and TLS metadata reads, primary keys
  and editing, DML update counts, high-precision decimals, time/boolean/binary edits, and index
  creation, refresh, filtering and deletion.

## [2.0.0] - 2026-10-20

The first release, with Hardcharger 2.0.0, on chargewire-jdbc 1.0.0 (Chargewire protocol 1.0.0).
Earlier builds (0.1.0-SNAPSHOT) were never published; the Changed entries below describe what
moved since them.

### Release

- Maven Central publishing, for 2.0.0 with Hardcharger 2.0.0: `com.gizmodata:hardcharger-jdbc` is
  the driver jar (its version and module name now in its manifest) depending on
  `com.gizmodata:chargewire-jdbc`, with sources and Javadoc jars, each file GPG-signed
  (`-P release`, through `central-publishing-maven-plugin` 0.12.0; uploads are USER_MANAGED unless
  `-Dcentral.autoPublish=true`; a release can't depend on a SNAPSHOT). The POM carries the
  developer, SCM and issue metadata Central requires. The release version is `2.0.0`.
- `release.yml`: on a `v*` tag, waits until Maven Central serves the Chargewire driver
  `chargewire-jdbc.version` names, runs the full test suite on the tagged commit against it, then
  checks the signed bundle (`dev/check-central-bundle.sh`) and publishes it to Maven Central, and
  attaches both jars and their signatures to the GitHub Release. By hand from a `release/*` branch
  it is a dry run, or with `central_upload` a USER_MANAGED upload to validate and drop.
- CI takes a `-SNAPSHOT` Chargewire driver from the submodule (and checks its version matches) and a
  released one from Maven Central. Builds skip documentation-only changes.

### Changed

- Built on released chargewire-jdbc 1.0.0, including cancellation of partition streams that finish
  opening during result close. Its standalone jar has no dependencies of its own, so this driver
  brings two jars and nothing else.

### Documentation

- The README banner is the new Hardcharger™ logo, the bull mascot lockup in a light and a dark
  version that follows the reader's color scheme, in place of the GizmoData banner card.
- Trademark notices: Hardcharger™ and GizmoData™ on the README titles and first mentions, package
  metadata and the footer, which says both are trademarks of GizmoData LLC.

### Added

- Browser sign-in through `jdbc:hardcharger://` (Chargewire spec §4.3): `authType=oauth_browser`
  (alias `external`) with `oauthBrowser` and `oauthTimeout`, the Chargewire driver's settings passed
  through unchanged, on a server started with `--oauth-client-id` and `--oauth-base-url`. The
  README has a section; `BrowserSignInIT` (6) signs in under either name and from the URL with a
  mock OpenID Connect provider (PKCE, single-use codes, nonce), and checks a refusal at the
  provider (`access_denied`), a person the server doesn't admit (`invalid_credentials`), the
  timeout (`SQLTimeoutException`) and a server without the capability.

### Changed

- Built on chargewire-jdbc 02ce45f (was 6c714ba), its Chargewire 1.0 main: `ipcCompression=auto`
  (`lz4_frame` on a near link, `zstd` on a far one; results arrive compressed) and
  `resultPartitions` 32 (was 8; the server sizes the split by the result), browser sign-in, and
  DuckDB's `HUGEINT`/`UHUGEINT` read exactly as `BigDecimal` under
  `SET arrow_lossless_conversion = true`. `PartitionsIT` checks the new default. Tested against
  `hardcharger-server` 2.0.0-rc.1.

- Built on chargewire-jdbc 6c714ba (was 00ffbb2): **Chargewire protocol 1.0.0**, so it needs a
  Hardcharger server that speaks 1.0.0 (its `spec/v1.0` branch); there are no fallbacks for the
  draft's names. What changes through `jdbc:hardcharger://`: a server that requires client
  certificates refuses a connection without one as `certificate_required` (SQLState 28000,
  `SQLInvalidAuthorizationSpecException`; it was `client_certificate_required`, and a handshake
  refusal was an unreachable server, 08xxx); `DatabaseMetaData.isReadOnly` reads Hardcharger's
  `server_read_only`; codes the driver makes up begin with `client_` (`client_http_error`,
  `client_protocol_error`); `ChargewireProblem.cancelReason()` says why a statement was cancelled;
  `ChargewireConnection.getTransactionState()` (through `unwrap`) is the server's view of the
  session's transaction; after `invalid_token` with `token_revoked`, `credentials_changed` or
  `principal_dropped` the driver no longer signs in again on its own. New live tests:
  `ProtocolV1IT` (the session's transaction across SQL `BEGIN`/`ROLLBACK`; `isReadOnly` on a
  `--read-only` server and its read-write twin) and `ClientCertificateIT`'s certificate-less
  connection with its twin; the read-only and certificate tests fail on 00ffbb2 (the transaction
  one doesn't compile there).

- Built on chargewire-jdbc 00ffbb2 (was e1e0866): client certificate sign-in. With
  `authType=client_certificate` (alias `mtls`), `clientCertificate` and `clientKey`, a
  `jdbc:hardcharger://` connection signs in with the mutual-TLS certificate alone, on a server
  with `--mtls-ca-cert` and `--mtls-certificate-sign-in`. `ClientCertificateIT` covers sign-in
  (an e-mail identity matching `--mtls-admin-identities` ignoring case), the options in the URL,
  a split result read with the bound token (every row, in order), an unknown identity
  (`unknown_certificate_identity`), a password beside the certificate (28000), a bound token over
  another certificate (`certificate_mismatch`) and its twin, and a server with certificate
  sign-in off (`mtls_identity`) and its password twin; six of its seven tests fail on e1e0866.
  The test certificates are chargewire-jdbc's regenerated set (client certificates with e-mail
  SANs).

- Built on chargewire-jdbc e1e0866 (was 7bb1d90): a large result is read in up to 8 partitions at
  once by default (`resultPartitions` 8, was 1). PartitionsIT checks the default, its twin
  (`resultPartitions=1`), and that a 2-million-row split result keeps every row in order.
- CI reads the chargewire-jdbc submodule with a read-only deploy key
  (`CHARGEWIRE_JDBC_DEPLOY_KEY`) and pulls `hardcharger-server` from the private image with
  `GITHUB_TOKEN`: no personal token.

### Added

- `HardchargerDriver`: `jdbc:hardcharger://host[:port][/catalog][?key=value&...]` URLs, handed to
  the Chargewire JDBC driver as `jdbc:chargewire://` URLs. The port defaults to 31337; TLS, the
  connection properties and their defaults are the Chargewire driver's (`useEncryption=false` for
  plain HTTP).
- `DatabaseMetaData` names the driver "Hardcharger JDBC Driver" with this driver's version, and
  reports the `jdbc:hardcharger:` URL with passwords and tokens redacted.
- Malformed URLs are refused with SQLState 08001 and an "Invalid Hardcharger URL" message naming
  the URL as given.
- Two jars: the driver alone, and `hardcharger-jdbc.jar` with the Chargewire driver and every
  dependency inside, for DBeaver and other tools.
- Integration tests against a real `hardcharger-server` (`HARDCHARGER_JDBC_TEST_SERVER_BINARY`),
  failing without one; CI on JDK 17 and 21, and a tag-triggered release workflow gated on them.
- DBeaver setup instructions.
