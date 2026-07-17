import { fetchInferenceManifest } from "@/api/inference";
import type {
  BrowserInferenceManifest,
  BrowserInferenceResult,
  InferenceReady,
  WorkerRequest,
  WorkerResponse,
} from "./types";

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timeout: number;
};

export class BrowserInferenceClient {
  private worker: Worker | null = null;
  private manifest: BrowserInferenceManifest | null = null;
  private ready: InferenceReady | null = null;
  private requestSequence = 0;
  private pending = new Map<string, PendingRequest>();
  private operationTail: Promise<void> = Promise.resolve();

  async ensureReady(refreshManifest = false) {
    return this.enqueueOperation(() => this.ensureReadyInternal(refreshManifest));
  }

  async infer(bitmap: ImageBitmap, confidenceThreshold: number, iouThreshold: number) {
    return this.enqueueOperation(async () => {
      if (!this.ready) await this.ensureReadyInternal();
      return this.request<BrowserInferenceResult>(
        {
          type: "infer",
          requestId: this.nextRequestId(),
          bitmap,
          confidenceThreshold,
          iouThreshold,
        },
        30_000,
        [bitmap],
      );
    });
  }

  async dispose() {
    return this.enqueueOperation(async () => {
      if (!this.worker) return;
      try {
        await this.request<void>({ type: "dispose", requestId: this.nextRequestId() }, 5_000);
      } finally {
        this.resetWorker(new Error("浏览器推理 Worker 已关闭"));
      }
    });
  }

  private async ensureReadyInternal(refreshManifest = false) {
    if (!refreshManifest && this.ready && this.manifest) {
      return { manifest: this.manifest, ready: this.ready };
    }
    const nextManifest = await fetchInferenceManifest();
    if (this.ready && this.manifest?.sha256 === nextManifest.sha256) {
      this.manifest = nextManifest;
      if (refreshManifest) {
        this.ready = await this.request<InferenceReady>(
          { type: "init", requestId: this.nextRequestId(), manifest: nextManifest },
          30_000,
        );
      }
      return { manifest: nextManifest, ready: this.ready };
    }
    this.manifest = nextManifest;
    const ready = await this.request<InferenceReady>(
      { type: "init", requestId: this.nextRequestId(), manifest: nextManifest },
      180_000,
    );
    this.ready = ready;
    return { manifest: nextManifest, ready };
  }

  private enqueueOperation<T>(operation: () => Promise<T>) {
    const result = this.operationTail.then(operation);
    this.operationTail = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private request<T>(message: WorkerRequest, timeoutMs: number, transfer: Transferable[] = []) {
    const worker = this.ensureWorker();
    return new Promise<T>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.resetWorker(new Error("浏览器推理响应超时，已自动重置推理引擎"));
      }, timeoutMs);
      this.pending.set(message.requestId, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      });
      try {
        worker.postMessage(message, transfer);
      } catch (error) {
        window.clearTimeout(timeout);
        this.pending.delete(message.requestId);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  private ensureWorker() {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL("./inference.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (event: MessageEvent<WorkerResponse>) => this.handleResponse(event.data);
    worker.onerror = (event) => this.resetWorker(new Error(event.message || "浏览器推理 Worker 异常"));
    worker.onmessageerror = () => this.resetWorker(new Error("浏览器推理 Worker 消息解析失败"));
    this.worker = worker;
    return worker;
  }

  private handleResponse(response: WorkerResponse) {
    const pending = this.pending.get(response.requestId);
    if (!pending) return;
    window.clearTimeout(pending.timeout);
    this.pending.delete(response.requestId);
    if (response.type === "error") {
      pending.reject(new Error(response.message));
      return;
    }
    if (response.type === "ready" || response.type === "result") {
      pending.resolve(response.payload);
      return;
    }
    pending.resolve(undefined);
  }

  private rejectPending(error: Error) {
    for (const pending of this.pending.values()) {
      window.clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
  }

  private resetWorker(error: Error) {
    const currentWorker = this.worker;
    this.worker = null;
    this.manifest = null;
    this.ready = null;
    currentWorker?.terminate();
    this.rejectPending(error);
  }

  private nextRequestId() {
    this.requestSequence += 1;
    return `inference-${Date.now()}-${this.requestSequence}`;
  }
}

export const browserInferenceClient = new BrowserInferenceClient();
