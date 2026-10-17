# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
