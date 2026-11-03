# Security policy

## Reporting a vulnerability

Please **don't** open a public issue. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/lecrane54/AppInspectorPlus/security/advisories/new)
(the **Security** tab → **Report a vulnerability**).

## Verifying a release

Each release includes `SHA256SUMS.txt` and a signed build attestation. Before installing a zip:

```bash
shasum -a 256 -c SHA256SUMS.txt
gh attestation verify network-rules-sync-<ver>.zip -R lecrane54/AppInspectorPlus
```

The attestation proves the zip was built by this repo's release workflow from this repo's code.
