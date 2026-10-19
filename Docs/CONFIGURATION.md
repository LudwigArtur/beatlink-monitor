# Configuration and networking

## Storage

| System | Directory |
| --- | --- |
| macOS | `~/Library/Application Support/BeatLinkMonitor/` |
| Windows | `%APPDATA%\BeatLinkMonitor\` |
| Linux | `$XDG_CONFIG_HOME/beatlink-monitor/`, otherwise `~/.config/beatlink-monitor/` |

Files: `application.json`, `outputs.json`, `profiles/*.json`.
The current `configVersion` is 1. Prefer editing settings through the UI;
stop the application and create a backup before manual edits.
Files in [config](../config/) are examples, not automatically loaded production
configuration.

## General settings

| Key | Meaning / default |
| --- | --- |
| `networkInterfaceMode` | `AUTOMATIC` or `MANUAL` |
| `manualInterfaceName` | Local adapter name; not a portable default |
| `preferredDeviceNumber` | 0 for automatic assignment; otherwise deliberately choose a free number |
| `waveformStylePreference` | `RGB` or `THREE_BAND`; hardware-dependent |
| `webUiPort` | 17812 |
| `allowLanAccess` | false: loopback only; true: all IPv4 interfaces |
| `developmentMode` | false |
| `simulatorAutoStart` | false |
| `activeProfile` | `default` |

Network/port changes may require a restart; follow UI notices.
The API uses `/api`; live state is pushed over `/ws`.

## Outputs

See [outputs.example.json](../config/outputs.example.json).
Fresh configuration has grandMA and Ableton Link disabled.
The grandMA target is loopback and username/password are empty.
Set the target host, port, templates and network adapter before enabling it.
Carabiner defaults to `127.0.0.1:17000`, quantum 4 and position source `AUTO`
(`PRECISE` / `SMOOTHED` are alternatives).

Output adapters are configured independently of the Pro DJ Link adapter.
Automatic adapter selection is a default, not a guarantee of correct routing
with VLANs or multiple network cards.

**Existing installations:** New defaults do not overwrite saved output
settings. Existing installations may still contain automatic connections and
previously saved credentials.

## Data protection

- `outputs.json` contains a saved password in plain text.
  Restrict user permissions and protect backups accordingly.
- LAN access does not provide user authentication, access control or TLS.
  Enable it only on a trusted, isolated network.
- Do not forward router ports. Do not use Telnet over unprotected networks.
- Do not upload configuration, profiles or logs to GitHub issues without review.

See [SECURITY.md](../SECURITY.md) and [warnings](../DISCLAIMER.md).
