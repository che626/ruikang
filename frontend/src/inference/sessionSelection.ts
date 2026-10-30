type ReleasableSession = { release(): Promise<void> };

type SessionFactories<T extends ReleasableSession> = {
  canUseWebGpu(): Promise<boolean>;
  createWebGpu(): Promise<T>;
  createWasm(): Promise<T>;
  warmup(session: T): Promise<void>;
};

/** Only report an execution provider after its session has completed a real warmup. */
export async function selectInferenceSession<T extends ReleasableSession>(factories: SessionFactories<T>) {
  async function createAndWarmup(create: () => Promise<T>) {
    let candidate: T | undefined;
    try {
      candidate = await create();
      await factories.warmup(candidate);
      return candidate;
    } catch (error) {
      try { await candidate?.release(); } catch { /* Preserve the initialization error. */ }
      throw error;
    }
  }

  let fallbackReason = "浏览器未提供可用的 WebGPU 适配器";
  try {
    if (await factories.canUseWebGpu()) {
      return {
        session: await createAndWarmup(factories.createWebGpu),
        providerStrategy: "WEBGPU" as const,
        fallbackReason: undefined,
      };
    }
  } catch (error) {
    fallbackReason = error instanceof Error ? error.message : String(error);
  }

  return {
    session: await createAndWarmup(factories.createWasm),
    providerStrategy: "WASM" as const,
    fallbackReason,
  };
}
