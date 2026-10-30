import { describe, expect, it, vi } from "vitest";
import { selectInferenceSession } from "./sessionSelection";

function setup() {
  const gpu = { release: vi.fn().mockResolvedValue(undefined) };
  const cpu = { release: vi.fn().mockResolvedValue(undefined) };
  const factories = {
    canUseWebGpu: vi.fn().mockResolvedValue(true),
    createWebGpu: vi.fn().mockResolvedValue(gpu),
    createWasm: vi.fn().mockResolvedValue(cpu),
    warmup: vi.fn().mockResolvedValue(undefined),
  };
  return { gpu, cpu, factories };
}

describe("GPU session selection", () => {
  it("uses WebGPU only after GPU warmup succeeds", async () => {
    const { gpu, factories } = setup();
    const selected = await selectInferenceSession(factories);
    expect(selected.session).toBe(gpu);
    expect(selected.providerStrategy).toBe("WEBGPU");
    expect(factories.warmup).toHaveBeenCalledWith(gpu);
    expect(factories.createWasm).not.toHaveBeenCalled();
  });

  it("uses WASM when no GPU adapter is available", async () => {
    const { cpu, factories } = setup();
    factories.canUseWebGpu.mockResolvedValue(false);
    const selected = await selectInferenceSession(factories);
    expect(selected.session).toBe(cpu);
    expect(selected.providerStrategy).toBe("WASM");
    expect(factories.createWebGpu).not.toHaveBeenCalled();
    expect(factories.warmup).toHaveBeenCalledWith(cpu);
  });

  it("falls back if GPU session creation fails", async () => {
    const { factories } = setup();
    factories.createWebGpu.mockRejectedValue(new Error("unsupported GPU operator"));
    const selected = await selectInferenceSession(factories);
    expect(selected.providerStrategy).toBe("WASM");
    expect(selected.fallbackReason).toContain("unsupported GPU operator");
  });

  it("releases a GPU session whose warmup fails before trying CPU", async () => {
    const { gpu, cpu, factories } = setup();
    factories.warmup.mockImplementation(async (candidate) => {
      if (candidate === gpu) throw new Error("shader compilation failed");
      expect(gpu.release).toHaveBeenCalledOnce();
    });
    const selected = await selectInferenceSession(factories);
    expect(selected.session).toBe(cpu);
    expect(selected.providerStrategy).toBe("WASM");
    expect(selected.fallbackReason).toContain("shader compilation failed");
  });

  it("never reports ready when both providers fail warmup", async () => {
    const { gpu, cpu, factories } = setup();
    factories.warmup.mockRejectedValue(new Error("model execution failed"));
    await expect(selectInferenceSession(factories)).rejects.toThrow("model execution failed");
    expect(gpu.release).toHaveBeenCalledOnce();
    expect(cpu.release).toHaveBeenCalledOnce();
  });
});
