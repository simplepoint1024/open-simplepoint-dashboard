# Security Policy

## Reporting Security Issues

**Please do not open public issues for security vulnerabilities.**

If you discover a security vulnerability in SimplePoint, please report it responsibly by emailing:

📧 **simplepoint1024@gmail.com**

Include the following in your report:

- Description of the vulnerability
- Steps to reproduce
- Affected versions
- Potential impact

We will acknowledge receipt within **48 hours** and aim to provide an initial assessment within **5 business days**.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | ✅ Current stable release |
| < 1.0   | ❌ No longer supported |

## Security Measures

This project employs the following security practices:

- **OAuth 2.0 / OIDC** authorization server with Spring Security
- **Server-side parameter binding** for federation SQL queries (防止 SQL 注入)
- **TLS support** for the DNA JDBC socket protocol
- **Query rate limiting** to mitigate denial-of-service
- **Identifier validation** for dynamically constructed SQL in data quality checks
- **Checkstyle** enforced via pre-commit hooks

## Disclosure Policy

- We follow **coordinated disclosure** — please allow us reasonable time to address the issue before public disclosure.
- Credit will be given to reporters in the release notes (unless anonymity is requested).
