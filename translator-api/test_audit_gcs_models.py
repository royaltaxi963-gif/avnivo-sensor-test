"""Offline manifest audit tests: no GCS network calls."""
import hashlib
import io
import unittest
from unittest.mock import MagicMock
from audit_gcs_models import audit

class Blob:
    def __init__(self,name,data):
        self.name=name;self.data=data;self.size=len(data)
    def open(self,*args,**kwargs):
        return io.BytesIO(self.data)

class AuditTests(unittest.TestCase):
    def test_audit_uses_actual_sha256(self):
        b=Blob('tts/uzbek/navoiy/model.onnx',b'AVNIVO test bytes')
        client=MagicMock()
        client.list_blobs.return_value=[b]
        manifest=audit(client,'private',{'navoiy-tts':'tts/uzbek/navoiy/'})
        self.assertEqual(manifest['navoiy-tts'][0]['sha256'],hashlib.sha256(b.data).hexdigest())
        self.assertEqual(manifest['navoiy-tts'][0]['bytes'],len(b.data))
    def test_empty_model_is_not_enabled(self):
        client=MagicMock()
        client.list_blobs.return_value=[]
        self.assertEqual(audit(client,'private',{'mms-tts':'tts/mms/'}),{})
    def test_path_escape_rejected(self):
        client=MagicMock()
        client.list_blobs.return_value=[Blob('tts/mms/../secret',b'abc')]
        with self.assertRaises(ValueError):
            audit(client,'private',{'mms-tts':'tts/mms/'})

if __name__=='__main__':
    unittest.main()
