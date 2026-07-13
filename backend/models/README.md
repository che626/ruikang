# Models

- Default runtime model path: `models/ruikang.onnx`
- Current deployment rule: prefer GPU, fall back to CPU
- The real `ruikang.onnx` file can be copied into this directory for local runtime
- `data.yaml` stores the class order exported by the current model training set. The backend reads it on startup to sync `defect_type`, so deploy it together with `ruikang.onnx`.
