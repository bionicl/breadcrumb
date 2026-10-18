import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("breadcrumb_debug.py")
SPEC = importlib.util.spec_from_file_location("breadcrumb_debug", SCRIPT)
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)


class BreadcrumbDebugCliTest(unittest.TestCase):
    def test_day_defaults_to_device_timezone_and_accepts_override(self):
        defaults = bridge.parser().parse_args(["day", "2026-10-18"])
        self.assertIsNone(defaults.timezone)
        bridge.validate(defaults)
        override = bridge.parser().parse_args(["day", "2026-10-18", "--timezone", "Europe/Warsaw"])
        bridge.validate(override)

    def test_invalid_day_and_timezone_are_rejected(self):
        for arguments in (
            ["day", "2026-02-30"],
            ["day", "2026-10-18", "--timezone", "Mars/Olympus"],
        ):
            with self.subTest(arguments=arguments), self.assertRaises(bridge.BridgeError):
                bridge.validate(bridge.parser().parse_args(arguments))

    def test_suggestion_limit_is_bounded(self):
        for limit in (0, 2001):
            with self.subTest(limit=limit), self.assertRaises(bridge.BridgeError):
                bridge.validate(bridge.parser().parse_args(["suggestions", "--limit", str(limit)]))

    @patch.object(bridge, "find_adb", return_value="adb")
    @patch.object(bridge, "run")
    def test_device_selection_requires_one_online_device(self, run, _find_adb):
        run.return_value.stdout = "List of devices attached\n"
        with self.assertRaisesRegex(bridge.BridgeError, "Brak urządzenia"):
            bridge.adb_prefix(None)
        run.return_value.stdout = "List of devices attached\na\tdevice\nb\tdevice\n"
        with self.assertRaisesRegex(bridge.BridgeError, "kilka urządzeń"):
            bridge.adb_prefix(None)
        self.assertEqual(["adb", "-s", "pixel"], bridge.adb_prefix("pixel"))


if __name__ == "__main__":
    unittest.main()
