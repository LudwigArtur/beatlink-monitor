# Security and operational boundaries

This experimental, AI-generated application has no security certification
or guaranteed response/support period. See [DISCLAIMER.md](DISCLAIMER.md).

## Deployment

- Keep `allowLanAccess=false` unless remote control is deliberately required.
- The HTTP API and WebSocket do not implement login, authorization or TLS.
  LAN clients with access can operate the application and configuration.
- Do not expose port 17812 or the Vite development server to the Internet.
- Use an isolated trusted show network and host firewall rules.
- grandMA2 Telnet is unencrypted. Use a dedicated, minimally privileged account
  where supported. Passwords are stored in local `outputs.json`; restrict
  file access and exclude that file from source control and issue attachments.
- Treat imported profiles, command templates and configuration as trusted input.
- Existing installations retain saved output settings, including enabled
  outputs. New public defaults do not sanitize previously stored credentials.

## Reporting a suspected vulnerability

Use the repository's **Security > Report a vulnerability** feature if private
vulnerability reporting is enabled. Otherwise open an issue requesting a
private contact channel **without technical exploit details or sensitive data**.
No private contact address or enabled reporting feature is promised here.

Include affected versions, impact and minimal reproduction details only in
the private channel. Never send real production credentials.

This document is deployment guidance, not a claim that a security audit was
performed.
