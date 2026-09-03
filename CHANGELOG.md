# Changelog

## [Unreleased]

### Security

- RabbitMQ Java client is updated to 5.34.0, closing the protocol, TLS, parsing, and allocation vulnerabilities reported against 5.25.0.

### Fixed

- Mobile collection acknowledgments no longer fail while loading their authoritative study, and upgraded installations restore the intended runtime database privilege boundary. [#56](https://github.com/uzaira0/chronicle-server/pull/56)
