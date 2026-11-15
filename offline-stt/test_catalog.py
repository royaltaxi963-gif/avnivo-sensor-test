import copy
import json
import unittest
from pathlib import Path
from validate_catalog import validate

BASE = json.loads(Path(__file__).with_name("catalog.json").read_text(encoding="utf-8"))

class CatalogTests(unittest.TestCase):
    def test_initial_catalog(self):
        self.assertTrue(validate(BASE))
    def test_duplicate_language(self):
        bad = copy.deepcopy(BASE)
        bad["languages"][1]["language_tag"] = "uz-UZ"
        with self.assertRaises(ValueError): validate(bad)
    def test_unverified_download_rejected(self):
        bad = copy.deepcopy(BASE)
        bad["languages"][0]["artifacts"] = [{"download_url": "https://example.com/fake.bin"}]
        with self.assertRaises(ValueError): validate(bad)
    def test_available_without_artifact_rejected(self):
        bad = copy.deepcopy(BASE)
        bad["languages"][0]["status"] = "available"
        with self.assertRaises(ValueError): validate(bad)

if __name__ == "__main__":
    unittest.main()
