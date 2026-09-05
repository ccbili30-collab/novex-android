import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('boundary', Path(__file__).parents[1] / 'check_novex_ui.py')
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)

class ComponentBoundaryTest(unittest.TestCase):
    def test_all_source_spellings_of_legacy_dialog_are_rejected(self):
        for source in ['import androidx.compose.material3.AlertDialog',
                       'import androidx.compose.material3.AlertDialog as OldDialog',
                       'androidx.compose.material3.AlertDialog(onDismissRequest = {})',
                       'import androidx.compose.material3.*']:
            self.assertTrue(boundary.violations(source, 'settings/Example.kt'))

    def test_shared_controls_can_own_platform_behavior(self):
        self.assertFalse(boundary.violations('import androidx.compose.material3.ModalBottomSheet', 'novex/NovexMaterialControls.kt'))

    def test_comments_and_novex_calls_are_not_violations(self):
        self.assertFalse(boundary.violations('// androidx.compose.material3.AlertDialog()\nimport com.openminis.app.ui.novex.AlertDialog', 'settings/Example.kt'))

    def test_legacy_decoration_cannot_hide_behind_basic_text_field(self):
        self.assertTrue(boundary.violations('OutlinedTextFieldDefaults.DecorationBox(value = query)', 'components/Picker.kt'))

if __name__ == '__main__':
    unittest.main()
