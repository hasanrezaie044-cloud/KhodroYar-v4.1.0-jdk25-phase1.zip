# Third-party asset notices

## Persian ALBERT model and app-specific modifications (5.5.0)

- Source: [`HooshvareLab/albert-fa-zwnj-base-v2`](https://huggingface.co/HooshvareLab/albert-fa-zwnj-base-v2),
  immutable revision `2315c0d4ddd6bd68f7703c5651978706bf8aff37`.
- The upstream model card declares **Apache-2.0**. Full terms, the upstream model card and a
  prominent modification notice are packaged in the APK. Model/card/source hashes are checked
  during the build; the original `.bin` is not shipped alongside the converted model.
- Modifications: frozen original ALBERT encoder, original authored app-intent training examples,
  a supervised task-specific head replacing the unused fill-mask head, ONNX export with
  shared weights preserved, int8 matrix/embedding quantization and a 64-token limit.
- This is **not** a ready-made general chatbot, a fine-tune on user records or a rule-only parser
  labelled as ALBERT. The runtime actually invokes the bundled model; deterministic fallback is
  explicitly labelled on low-memory/unsupported devices. See [architecture and reproduction](docs/OFFLINE-ALBERT.md).
- Full Apache text: `android/app/src/main/assets/licenses/ALBERT-Apache-2.0.txt`.
  Source/modifications: `licenses/KhodroYar-ALBERT-NOTICE.txt` in the same assets directory.
  The generated `albert/UPSTREAM-MODEL-CARD.md` retains the original card without rewriting it.
- No vendor endorsement, complete training-data rights audit or marketplace approval is claimed.

## ONNX Runtime Android 1.23.2

- [Microsoft ONNX Runtime, tag v1.23.2](https://github.com/microsoft/onnxruntime/tree/v1.23.2), **MIT**.
- CPU-only Android runtime; no runtime model downloads, model telemetry or cloud inference.
- The full upstream `LICENSE` and `ThirdPartyNotices.txt` (including bundled dependency notices)
  are retained in APK `assets/licenses/ONNX-Runtime-{MIT,ThirdPartyNotices}.txt`.
- Build-only PyTorch/Transformers/scikit-learn/ONNX tooling is pinned in
  `tools/offline-albert/requirements.txt`; it is not installed in or shipped as Python in the APK.

## SentencePiece 0.2.1

- [Google SentencePiece](https://github.com/google/sentencepiece), **Apache-2.0**, pinned commit
  `31646a467d2051eb904e0b45de3a73e91fe1c1e3`. Unmodified upstream source builds a static
  processor linked into KhodroYar's original JNI adapter. Training/CLI tools are not packaged.
- Pinned source archive SHA256:
  `b4eb17e6bea5c9380ddd79d04d01d6aa5e280be8f3e3cef1f745a80e6d9451ce`.
- The processor uses the original model's pinned `spiece.model`, not an approximate tokenizer.
- Full Apache terms and bundled Abseil/Darts/esaxx/Protobuf-lite licence texts are retained in
  `android/app/src/main/assets/licenses/SentencePiece-*.txt`. Some notices are preserved
  conservatively even when only needed by upstream training code.
- All new model/runtime/tokenizer notices can be read offline under Settings → About →
  **Offline model & licences**. The app-specific notice distinguishes local inference from
  existing, separately consented optional online AI/traffic services.

## Vazirmatn font

- Author/project: [Saber Rastikerdar / Vazirmatn](https://github.com/rastikerdar/vazirmatn)
- Licence: **SIL Open Font License 1.1** (copyright and complete terms are packaged in
  `android/app/src/main/res/raw/vazirmatn_license.txt`).
- Upstream revision: `6e553e33489a8f9dfaccc76860a2e3f3c1e66de7`.
- Unmodified upstream TTF files, from `fonts/ttf/`: Regular, Medium, SemiBold, Bold.
- Bundled under `android/app/src/main/res/font/`; combined TTF size **491,364 bytes**.
- These fonts load locally. No font service, API key or Internet request is used.

The font is distributed as part of the application, not sold on its own. Original font
names and copyright notices are preserved. The OFL text is retained as an Android raw
resource (`res/raw/vazirmatn_license.txt`) as well as in source.
