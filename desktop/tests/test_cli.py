"""Integration checks of the installed JVM program. No phone is used."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CLI = Path(__file__).resolve().parents[1] / "app/build/install/desktop-cli/bin/desktop-cli"


class DesktopTest(unittest.TestCase):
    def invoke(self, *args, env=None):
        return subprocess.run([str(CLI), *args], capture_output=True, text=True, timeout=20, env=env)

    def test_capabilities_do_not_claim_battle(self):
        result = self.invoke("capabilities")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(json.loads(result.stdout)["battle"])

    def test_original_command_parser(self):
        result = self.invoke("parse", "a1b2c3,4,#,5,#,6")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)["waves"]), 3)
        bad = self.invoke("parse", "?")
        self.assertNotEqual(bad.returncode, 0)
        self.assertIn("error", json.loads(bad.stdout))

    def test_original_geometry(self):
        result = self.invoke("geometry", "1920", "1080")
        self.assertEqual(json.loads(result.stdout)["width"], 1920)
        self.assertNotEqual(self.invoke("geometry", "0", "1080").returncode, 0)

    def test_adb_png_and_serial_forwarding(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
            fake = root / "adb"
            fake.write_text(f'#!{sys.executable}\nimport sys\nassert sys.argv[1:] == ["-s","FAKE:5555","exec-out","screencap","-p"]\nsys.stdout.buffer.write({png!r})\n')
            fake.chmod(0o755)
            output = root / "capture.png"
            result = self.invoke("screenshot", "FAKE:5555", str(output), env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"]))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(output.read_bytes(), png)
            self.assertNotEqual(self.invoke("screenshot", "bad\nserial", str(output)).returncode, 0)


if __name__ == "__main__":
    unittest.main()
