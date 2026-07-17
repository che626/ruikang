# Models

- Default model path: `models/ruikang.onnx`.
- The backend publishes the model manifest and model bytes; ONNX inference runs in the browser worker.
- The browser prefers WebGPU and falls back to WASM when WebGPU is unavailable.
- The manifest includes a SHA-256 digest. The browser verifies the downloaded model before creating the ONNX Runtime session.
- `data.yaml` stores the class order exported by the current training set. The backend uses it to synchronize `defect_type`, so deploy it together with `ruikang.onnx`.
