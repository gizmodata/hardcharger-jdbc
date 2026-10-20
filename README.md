<p align="center">
  <a href="https://github.com/gizmodata/hardcharger">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hardcharger-banner-dark.png">
      <img src="docs/assets/hardcharger-banner-light.png" alt="Hardcharger™" width="400">
    </picture>
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDBC-4.2-red.svg?logo=openjdk" alt="JDBC 4.2">
  <img src="https://img.shields.io/badge/Java-17%2B-orange.svg?logo=openjdk" alt="Java 17+">
  <a href="https://github.com/gizmodata/hardcharger"><img src="https://img.shields.io/badge/server-Hardcharger-f15a24.svg" alt="Hardcharger"></a>
  <a href="https://github.com/gizmodata/warp-sql-jdbc"><img src="https://img.shields.io/badge/built%20on-Warp%20SQL%20JDBC-31a8db.svg" alt="Built on the Warp SQL JDBC driver"></a>
  <a href="https://github.com/gizmodata/hardcharger-jdbc/releases/latest"><img src="https://img.shields.io/badge/release-jar-orange.svg?logo=GitHub" alt="Release jar"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-brightgreen.svg" alt="Apache 2.0 license"></a>
  <a href="https://gizmodata.com"><img src="https://img.shields.io/badge/by-GizmoData%E2%84%A2-f15a24.svg" alt="GizmoData™"></a>
</p>

<h3 align="center">Hardcharger for Java applications, DBeaver and other JDBC tools: one jar.</h3>

---

## ☕ What is it?

The JDBC driver for [Hardcharger™](https://github.com/gizmodata/hardcharger), GizmoData™'s DuckDB 2.0
server. Hardcharger speaks [Warp SQL](https://github.com/gizmodata/warp-sql), so this driver is a
thin layer on the [Warp SQL JDBC driver](https://github.com/gizmodata/warp-sql-jdbc): it accepts
`jdbc:hardcharger://` URLs, uses Hardcharger's port (31337) when the URL names none, and names
itself "Hardcharger JDBC Driver" to the tools that show it. Everything else, from TLS to result
streaming and partitioned reads, is the Warp SQL driver's, which speaks the Warp SQL protocol
1.0.0.

**Status: 2.0.0-SNAPSHOT.** Nothing is published yet: 2.0.0 goes to Maven Central with
Hardcharger 2.0.0.

## 📦 Install

<!-- LAUNCH: these coordinates resolve once 2.0.0 is on Maven Central (Hardcharger GA). -->

Maven:

```xml
<dependency>
  <groupId>com.gizmodata</groupId>
  <artifactId>hardcharger-jdbc</artifactId>
  <version>2.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.gizmodata:hardcharger-jdbc:2.0.0'
```

It brings one dependency, `com.gizmodata:warp-sql-jdbc`, itself a single jar with everything
relocated inside. For DBeaver and other tools, download the standalone `hardcharger-jdbc.jar` from
the [latest release](https://github.com/gizmodata/hardcharger-jdbc/releases/latest).

## 🛠️ Build

The Warp SQL JDBC driver is a submodule while `pom.xml` names a `-SNAPSHOT` of it
(`warp-sql-jdbc.version`); a released version comes from Maven Central instead:

```bash
git clone --recurse-submodules https://github.com/gizmodata/hardcharger-jdbc.git
cd hardcharger-jdbc
mvn -f third_party/warp-sql-jdbc/pom.xml -DskipTests -DskipITs install   # the Warp SQL driver, into ~/.m2
mvn package            # unit tests, then the jars
mvn verify             # also the integration tests (needs a hardcharger-server; see Testing)
```

Two jars come out:

| Jar | Contents | For |
|---|---|---|
| `target/hardcharger-jdbc.jar` | this driver, the Warp SQL driver and every dependency, relocated | DBeaver and other tools, or any class path: it is the only file you need |
| `target/hardcharger-jdbc-<version>.jar` | this driver only | builds that already bring `warp-sql-jdbc` |

## 🚀 Connect

```
jdbc:hardcharger://host[:port][/catalog][?key=value&...]
```

The port defaults to 31337. TLS is on unless `useEncryption=false`, as with `jdbc:warp://`; there
is no separate scheme for plain HTTP. Options can also follow semicolons or come in the
`Properties`, and keys are case-insensitive.

```java
Properties properties = new Properties();
properties.setProperty("user", "hardcharger");
properties.setProperty("password", "change-me");
properties.setProperty("useEncryption", "false"); // plain HTTP on localhost
try (Connection connection = DriverManager.getConnection("jdbc:hardcharger://localhost", properties);
     ResultSet rows = connection.createStatement().executeQuery("SELECT 42 AS answer")) {
  rows.next();
  System.out.println(rows.getInt("answer"));
}
```

The connection properties (`tlsRootCerts`, mutual TLS, `async`, `retainResults`,
`ipcCompression`, `resultPartitions`, ...) and their defaults are the Warp SQL driver's: see its
[property table](https://github.com/gizmodata/warp-sql-jdbc#properties). This driver sets no
defaults of its own beyond the port, so it reads a large result in up to 32 partitions at once,
each over its own connection (`resultPartitions`, default 32; the server sizes the split by the
result, and `resultPartitions=1` reads one stream), in the server's row order unless
`resultOrder=any`. Results come compressed (`ipcCompression=auto`: `lz4_frame` on a near link,
`zstd` on a far one).

### 🪪 Client certificate sign-in (mutual TLS)

On a server that verifies client certificates and lets them sign in (`--mtls-ca-cert` with
`--mtls-certificate-sign-in`), the certificate is the credential: set `authType` to
`client_certificate` (or `mtls`) and give no user or password.

```java
Properties properties = new Properties();
properties.setProperty("authType", "client_certificate");
properties.setProperty("clientCertificate", "client.pem");
properties.setProperty("clientKey", "client-key.pem");    // PKCS#8, PKCS#1 RSA or SEC1 EC, unencrypted
properties.setProperty("tlsRootCerts", "server-ca.pem");  // when the server's CA is private
try (Connection connection = DriverManager.getConnection("jdbc:hardcharger://db.example.com", properties);
     ResultSet rows = connection.createStatement().executeQuery("SELECT HARDCHARGER_USER()")) {
  rows.next();
  System.out.println(rows.getString(1)); // the certificate's identity: ops@example.com
}
```

The same keys work in the URL
(`jdbc:hardcharger://db.example.com?authType=mtls&clientCertificate=client.pem&clientKey=client-key.pem`).
The identity is the certificate's e-mail SAN unless the server says otherwise
(`--mtls-identity-source`); it must be one of `--mtls-admin-identities` or, under access
control, a user of the store. The token is bound to the certificate, and every connection the
driver opens (result partitions too) presents it. Refusals have SQLState 28000, with the
server's code in `WarpProblem.codeOf(error)` (`unknown_certificate_identity`,
`certificate_mismatch`, `certificate_required` (also when the server refuses a connection
without a certificate at the TLS handshake), `certificate_revoked`,
`certificate_identity_mismatch`).

### 🌐 Browser sign-in

On a server with browser sign-in configured (`--oauth-client-id` and the identity provider's
`--token-allowed-issuer`; its discovery then lists the `oauth_browser` capability), the person
signs in with that identity provider in a web browser and the application holds no password:
set `authType` to `oauth_browser` (or `external`) and give no user or password.

```java
Properties properties = new Properties();
properties.setProperty("authType", "oauth_browser");
try (Connection connection = DriverManager.getConnection("jdbc:hardcharger://db.example.com", properties);
     ResultSet rows = connection.createStatement().executeQuery("SELECT HARDCHARGER_USER()")) {
  rows.next();
  System.out.println(rows.getString(1)); // whoever signed in to the identity provider
}
```

These are the Warp SQL driver's settings, passed through unchanged (and the same keys work in the
URL: `jdbc:hardcharger://db.example.com?authType=external&oauthTimeout=120`):

| Property | Default | Meaning |
|---|---|---|
| `authType` | `password` | `oauth_browser` (alias `external`): browser sign-in (Warp SQL spec §4.3) |
| `oauthBrowser` | the desktop's browser | The command that opens the sign-in page, given its URL as the last argument (split on spaces, no shell); `none` prints the URL to standard error for you to open |
| `oauthTimeout` | `300` | Seconds to wait for the person to sign in |

`getConnection` opens the sign-in page and waits, polling the server, until the person has signed
in or `oauthTimeout` passes (`SQLTimeoutException`). A refusal at the identity provider
(`access_denied`) or a person the server doesn't admit (`invalid_credentials`, outside
`--token-authorized-emails`) is an `SQLInvalidAuthorizationSpecException` with SQLState 28000 and
the server's code in `WarpProblem.codeOf(error)`; a server without browser sign-in is refused
before any browser opens. The access token isn't renewed on its own: when it expires, connect
again. `user`, `password`, `token` and `subjectToken` can't be given beside it. See the Warp SQL
driver's [Browser sign-in](https://github.com/gizmodata/warp-sql-jdbc#-browser-sign-in) section.

What is Hardcharger's in `DatabaseMetaData`:

| Method | Answer |
|---|---|
| `getDriverName()` | `Hardcharger JDBC Driver` |
| `getDriverVersion()`, `getDriverMajorVersion()`, `getDriverMinorVersion()` | this driver's version |
| `getURL()` | the `jdbc:hardcharger:` URL you gave, with passwords and tokens shown as `***` |
| `getDatabaseProductName()`, `getDatabaseProductVersion()` | the server's own answer: `Hardcharger`, and its version |
| `isReadOnly()` | the server's own answer (`server_read_only`): true for a server started with `--read-only` |

`connection.unwrap(com.gizmodata.warpsql.jdbc.WarpConnection.class)` reaches the Warp SQL
connection (for `getSessionId()`, or `getTransactionState()`: the server's view of the session's
transaction, which sees SQL `BEGIN` too). Errors carry the server's code in
`WarpProblem.codeOf(error)`, or a `client_` code for a failure the driver found in the server's
answer (`client_http_error`, `client_protocol_error`). `Statement.getConnection()` returns that Warp SQL
connection too, not the Hardcharger one. `jdbc:warp://` URLs keep going to the Warp SQL driver,
which the standalone jar also registers.

## 🦫 DBeaver

1. **Database > Driver Manager > New.**
2. On **Settings**: driver name `Hardcharger`; class name
   `com.gizmodata.hardcharger.jdbc.HardchargerDriver`; URL template
   `jdbc:hardcharger://{host}[:{port}][/{database}]`; default port `31337`; leave the default
   user empty or set `hardcharger`.
3. On **Libraries**: **Add File**, and pick `hardcharger-jdbc.jar` (the standalone jar from the
   [release](https://github.com/gizmodata/hardcharger-jdbc/releases/latest) or `target/`). **Find
   Class** should show `com.gizmodata.hardcharger.jdbc.HardchargerDriver`. Click **OK**.
4. **Database > New Database Connection**, choose **Hardcharger**, and enter the host, the port
   (31337 unless you changed it), and the user and password.
5. On **Driver properties**: for a server without TLS (a local one, or the Docker quick start)
   set `useEncryption` to `false`. For a server with a private CA, set `tlsRootCerts` to its PEM
   file instead.
6. **Test Connection**, then **Finish**. DBeaver shows the server as Hardcharger and the driver as
   Hardcharger JDBC Driver.

No JVM options are needed. Keep DBeaver's default of a separate connection for metadata reads.

## ✅ Testing

Unit tests (`mvn package`) need nothing else. Integration tests (`mvn verify`) start
`hardcharger-server` for themselves, on free ports, with a fresh administrator password that
contains `:` and `@`, an empty home directory, and only `PATH`, `HOME`, `TMPDIR` and
`HARDCHARGER_ADMIN_PASSWORD` in its environment:

```bash
HARDCHARGER_JDBC_TEST_SERVER_BINARY=/path/to/hardcharger-server mvn verify
```

Without the variable they fail; they never skip. One test starts a server on 31337 to prove a URL
without a port reaches it, and fails if anything else holds that port. They run against
`target/hardcharger-jdbc.jar` with no other dependency on the class path, so they also prove the
standalone jar is self-contained. Negative tests assert the SQLState and message, each beside a
positive twin. The TLS certificates in `src/test/resources/certs` are warp-sql-jdbc's throwaway
test certificates (keys public, trusted by nothing but these tests).

CI runs both on JDK 17 and 21 against `hardcharger-server` from the image named in
`.github/workflows/ci.yml` (`ghcr.io/gizmodata/hardcharger:main`, Hardcharger's main branch;
pin a `:sha-<short>` tag to reproduce a run; pulled with the workflow's
`GITHUB_TOKEN`; the package grants this repository read access). While the repositories are
internal, CI fetches the submodule (for a `-SNAPSHOT` `warp-sql-jdbc.version`, which must match
the submodule's version) with `WARP_SQL_JDBC_DEPLOY_KEY`, a read-only deploy key of
`gizmodata/warp-sql-jdbc`. For a released version it checks Maven Central has it and uses that. No
personal token is involved.

Formatting is google-java-format, checked by Spotless: `mvn spotless:apply` (on JDK 17).

## 📦 Releases

The version in `pom.xml` is the next release with `-SNAPSHOT`. A release depends on a released
Warp SQL JDBC driver, so release warp-sql-jdbc first. To release X.Y.Z: set `<version>X.Y.Z</version>`
and `warp-sql-jdbc.version` to the released driver (and repin the submodule to its tag), move
`[Unreleased]` in the [CHANGELOG](CHANGELOG.md) to `## [X.Y.Z] - <date>`, commit, and push the commit
and the tag `vX.Y.Z` together.

`.github/workflows/release.yml` then waits (up to an hour) until Maven Central serves that Warp SQL
driver, runs the full test suite (unit and integration) on the tagged commit against it, builds the
jar, sources and Javadoc jars, signs each file with GizmoData's Maven Central key
(`BAAC1F9A2083FE825C9834D39CB426669B8EEEAA`), checks the bundle (`dev/check-central-bundle.sh`),
uploads it to Maven Central and waits until it is published, and makes a GitHub Release with both
jars, their signatures and the CHANGELOG section. Pushing the tag is the decision to publish; Maven
Central never takes a version twice.

A dry run, from a `release/*` branch: **Actions > release > Run workflow**. It runs the tests and
builds, signs and checks the bundle (a workflow artifact) without uploading; a Warp SQL driver
version Central doesn't have yet is built from the submodule for the run. With `central_upload` it
also uploads the bundle as USER_MANAGED: Central validates it and holds it under **Deployments** on
[central.sonatype.com](https://central.sonatype.com/publishing/deployments) for Publish or Drop.
Drop it there to test without publishing.

By hand: `mvn -P release deploy` with the Central Portal token as server `central` in
`settings.xml`, the key in gpg's keyring and its passphrase in `MAVEN_GPG_PASSPHRASE`. It uploads
USER_MANAGED (`-Dcentral.autoPublish=true` publishes as soon as it validates), and
`-Dcentral.skipUpload=true` only builds `target/central-publishing/central-bundle.zip`.

## 📄 License

[Apache License 2.0](LICENSE).

---

<p align="center">
  <a href="https://gizmodata.com"><img src="docs/assets/gizmodata-logo.png" alt="GizmoData™" width="56"></a><br>
  <sub>Built by <a href="https://gizmodata.com">GizmoData™</a></sub>
</p>

<p align="center"><sub>Hardcharger™ and GizmoData™ are trademarks of GizmoData LLC.</sub></p>
