"""Contract-only fixtures: never production training material."""
import copy
import hashlib
import importlib.util
import json
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

dataset = __import__('expert_dataset') if importlib.util.find_spec('expert_dataset') else None


def fixture():
    sources, examples = [], []
    for i, split in enumerate(('train', 'valid', 'test')):
        sources.append(dict(sourceId=f's{i}', title=f'Test source {i}',
                            url=f'https://example.invalid/{i}', license='OWNED',
                            licenseEvidenceUrl=f'https://example.invalid/license/{i}',
                            contentSha256=str(i) * 64, usage='TRAINING_ALLOWED'))
        examples.append(dict(exampleId=f'e{i}', sourceIds=[f's{i}'], groupId=f'g{i}',
                             split=split, question=f'Test question {i}?',
                             context=f'Test context {i}.', answer=f'Test answer {i}.',
                             origin='HUMAN_AUTHORED',
                             verification=dict(status='VERIFIED', reference='Test review record')))
    return dict(schemaVersion=1, datasetId='contract-test', sources=sources, examples=examples)


class ExpertDatasetTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(dataset, 'Task1 expert_dataset implementation must exist')

    def reject(self, value, field=None):
        with self.assertRaises(dataset.DatasetValidationError) as caught:
            dataset.validate_dataset(value)
        errors = caught.exception.errors
        self.assertTrue(1 <= len(errors) <= 50)
        for error in errors:
            self.assertEqual(set(error), {'row', 'field', 'code', 'message'})
            self.assertIs(type(error['row']), int)
            self.assertTrue(all(isinstance(error[k], str) and error[k] for k in ('field', 'code', 'message')))
        if field:
            self.assertTrue(any(field in e['field'] for e in errors), errors)
        return errors

    def test_valid_canonical_provenance_hash_and_no_mutation(self):
        payload = fixture()
        payload['examples'][0]['origin'] = 'SYNTHETIC'
        payload['examples'][0]['question'] = '  Original\nquestion?  '
        original = copy.deepcopy(payload)
        result = dataset.validate_dataset(payload)
        self.assertEqual(payload, original)
        self.assertEqual(result['counts'], dict(train=1, valid=1, test=1))
        self.assertEqual(result['examples'], payload['examples'])
        self.assertEqual(result['sources'], payload['sources'])
        canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
        self.assertEqual(result['datasetSha256'], hashlib.sha256(canonical).hexdigest())
        payload['examples'][0]['context'] += ' changed'
        self.assertNotEqual(result['datasetSha256'], dataset.validate_dataset(payload)['datasetSha256'])

    def test_order_independence(self):
        payload = fixture()
        expected = dataset.validate_dataset(payload)
        payload['sources'].reverse()
        payload['examples'].reverse()
        self.assertEqual(expected, dataset.validate_dataset(payload))

    def test_strict_top_level(self):
        for value in (None, [], True, 1, 'secret'):
            self.reject(value)
        for key, value in [('schemaVersion', True), ('schemaVersion', 2),
                           ('datasetId', []), ('datasetId', '../secret'),
                           ('datasetId', 'é'), ('datasetId', 'a' * 65),
                           ('sources', {}), ('examples', None), ('unknown-secret', 1),
                           ('datasetSha256', 'x'), ('counts', {})]:
            payload = fixture()
            payload[key] = value
            errors = self.reject(payload)
            self.assertNotIn('unknown-secret', str(errors))
        for key in fixture():
            payload = fixture()
            del payload[key]
            self.reject(payload)

    def test_source_gates(self):
        invalid = {'sourceId': [[], 'bad id', '', 'a' * 65],
                   'title': [None, '', ' ', 'x' * 301, 'x\x00', '\ud800'],
                   'url': [[], 'http://example.invalid', 'https:///path',
                           'https://u:p@example.invalid', 'https://example.invalid:bad',
                           'https://exa mple.invalid', 'https://example.invalid/\npath', 'https://' + 'a' * 2048],
                   'licenseEvidenceUrl': ['http://example.invalid', {}],
                   'license': ['CC-BY-NC-4.0', [], None],
                   'contentSha256': ['A' * 64, '0' * 63, []],
                   'usage': ['RETRIEVAL_ONLY', [], None]}
        for key, values in invalid.items():
            for value in values:
                with self.subTest(key=key, value=repr(value)):
                    payload = fixture()
                    payload['sources'][0][key] = value
                    self.reject(payload, key)
        for license_name in ('CC0-1.0', 'CC-BY-4.0', 'PUBLIC_DOMAIN', 'OWNED'):
            payload = fixture()
            payload['sources'][0]['license'] = license_name
            dataset.validate_dataset(payload)

    def test_example_gates(self):
        invalid = {'exampleId': [[], '', 'bad id'], 'groupId': [[], '', 'é'],
                   'sourceIds': [None, [], ['missing'], ['s0', 's0'], [[]], ['s0'] * 11],
                   'split': [[], 'validation'], 'origin': [[], 'AUTOMATIC'],
                   'question': [None, ' ', 'x' * 2001, '\udfff', 'x\x7f'],
                   'context': [[], '', 'x' * 12001, 'x\x00'],
                   'answer': [False, '\t\n', 'x' * 6001],
                   'verification': [None, [], {}, {'status': 'VERIFIED'},
                                    {'status': 'SELF_VERIFIED', 'reference': 'x'},
                                    {'status': [], 'reference': 'x'},
                                    {'status': 'VERIFIED', 'reference': ' '},
                                    {'status': 'VERIFIED', 'reference': 'x' * 301},
                                    {'status': 'VERIFIED', 'reference': 'x', 'extra': 1}]}
        for key, values in invalid.items():
            for value in values:
                with self.subTest(key=key, value=repr(value)):
                    payload = fixture()
                    payload['examples'][0][key] = value
                    self.reject(payload, key)

    def test_missing_unknown_and_non_object_records(self):
        for collection in ('sources', 'examples'):
            for key in fixture()[collection][0]:
                payload = fixture()
                del payload[collection][0][key]
                self.reject(payload, collection)
            for value in (None, [], True, {'secret-key': 'sensitive'}):
                payload = fixture()
                payload[collection][0] = value
                errors = self.reject(payload, collection)
                self.assertNotIn('sensitive', str(errors))
            payload = fixture()
            payload[collection][0]['extra'] = 'secret'
            self.reject(payload, collection)

    def test_array_bounds_and_bounded_safe_errors(self):
        for collection, limit in (('sources', 1000), ('examples', 10000)):
            for value in ([], fixture()[collection][:2], [None] * (limit + 1)):
                payload = fixture()
                payload[collection] = value
                self.reject(payload, collection)
        payload = fixture()
        payload['examples'] = [None] * 100
        self.assertEqual(len(self.reject(payload)), 50)

    def test_duplicates_leakage_and_unused_sources(self):
        changes = [('sources', 'sourceId', 's0'), ('sources', 'contentSha256', '0' * 64),
                   ('examples', 'exampleId', 'e0'), ('examples', 'groupId', 'g0'),
                   ('examples', 'sourceIds', ['s0']),
                   ('examples', 'question', '  ＴＥＳＴ\tQUESTION  0?  ')]
        for collection, key, value in changes:
            payload = fixture()
            payload[collection][1][key] = value
            self.reject(payload, key)
        payload = fixture()
        extra = copy.deepcopy(payload['examples'][0])
        extra.update(exampleId='extra', question=' TEST  QUESTION 0? ', answer=' TEST ANSWER 0. ')
        payload['examples'].append(extra)
        self.reject(payload, 'examples')
        payload = fixture()
        extra = copy.deepcopy(payload['sources'][0])
        extra['sourceId'] = 'unused'
        payload['sources'].append(extra)
        self.reject(payload, 'sources')
        payload = fixture()
        payload['examples'][2]['split'] = 'train'
        self.reject(payload, 'examples')

    def test_shared_sources_groups_and_questions_allowed_within_split(self):
        payload = fixture()
        extra = copy.deepcopy(payload['examples'][0])
        extra.update(exampleId='extra', answer='A distinct test answer')
        payload['examples'].append(extra)
        self.assertEqual(dataset.validate_dataset(payload)['counts']['train'], 2)

    def test_export_determinism_permissions_provenance_and_chat(self):
        payload = fixture()
        payload['examples'][0]['origin'] = 'SYNTHETIC'
        with tempfile.TemporaryDirectory() as root:
            first, second = Path(root) / 'one', Path(root) / 'two'
            manifest = dataset.write_dataset(first, dataset.validate_dataset(payload))
            payload['sources'].reverse()
            payload['examples'].reverse()
            self.assertEqual(manifest, dataset.write_dataset(second, payload))
            self.assertEqual(manifest['purpose'], 'EXPERT_SFT_DATASET')
            self.assertEqual(manifest['qualityStatus'], 'NOT_EVALUATED')
            self.assertEqual(manifest['sources'], dataset.validate_dataset(payload)['sources'])
            self.assertEqual(manifest['examples'], dataset.validate_dataset(payload)['examples'])
            self.assertEqual(stat.S_IMODE(first.stat().st_mode), 0o700)
            self.assertEqual(set(p.name for p in first.iterdir()),
                             {'manifest.json', 'records.json', 'train.jsonl', 'valid.jsonl', 'test.jsonl'})
            for path in first.iterdir():
                self.assertEqual(path.read_bytes(), (second / path.name).read_bytes())
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(json.loads((first / 'manifest.json').read_text()), manifest)
            self.assertEqual(json.loads((first / 'records.json').read_text()), dataset.validate_dataset(payload))
            for filename, digest in manifest['files'].items():
                self.assertEqual(digest, hashlib.sha256((first / filename).read_bytes()).hexdigest())
            self.assertEqual(set(manifest['files']), {'records.json', 'train.jsonl', 'valid.jsonl', 'test.jsonl'})
            instructions = set()
            for i, split in enumerate(('train', 'valid', 'test')):
                rows = (first / f'{split}.jsonl').read_text().splitlines()
                self.assertEqual(len(rows), 1)
                chat = json.loads(rows[0])
                self.assertEqual(set(chat), {'messages'})
                messages = chat['messages']
                self.assertEqual([m['role'] for m in messages], ['system', 'user', 'assistant'])
                instructions.add(messages[0]['content'])
                self.assertIn(f'Test question {i}?', messages[1]['content'])
                self.assertIn(f'Test context {i}.', messages[1]['content'])
                self.assertEqual(messages[2]['content'], f'Test answer {i}.')
            self.assertEqual(len(instructions), 1)
            self.assertIn('insufficient', next(iter(instructions)).lower())

    def test_writer_revalidates_computed_fields_and_source_data(self):
        payload = dataset.validate_dataset(fixture())
        payload.update(counts={'train': 999}, datasetSha256='forged')
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'dataset'
            result = dataset.write_dataset(path, payload)
            self.assertEqual(result['counts'], dict(train=1, valid=1, test=1))
            self.assertEqual(result['datasetSha256'], dataset.validate_dataset(fixture())['datasetSha256'])
            payload['examples'][0]['verification']['status'] = 'SELF_VERIFIED'
            with self.assertRaises(dataset.DatasetValidationError):
                dataset.write_dataset(Path(root) / 'invalid', payload)
            self.assertFalse((Path(root) / 'invalid').exists())

    def test_writer_exclusive_destinations_and_rollback(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            existing = root / 'existing'
            existing.mkdir()
            sentinel = existing / 'keep'
            sentinel.write_text('preserve')
            link = root / 'link'
            link.symlink_to(existing, target_is_directory=True)
            dangling = root / 'dangling'
            dangling.symlink_to(root / 'missing')
            for path in (existing, link, dangling, sentinel):
                with self.assertRaises(FileExistsError):
                    dataset.write_dataset(path, fixture())
            self.assertEqual(sentinel.read_text(), 'preserve')
            target = root / 'failed'
            original = dataset.os.write
            def fail_after_partial(fd, data):
                original(fd, data[:10])
                raise OSError('injected write failure')
            with patch.object(dataset.os, 'write', side_effect=fail_after_partial):
                with self.assertRaises(OSError):
                    dataset.write_dataset(target, fixture())
            self.assertFalse(target.exists())
            self.assertEqual(sentinel.read_text(), 'preserve')

    def test_source_reference_order_is_canonical(self):
        payload = fixture()
        extra = copy.deepcopy(payload['sources'][0])
        extra['sourceId'] = 'additional'
        payload['sources'].append(extra)
        payload['examples'][0]['sourceIds'].append('additional')
        expected = dataset.validate_dataset(payload)
        payload['examples'][0]['sourceIds'].reverse()
        self.assertEqual(expected, dataset.validate_dataset(payload))

    def test_writer_handles_short_writes_and_preserves_foreign_files_on_failure(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'short'
            original = dataset.os.write
            with patch.object(dataset.os, 'write', side_effect=lambda fd, data: original(fd, data[:17])):
                manifest = dataset.write_dataset(target, fixture())
            self.assertEqual(json.loads((target / 'manifest.json').read_text()), manifest)
            failed = Path(root) / 'failed'
            calls = 0
            def fail_late(fd, data):
                nonlocal calls
                calls += 1
                if calls == 3:
                    (failed / 'foreign').write_text('preserve')
                    raise OSError('injected late failure')
                return original(fd, data)
            with patch.object(dataset.os, 'write', side_effect=fail_late):
                with self.assertRaises(OSError):
                    dataset.write_dataset(failed, fixture())
            self.assertEqual([p.name for p in failed.iterdir()], ['foreign'])
            self.assertEqual((failed / 'foreign').read_text(), 'preserve')


if __name__ == '__main__':
    unittest.main()
