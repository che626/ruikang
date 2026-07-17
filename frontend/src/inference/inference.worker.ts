/// <reference lib="webworker" />

import * as ort from "onnxruntime-web/webgpu";
import { postprocessDetectedOutput } from "./postprocess";
import type {
  BrowserInferenceManifest,
  BrowserInferenceResult,
  LetterboxTransform,
  WorkerRequest,
  WorkerResponse,
} from "./types";

const worker = self as DedicatedWorkerGlobalScope;

let session: ort.InferenceSession | null = null;
let manifest: BrowserInferenceManifest | null = null;
let providerStrategy = "UNINITIALIZED";
let canvas: OffscreenCanvas | null = null;
let context: OffscreenCanvasRenderingContext2D | null = null;
let inputData: Float32Array | null = null;
let requestTail: Promise<void> = Promise.resolve();

ort.env.wasm.wasmPaths = "/ort/";
ort.env.wasm.proxy = false;
ort.env.wasm.numThreads = worker.crossOriginIsolated
  ? Math.min(4, Math.max(1, Math.floor((worker.navigator.hardwareConcurrency || 2) / 2)))
  : 1;

worker.onmessage = (event: MessageEvent<WorkerRequest>) => {
  const request = event.data;
  requestTail = requestTail.then(() => processRequest(request));
};

async function processRequest(request: WorkerRequest) {
  try {
    await handleRequest(request);
  } catch (error: unknown) {
    post({
      type: "error",
      requestId: request.requestId,
      message: error instanceof Error ? error.message : String(error),
    });
  }
}

async function handleRequest(request: WorkerRequest) {
  if (request.type === "init") {
    const payload = await initialize(request.manifest);
    post({ type: "ready", requestId: request.requestId, payload });
    return;
  }
  if (request.type === "infer") {
    const payload = await infer(request.bitmap, request.confidenceThreshold, request.iouThreshold);
    post({ type: "result", requestId: request.requestId, payload });
    return;
  }
  await releaseSession();
  manifest = null;
  canvas = null;
  context = null;
  inputData = null;
  post({ type: "disposed", requestId: request.requestId });
}

async function initialize(nextManifest: BrowserInferenceManifest) {
  if (session && manifest?.sha256 === nextManifest.sha256) {
    manifest = nextManifest;
    return {
      providerStrategy,
      modelSha256: nextManifest.sha256,
      initializationTimeMs: 0,
      warmupTimeMs: 0,
    };
  }

  await releaseSession();
  manifest = nextManifest;
  canvas = new OffscreenCanvas(nextManifest.inputWidth, nextManifest.inputHeight);
  context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) throw new Error("浏览器无法创建离屏画布");
  inputData = new Float32Array(3 * nextManifest.inputWidth * nextManifest.inputHeight);

  const response = await fetch(nextManifest.modelUrl, { credentials: "same-origin", cache: "force-cache" });
  if (!response.ok) throw new Error(`模型下载失败: HTTP ${response.status}`);
  const modelBytes = await response.arrayBuffer();
  const actualHash = await sha256(modelBytes);
  if (actualHash !== nextManifest.sha256.toLowerCase()) {
    throw new Error("模型 SHA-256 校验失败");
  }

  const initializationStartedAt = performance.now();
  if (await hasWebGpuAdapter()) {
    try {
      session = await ort.InferenceSession.create(modelBytes, {
        executionProviders: ["webgpu", "wasm"],
        graphOptimizationLevel: "all",
      });
      providerStrategy = "WebGPU/WASM";
    } catch {
      session = await createWasmSession(modelBytes);
      providerStrategy = "WASM";
    }
  } else {
    session = await createWasmSession(modelBytes);
    providerStrategy = "WASM";
  }
  const initializationTimeMs = Math.round(performance.now() - initializationStartedAt);

  const warmupStartedAt = performance.now();
  const warmupTensor = new ort.Tensor(
    "float32",
    new Float32Array(3 * nextManifest.inputWidth * nextManifest.inputHeight),
    [1, 3, nextManifest.inputHeight, nextManifest.inputWidth],
  );
  let warmupOutputs: Record<string, ort.Tensor> | undefined;
  try {
    warmupOutputs = await session.run({ [nextManifest.inputName]: warmupTensor });
  } finally {
    warmupTensor.dispose();
    disposeTensorMap(warmupOutputs);
  }
  const warmupTimeMs = Math.round(performance.now() - warmupStartedAt);

  return {
    providerStrategy,
    modelSha256: nextManifest.sha256,
    initializationTimeMs,
    warmupTimeMs,
  };
}

async function hasWebGpuAdapter() {
  const navigatorWithGpu = worker.navigator as WorkerNavigator & {
    gpu?: { requestAdapter: () => Promise<unknown | null> };
  };
  if (!navigatorWithGpu.gpu) return false;
  try {
    const adapter = await Promise.race([
      navigatorWithGpu.gpu.requestAdapter(),
      new Promise<null>((resolve) => worker.setTimeout(() => resolve(null), 1_500)),
    ]);
    return Boolean(adapter);
  } catch {
    return false;
  }
}

async function createWasmSession(modelBytes: ArrayBuffer) {
  return ort.InferenceSession.create(modelBytes, {
    executionProviders: ["wasm"],
    graphOptimizationLevel: "all",
  });
}

async function infer(
  bitmap: ImageBitmap,
  confidenceThreshold: number,
  iouThreshold: number,
): Promise<BrowserInferenceResult> {
  if (!session || !manifest || !canvas || !context || !inputData) {
    bitmap.close();
    throw new Error("浏览器推理模型尚未初始化");
  }

  const imageWidth = bitmap.width;
  const imageHeight = bitmap.height;
  const preprocessStartedAt = performance.now();
  let transform: LetterboxTransform;
  try {
    transform = prepareInput(bitmap, canvas, context, inputData, manifest.inputWidth, manifest.inputHeight);
  } finally {
    bitmap.close();
  }
  const preprocessTimeMs = Math.round(performance.now() - preprocessStartedAt);

  const inferenceStartedAt = performance.now();
  const tensor = new ort.Tensor("float32", inputData, [1, 3, manifest.inputHeight, manifest.inputWidth]);
  let outputs: Record<string, ort.Tensor> | undefined;
  try {
    outputs = await session.run({ [manifest.inputName]: tensor });
    const inferenceTimeMs = Math.round(performance.now() - inferenceStartedAt);
    const output = outputs[manifest.outputName];
    if (!output || !(output.data instanceof Float32Array)) {
      throw new Error(`模型输出 ${manifest.outputName} 不存在或不是 float32`);
    }

    const postprocessStartedAt = performance.now();
    const detections = postprocessDetectedOutput(
      output.data,
      output.dims,
      transform,
      manifest.classes,
      confidenceThreshold,
      iouThreshold,
    );
    const postprocessTimeMs = Math.round(performance.now() - postprocessStartedAt);
    return {
      imageWidth,
      imageHeight,
      preprocessTimeMs,
      inferenceTimeMs,
      postprocessTimeMs,
      providerStrategy,
      detections,
    };
  } finally {
    tensor.dispose();
    disposeTensorMap(outputs);
  }
}

async function releaseSession() {
  const currentSession = session;
  session = null;
  if (currentSession) await currentSession.release();
}

function disposeTensorMap(tensors: Record<string, ort.Tensor> | undefined) {
  if (!tensors) return;
  Object.values(tensors).forEach((tensor) => tensor.dispose());
}

function prepareInput(
  bitmap: ImageBitmap,
  targetCanvas: OffscreenCanvas,
  targetContext: OffscreenCanvasRenderingContext2D,
  targetData: Float32Array,
  modelWidth: number,
  modelHeight: number,
): LetterboxTransform {
  const scale = Math.min(modelWidth / bitmap.width, modelHeight / bitmap.height);
  const resizedWidth = Math.max(1, Math.round(bitmap.width * scale));
  const resizedHeight = Math.max(1, Math.round(bitmap.height * scale));
  const padX = Math.floor((modelWidth - resizedWidth) / 2);
  const padY = Math.floor((modelHeight - resizedHeight) / 2);

  targetContext.fillStyle = "rgb(114, 114, 114)";
  targetContext.fillRect(0, 0, targetCanvas.width, targetCanvas.height);
  targetContext.drawImage(bitmap, padX, padY, resizedWidth, resizedHeight);
  const rgba = targetContext.getImageData(0, 0, modelWidth, modelHeight).data;
  const planeSize = modelWidth * modelHeight;
  for (let index = 0, rgbaIndex = 0; index < planeSize; index += 1, rgbaIndex += 4) {
    targetData[index] = rgba[rgbaIndex] / 255;
    targetData[planeSize + index] = rgba[rgbaIndex + 1] / 255;
    targetData[2 * planeSize + index] = rgba[rgbaIndex + 2] / 255;
  }
  return {
    originalWidth: bitmap.width,
    originalHeight: bitmap.height,
    modelWidth,
    modelHeight,
    scale,
    padX,
    padY,
  };
}

async function sha256(bytes: ArrayBuffer) {
  const digest = await worker.crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
}

function post(message: WorkerResponse) {
  worker.postMessage(message);
}
