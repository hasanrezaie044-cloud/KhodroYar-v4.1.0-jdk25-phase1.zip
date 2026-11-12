import importlib.util
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

spec = importlib.util.spec_from_file_location('permissions', Path(__file__).with_name('check-permissions.py'))
permissions = importlib.util.module_from_spec(spec); spec.loader.exec_module(permissions)

class PermissionAuditTest(unittest.TestCase):
    def test_required_network_permissions_are_allowed(self):
        self.assertEqual([], permissions.audit(permissions.REQUIRED))
    def test_removing_network_permissions_is_an_error(self):
        self.assertTrue(permissions.audit(set()))
    def test_banned_and_unknown_permissions_fail(self):
        for name in ['android.permission.CAMERA', 'android.permission.READ_SMS', 'android.permission.ACCESS_FINE_LOCATION', 'com.untrusted.PERMISSION']:
            self.assertTrue(permissions.audit(permissions.REQUIRED | {name}))
    def test_aapt_does_not_lose_app_owned_permissions(self):
        text = "uses-permission: name='android.permission.INTERNET'\nuses-permission: name='com.khodroyar.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
        self.assertIn('com.khodroyar.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', permissions.aapt_permissions(text))
    def test_source_xml_multiline_and_remove_markers(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / 'AndroidManifest.xml'
            path.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"><uses-permission\n android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.CAMERA" tools:node="remove"/></manifest>''')
            self.assertEqual({'android.permission.INTERNET'}, permissions.source_permissions(path))

if __name__ == '__main__': unittest.main()
