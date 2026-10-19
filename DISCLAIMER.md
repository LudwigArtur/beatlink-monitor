# Use at your own risk

BeatLink Monitor is experimental, AI-generated show-control software.
**Installation and use are at your own risk.** No error-free operation,
availability, timing accuracy, hardware compatibility or fitness for a
particular purpose is guaranteed. The warranty and liability provisions of
[EPL-2.0](LICENSE), particularly sections 4 and 5, govern. Mandatory statutory
rights and liability that cannot legally be excluded remain unaffected.
This notice is not individual legal advice and does not replace a legal
review for commercial distribution.

## Before every live deployment

1. Start with the simulator and disabled outputs.
2. Check network adapters, virtual player number, target console and command
   templates.
3. Control only authorized, approved targets. grandMA Telnet can send commands
   directly to a lighting console; incorrect templates or target addresses
   can change the running show.
4. Test master changes, connection loss, restart and manual output shutdown
   in the actual setup.
5. Keep an independent fallback available. Do not use this application as the
   sole source for timing-critical or safety-related control.

## Not a safety function

Do not use for emergency stops, personal protection or safety-related laser,
pyrotechnics, rigging or machinery control. This software replaces neither
approved safety controllers nor qualified operators.

## Network and data

The web API has no built-in user authentication and no TLS. Enable LAN access
only on a trusted, isolated network; do not forward ports to the Internet.
Telnet transmits credentials without encryption. Configuration and logs may
contain sensitive information.

See also [SECURITY.md](SECURITY.md) and
[configuration](Docs/CONFIGURATION.md).
