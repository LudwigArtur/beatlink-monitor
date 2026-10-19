# Contributing

Read the [architecture](ARCHITECTURE.md), [development guide](Docs/DEVELOPMENT.md),
[AI disclosure](AI_DISCLOSURE.md) and [operational warnings](DISCLAIMER.md).

## Issues

Describe the expected and actual behavior, version/commit, OS, Java/Node
version, player model/firmware and reproduction steps. Reproduce with the
simulator where possible. Never upload passwords, complete private
configuration, music files, private network captures or unsanitized logs.

For a suspected security problem, follow [SECURITY.md](SECURITY.md) rather than
posting exploitable details in a public issue.

## Pull requests

- Keep changes focused; explain observable behavior changes.
- Preserve existing attribution and add provenance for copied code/data.
- License original contributions under EPL-2.0, consistent with [LICENSE](LICENSE).
- State any AI assistance and the checks actually performed. Do not claim
  third-party work as your own or as AI-generated.
- Add regression tests and update directly affected documentation.
- Run backend tests, frontend type checks and a production build as described
  in the development guide.
- Test protocol/output changes on appropriate hardware before claiming support.

Do not submit secrets, generated distributions, caches or proprietary media.
Contributions must be material you are authorized to submit.
