import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchInferenceManifest } from "@/api/inference";
import { BrowserInferenceClient } from "./browserInferenceClient";
import type { BrowserInferenceManifest, WorkerRequest, WorkerResponse } from "./types";

vi.mock("@/api/inference", () => ({
  fetchInferenceManifest: vi.fn(),
}));

const manifest: BrowserInferenceManifest = {
  modelName: "ruikang.onnx",
  modelVersion: "test",
  modelUrl: "/api/inference/model?sha256=test-sha",
  sha256: "test-sha",
  fileSize: 1,
  inputName: "images",
  outputName: "output0",
  inputWidth: 640,
  inputHeight: 640,
  classes: [],
  confidenceThreshold: 0.45,
  iouThreshold: 0.42,
  preferredExecutionProviders: ["webgpu", "wasm"],
};

class FakeWorker {
  static instances: FakeWorker[] = [];

  onmessage: ((event: MessageEvent<WorkerResponse>) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  onmessageerror: (() => void) | null = null;
  activeInferenceCount = 0;
  maxActiveInferenceCount = 0;
  lastInitManifest: BrowserInferenceManifest | null = null;

  constructor() {
    FakeWorker.instances.push(this);
  }

  postMessage(message: WorkerRequest) {
    if (message.type === "init") {
      this.lastInitManifest = message.manifest;
      queueMicrotask(() => this.respond({
        type: "ready",
        requestId: message.requestId,
        payload: {
          providerStrategy: "WebGPU/WASM",
          modelSha256: manifest.sha256,
          initializationTimeMs: 1,
          warmupTimeMs: 1,
        },
      }));
      return;
    }
    if (message.type === "dispose") {
      queueMicrotask(() => this.respond({ type: "disposed", requestId: message.requestId }));
      return;
    }

    this.activeInferenceCount += 1;
    this.maxActiveInferenceCount = Math.max(this.maxActiveInferenceCount, this.activeInferenceCount);
    setTimeout(() => {
      this.activeInferenceCount -= 1;
      this.respond({
        type: "result",
        requestId: message.requestId,
        payload: {
          imageWidth: 640,
          imageHeight: 640,
          preprocessTimeMs: 1,
          inferenceTimeMs: 5,
          postprocessTimeMs: 1,
          providerStrategy: "WebGPU/WASM",
          detections: [],
        },
      });
    }, 10);
  }

  terminate() {}

  private respond(response: WorkerResponse) {
    this.onmessage?.({ data: response } as MessageEvent<WorkerResponse>);
  }
}

describe("BrowserInferenceClient", () => {
  beforeEach(() => {
    FakeWorker.instances = [];
    vi.mocked(fetchInferenceManifest).mockResolvedValue(manifest);
    vi.stubGlobal("window", {
      setTimeout: globalThis.setTimeout.bind(globalThis),
      clearTimeout: globalThis.clearTimeout.bind(globalThis),
    });
    vi.stubGlobal("Worker", FakeWorker);
  });

  it("serializes concurrent inference requests for the shared ONNX session", async () => {
    const client = new BrowserInferenceClient();
    await client.ensureReady();

    await Promise.all([
      client.infer({} as ImageBitmap, 0.45, 0.42),
      client.infer({} as ImageBitmap, 0.45, 0.42),
    ]);

    expect(FakeWorker.instances).toHaveLength(1);
    expect(FakeWorker.instances[0].maxActiveInferenceCount).toBe(1);
    await client.dispose();
  });

  it("refreshes manifest metadata without recreating the model worker", async () => {
    const client = new BrowserInferenceClient();
    await client.ensureReady();
    const refreshedManifest = {
      ...manifest,
      classes: [
        { index: 0, defectCode: "oil_stain", defectName: "油渍", displayColor: "#facc15" },
      ],
    };
    vi.mocked(fetchInferenceManifest).mockResolvedValueOnce(refreshedManifest);

    const refreshed = await client.ensureReady(true);

    expect(FakeWorker.instances).toHaveLength(1);
    expect(FakeWorker.instances[0].lastInitManifest).toEqual(refreshedManifest);
    expect(refreshed.manifest).toEqual(refreshedManifest);
    await client.dispose();
  });
});
