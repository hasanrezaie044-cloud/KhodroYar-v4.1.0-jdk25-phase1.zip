#!/usr/bin/env python3
"""Build-time ONLY: verified Persian ALBERT -> trained intent head -> int8 ONNX.

No personal data. No runtime downloads. No remotely supplied Python code. The
encoder is frozen; only a small supervised intent head is fitted. Separate
validation chooses calibration, then the authored holdout is measured once.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import time
import unicodedata
import urllib.request

HERE = Path(__file__).resolve().parent
LOCK = json.loads((HERE / 'source-lock.json').read_text())


def normalize(text):
    table = str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩يىك٫٬،\u200c\u00a0',
                         '01234567890123456789ییک.,,  ')
    text = unicodedata.normalize('NFKC', text).lower().translate(table)
    text = ''.join(c for c in text if not unicodedata.category(c).startswith('M') and c != 'ـ')
    return ' '.join(text.split())


def sha(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def download(source):
    source.mkdir(parents=True, exist_ok=True)
    for name, expected in LOCK['files'].items():
        path = source / name
        def valid():
            if not path.exists() or path.stat().st_size != expected['bytes']:
                return False
            if 'sha256' in expected:
                return sha(path) == expected['sha256']
            data = path.read_bytes()
            return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == expected['git_blob']
        if not valid():
            url = f'https://huggingface.co/{LOCK["model"]}/resolve/{LOCK["revision"]}/{name}'
            print('Downloading pinned public model asset:', name, flush=True)
            temp = path.with_suffix(path.suffix + '.partial')
            with urllib.request.urlopen(url, timeout=120) as response, temp.open('wb') as out:
                shutil.copyfileobj(response, out)
            temp.replace(path)
        if not valid():
            raise ValueError('Pinned source checksum mismatch: ' + name)
    card = (source / 'README.md').read_text()
    assert 'license: apache-2.0' in card


def examples():
    doc = json.loads((HERE / 'intents.json').read_text())
    labels = list(doc['labels'])
    splits = {key: [] for key in ('train', 'validation', 'test')}
    seen = {}
    for label, groups in doc['labels'].items():
        for split, texts in groups.items():
            for text in texts:
                n = normalize(text)
                if n in seen:
                    raise ValueError(f'Duplicate authored example: {seen[n]} / {split}: {text}')
                seen[n] = split
                splits[split].append((text, labels.index(label)))
                if split == 'train':
                    for prefix, suffix in [('لطفا ', ''), ('', ' لطفا')]:
                        splits[split].append((prefix + text + suffix, labels.index(label)))
    return doc, labels, splits


def build(args):
    import numpy as np
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic, QuantType
    import sentencepiece as spm
    import torch
    from torch import nn
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import accuracy_score, log_loss
    from transformers import AlbertConfig, AlbertModel

    started = time.monotonic()
    torch.set_num_threads(2)
    torch.manual_seed(37)
    np.random.seed(37)
    download(args.source)
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    doc, labels, splits = examples()
    sp = spm.SentencePieceProcessor(model_file=str(args.source / 'spiece.model'))
    assert sp.vocab_size() == 30000 and sp.piece_to_id('[CLS]') == 2 and sp.piece_to_id('[SEP]') == 3
    max_tokens = LOCK['maxTokens']

    def ids(text):
        pieces = sp.encode(normalize(text), out_type=int)
        if len(pieces) > max_tokens - 2:
            raise ValueError('Authored example too long; runtime must abstain, never truncate: ' + text)
        row = [2] + pieces + [3]
        return row + [0] * (max_tokens - len(row)), [1] * len(row) + [0] * (max_tokens - len(row))

    config = AlbertConfig(**json.loads((args.source / 'config.json').read_text()))
    encoder = AlbertModel.from_pretrained(str(args.source), config=config, use_safetensors=False,
                                         local_files_only=True, attn_implementation='eager').eval()
    for p in encoder.parameters():
        p.requires_grad_(False)

    def tensors(rows):
        encoded = [ids(t) for t, _ in rows]
        inp = torch.tensor([r[0] for r in encoded], dtype=torch.long)
        mask = torch.tensor([r[1] for r in encoded], dtype=torch.long)
        return inp, mask, torch.zeros_like(inp)

    features, targets = {}, {}
    with torch.inference_mode():
        for split, rows in splits.items():
            batch = []
            for index in range(0, len(rows), 16):
                x, m, typ = tensors(rows[index:index+16])
                batch.append(encoder(x, attention_mask=m, token_type_ids=typ).last_hidden_state[:, 0, :].numpy())
            features[split] = np.concatenate(batch)
            targets[split] = np.array([label for _, label in rows])
            print('Encoded', split, len(rows), flush=True)

    scaler = StandardScaler().fit(features['train'])
    models = []
    for c in (0.01, 0.1, 1.0, 10.0):
        head = LogisticRegression(C=c, max_iter=1500, random_state=37, class_weight='balanced')
        head.fit(scaler.transform(features['train']), targets['train'])
        p = head.predict_proba(scaler.transform(features['validation']))
        models.append((accuracy_score(targets['validation'], p.argmax(1)), -log_loss(targets['validation'], p), c, head))
    _, _, chosen_c, head = max(models, key=lambda item: item[:2])

    class IntentModel(nn.Module):
        def __init__(self):
            super().__init__()
            self.albert = encoder
            self.register_buffer('feature_mean', torch.tensor(scaler.mean_, dtype=torch.float32))
            self.register_buffer('feature_scale', torch.tensor(scaler.scale_, dtype=torch.float32))
            self.linear = nn.Linear(config.hidden_size, len(labels))
            with torch.no_grad():
                self.linear.weight.copy_(torch.tensor(head.coef_, dtype=torch.float32))
                self.linear.bias.copy_(torch.tensor(head.intercept_, dtype=torch.float32))
        def forward(self, input_ids, attention_mask, token_type_ids):
            f = self.albert(input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state[:, 0, :]
            return self.linear((f - self.feature_mean) / self.feature_scale)

    model = IntentModel().eval()
    sample = tensors(splits['validation'][:1])
    fp32 = out / 'intent-fp32.onnx'
    final = out / 'intent-int8.onnx'
    with torch.inference_mode():
        torch.onnx.export(model, sample, str(fp32), input_names=['input_ids', 'attention_mask', 'token_type_ids'],
                          output_names=['logits'], opset_version=17, do_constant_folding=True, dynamo=False)
    onnx.checker.check_model(str(fp32))
    quantize_dynamic(str(fp32), str(final), weight_type=QuantType.QInt8, per_channel=False,
                     op_types_to_quantize=['MatMul', 'Gemm'])
    onnx.checker.check_model(str(final))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(final), sess_options=options, providers=['CPUExecutionProvider'])

    def predict(rows):
        probs = []
        for text, _ in rows:
            i, m = ids(text)
            z = session.run(['logits'], {'input_ids': np.array([i], dtype=np.int64),
                                        'attention_mask': np.array([m], dtype=np.int64),
                                        'token_type_ids': np.zeros((1,max_tokens), dtype=np.int64)})[0][0]
            z = np.exp(z - z.max())
            probs.append(z / z.sum())
        return np.array(probs)

    validation = predict(splits['validation'])
    other = labels.index('other')
    def accepted(p, threshold, margin):
        sorted_p = np.sort(p, axis=1)
        return (p.argmax(1) != other) & (sorted_p[:, -1] >= threshold) & (sorted_p[:, -1] - sorted_p[:, -2] >= margin)
    candidates = []
    for threshold in (0.55, 0.65, 0.75, 0.85, 0.92):
        for margin in (0.10, 0.20, 0.30, 0.45):
            take = accepted(validation, threshold, margin)
            good = int(((validation.argmax(1) == targets['validation']) & take).sum())
            wrong = int(((validation.argmax(1) != targets['validation']) & take).sum())
            if wrong == 0:
                candidates.append((good, threshold, margin))
    assert candidates, 'No fail-closed calibration found'
    _, threshold, margin = max(candidates, key=lambda x: (x[0], x[1], x[2]))
    holdout = predict(splits['test'])
    take = accepted(holdout, threshold, margin)
    correct = holdout.argmax(1) == targets['test']
    accepted_count = int(take.sum())
    accepted_correct = int((correct & take).sum())
    report = {
        'schema': 1, 'model': LOCK['model'], 'revision': LOCK['revision'], 'license': LOCK['license'],
        'adaptation': 'Original frozen Persian ALBERT encoder + supervised intent head; dynamic-int8 matrices',
        'dataProvenance': doc['provenance'], 'trainingExamplesWithPoliteVariants': len(splits['train']),
        'validationExamples': len(splits['validation']), 'holdoutExamples': len(splits['test']),
        'holdoutRawCorrect': int(correct.sum()), 'holdoutAccepted': accepted_count,
        'holdoutAcceptedCorrect': accepted_correct, 'threshold': threshold, 'minimumMargin': margin,
        'headC': chosen_c, 'buildSeconds': round(time.monotonic()-started,2),
        'limits': ['Authored app-task holdout, not a general Persian benchmark',
                   'Classifier is a hint only; deterministic validation and fresh approval remain mandatory',
                   'No phone RAM, speed or battery claim'],
        'holdout': [{'text': text, 'expected': labels[label], 'predicted': labels[int(holdout[j].argmax())],
                     'score': round(float(holdout[j].max()),5), 'accepted': bool(take[j])}
                    for j,(text,label) in enumerate(splits['test'])]
    }
    (out / 'evaluation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in report.items() if k not in ('holdout',)},ensure_ascii=False),flush=True)
    # A failed model build must not be relabelled as a successful ALBERT release.
    assert int(correct.sum()) >= math.ceil(len(correct) * .70), 'Raw authored holdout below the minimum'
    assert accepted_count >= 16, 'Too few distinct heldout requests accepted'
    assert accepted_correct / accepted_count >= .90, 'Accepted authored holdout precision below 90%'
    assert final.stat().st_size < 45_000_000, 'Unexpected model growth'
    shutil.copyfile(args.source / 'spiece.model', out / 'spiece.model')
    shutil.copyfile(args.source / 'README.md', out / 'UPSTREAM-MODEL-CARD.md')
    fixtures = []
    for text, _ in splits['test'] + splits['validation']:
        i, m = ids(text)
        p = predict([(text,0)])[0]
        fixtures.append({'text':text,'normalized':normalize(text),'ids':i,'mask':m,
                         'label':labels[int(p.argmax())], 'score':float(p.max())})
    (out / 'parity-fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False,separators=(',',':')))
    fp32.unlink()
    inputs = hashlib.sha256()
    for name in ('build_model.py','intents.json','requirements.txt','source-lock.json'):
        inputs.update(name.encode());inputs.update((HERE/name).read_bytes())
    assets = {path.name:{'bytes':path.stat().st_size,'sha256':sha(path)}
              for path in sorted(out.iterdir()) if path.is_file() and path.name != 'manifest.json'}
    manifest = {'schema':1,'model':LOCK['model'],'revision':LOCK['revision'],'license':LOCK['license'],
                'maxTokens':max_tokens,'labels':labels,'threshold':threshold,'minimumMargin':margin,
                'normalization':'LocalIntentParser.normalize / NFKC, Persian digits and letters, whitespace; SentencePiece 0.2.1',
                'sourceInputsSha256':inputs.hexdigest(),'assets':assets}
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    print('::notice title=Offline ALBERT model::'+json.dumps({'bytes':final.stat().st_size,'source':LOCK['revision'],
          'holdout':len(correct),'accepted':accepted_count,'correctAccepted':accepted_correct},separators=(',',':')),flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    build(parser.parse_args())
