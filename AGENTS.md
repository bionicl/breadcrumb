# Codex instructions

When the user asks about the current Breadcrumb installation on their Pixel (analysis queue,
Review suggestions, a date's location/sensor data, or app logs), query the connected debug app with
`python3 tools/breadcrumb_debug.py` before answering. The command prints a JSON object containing
`output_file`; read that file and base the answer on its contents. For date questions run
`python3 tools/breadcrumb_debug.py day YYYY-MM-DD`; the app's current timezone is used unless
`--timezone IANA_ZONE` is supplied. Other commands are `status`, `suggestions [--state STATE]`, and
`logs`. If ADB or the debug install is unavailable, say so and continue from any evidence already
provided. This interface is read-only; do not infer that a queue item was applied from its presence.
