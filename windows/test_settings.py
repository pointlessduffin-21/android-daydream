import copy
import json
import os
import tempfile
import unittest
from unittest.mock import patch

import core
import settings_ui


def fields_for(cfg):
    fields = {}
    for name, value in cfg.items():
        if isinstance(value, dict):
            fields.update({f'{name}.{key}': subvalue for key, subvalue in value.items()})
        else:
            fields[name] = value
    fields['folders'] = '\n'.join(cfg['folders'])
    fields['immich.albums'] = ', '.join(cfg['immich']['albums'])
    fields['interval_seconds'] = str(cfg['interval_seconds'])
    fields['display'] = str(cfg['display'])
    return fields


class SettingsTest(unittest.TestCase):
    def setUp(self):
        self.cfg = copy.deepcopy(core.DEFAULTS)
        self.fields = fields_for(self.cfg)

    def test_editor_round_trip_and_unknown_settings(self):
        self.cfg['future_option'] = {'keep': True}
        self.fields.update({'folders': ' D:/Photos \n\n ~/Pictures ', 'weather.city': ' Manila ',
                            'clock_24h': True, 'interval_seconds': '30'})
        cfg = settings_ui.config_from_fields(self.cfg, self.fields)
        self.assertEqual(cfg['folders'], ['D:/Photos', '~/Pictures'])
        self.assertEqual(cfg['weather']['city'], 'Manila')
        self.assertEqual(cfg['interval_seconds'], 30)
        self.assertTrue(cfg['clock_24h'])
        self.assertEqual(cfg['future_option'], {'keep': True})
        self.assertEqual(self.cfg['interval_seconds'], 15)

    def test_invalid_input_does_not_mutate_config(self):
        for key, value in [('interval_seconds', '4'), ('interval_seconds', '301'),
                           ('interval_seconds', 'fast'), ('display', '-1'), ('folders', ''),
                           ('layout', 'bad')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                settings_ui.config_from_fields(self.cfg, dict(self.fields, **{key: value}))
        self.assertEqual(self.cfg, core.DEFAULTS)

    def test_immich_validation_and_album_ids(self):
        fields = dict(self.fields, photo_source='immich')
        fields.update({'immich.url': 'https://photos.example.com', 'immich.api_key': 'valid',
                       'immich.mode': 'albums', 'immich.albums': 'album1, album2\nalbum3'})
        cfg = settings_ui.config_from_fields(self.cfg, fields)
        self.assertEqual(cfg['immich']['albums'], ['album1', 'album2', 'album3'])
        for key, value in [('immich.api_key', ''), ('immich.url', 'http://photos.example.com'),
                           ('immich.albums', '../bad'), ('immich.albums', '')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                settings_ui.config_from_fields(self.cfg, dict(fields, **{key: value}))

    def test_atomic_save_and_failure_preserves_original(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, 'config.json')
            settings_ui.save_config(path, self.cfg)
            loaded, error = core.load_config(path)
            self.assertIsNone(error)
            self.assertEqual(loaded, self.cfg)
            with patch('settings_ui.os.replace', side_effect=OSError('disk error')):
                with self.assertRaises(OSError):
                    settings_ui.save_config(path, dict(self.cfg, clock_24h=True))
            with open(path, encoding='utf-8') as stream:
                self.assertEqual(json.load(stream), self.cfg)
            self.assertEqual(os.listdir(directory), ['config.json'])

    def test_apply_reloads_configuration_and_restores_display_state(self):
        import threading
        from types import SimpleNamespace
        from unittest.mock import Mock
        import daydream

        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, 'config.json')
            settings_ui.save_config(path, self.cfg)
            first = SimpleNamespace(stop=threading.Event(), windowed=False, _set_mode=Mock())
            second = SimpleNamespace(stop=threading.Event(), windowed=False, run=Mock(return_value=None))
            def request_settings():
                first.page, first.face, first.stack = 2, 1, 3
                return 'settings'
            first.run = request_settings
            def apply(cfg, config_path, error, displays):
                settings_ui.save_config(config_path, dict(cfg, clock_24h=True))
                return True
            with patch('daydream.App', side_effect=[first, second]) as app_factory, \
                 patch('daydream.win32_setup'), \
                 patch('daydream.pygame.display.get_desktop_sizes', return_value=[(1920, 1080)]), \
                 patch('daydream.pygame.mouse.set_visible'), \
                 patch('settings_ui.show_settings', side_effect=apply):
                daydream.main(['--config', path])
            self.assertTrue(app_factory.call_args_list[1].args[0]['clock_24h'])
            self.assertFalse(app_factory.call_args_list[1].kwargs['windowed'])
            self.assertEqual((second.page, second.face, second.stack), (2, 1, 3))
            first._set_mode.assert_called_once()
            self.assertTrue(first.stop.is_set())
            self.assertTrue(second.stop.is_set())


if __name__ == '__main__':
    unittest.main()
