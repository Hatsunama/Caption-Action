# Existing model artifact identities

The download URLs, model filenames, engines, and approximate display sizes are unchanged. Readiness now requires exact length and SHA-256 of the actual complete file. The application does not adopt server-provided digests, successful native loads, timestamps, or persisted readiness markers as proof of integrity.

Approved upstream tuples were verified from the official Hugging Face Git LFS pointers on 2026-10-12:

- BALANCED, `ggml-tiny-q5_1.bin`: 32,152,673 bytes; SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.
  - https://huggingface.co/ggerganov/whisper.cpp/raw/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin
- ACCURATE, `ggml-small-q5_1.bin`: 190,085,487 bytes; SHA-256 `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`.
  - https://huggingface.co/ggerganov/whisper.cpp/raw/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-small-q5_1.bin
- FAST, `model.int8.onnx`: 239,233,841 bytes; SHA-256 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`.
  - https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/raw/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx

Current-main pointers matched these immutable pointer values at research time. No model payload was downloaded for this research. These tuples are not evidence that historical approved or device-cached model bytes match. SenseVoice upstream history includes a previous different quantization artifact. Existing final files are preserved unless a user-requested successful verified download atomically replaces an invalid final. Mismatched files remain unready, with recovery available.

Cache verification runs on IO, under the same process-wide canonical-path mutex as downloading and native loading. Cold-process readiness starts unverified. Metadata may only invalidate an already byte-verified process-local snapshot. Gate checks and native loading force a new byte verification; native loading holds the lock until its load call returns. This protects cooperating in-process writers, not malicious external same-UID/root writers or a future separate application process.

Promotion requires a complete approved partial, a successful file sync, and same-directory atomic replacement. No delete-destination or in-place copy fallback is allowed. Atomic-move failure leaves the complete partial retryable. The destination is reverified before readiness is published. A pre-existing matching-identity/stamp verified snapshot remains usable during revalidation; an actual mismatch or read failure removes it. Cold verification remains untrusted. Cancellation before new publication cannot create a ready token; cancellation strictly after successful byte verification/publication does not make those verified bytes invalid and may retain readiness.

Regression fixtures use an internal-only identity/filesystem boundary. It defaults to the immutable production constants, is not exposed as a user/server/runtime override, and tests hash the actual small fixture bytes. Fixture success is not real-model/device/native verification.
