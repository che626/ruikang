import { describe, expect, it } from "vitest";
import { postprocessDetectedOutput } from "./postprocess";
import type { BrowserModelClass, LetterboxTransform } from "./types";

const classes: BrowserModelClass[] = [
  { index: 0, defectCode: "oil_stain", defectName: "油渍", displayColor: "#facc15" },
  { index: 1, defectCode: "hole", defectName: "破洞", displayColor: "#fb923c" },
];

describe("postprocessDetectedOutput", () => {
  it("restores letterboxed coordinates and applies class-aware NMS", () => {
    const transform: LetterboxTransform = {
      originalWidth: 1280,
      originalHeight: 720,
      modelWidth: 640,
      modelHeight: 640,
      scale: 0.5,
      padX: 0,
      padY: 140,
    };
    const output = new Float32Array([
      100, 190, 200, 290, 0.9, 0,
      105, 195, 198, 288, 0.8, 0,
      100, 190, 200, 290, 0.7, 1,
      300, 300, 320, 320, 0.2, 0,
    ]);

    const result = postprocessDetectedOutput(
      output,
      [1, 4, 6],
      transform,
      classes,
      0.45,
      0.5,
    );

    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({
      defectCode: "oil_stain",
      confidence: expect.closeTo(0.9, 5),
      x1: 200,
      y1: 100,
      x2: 400,
      y2: 300,
      width: 200,
      height: 200,
      area: 40_000,
    });
    expect(result[1].defectCode).toBe("hole");
  });

  it("supports transposed normalized [1, 6, N] output", () => {
    const transform: LetterboxTransform = {
      originalWidth: 640,
      originalHeight: 640,
      modelWidth: 640,
      modelHeight: 640,
      scale: 1,
      padX: 0,
      padY: 0,
    };
    const result = postprocessDetectedOutput(
      new Float32Array([0.1, 0.2, 0.3, 0.4, 0.95, 0]),
      [1, 6, 1],
      transform,
      classes,
      0.45,
      0.5,
    );

    expect(result).toEqual([
      expect.objectContaining({
        defectCode: "oil_stain",
        x1: 64,
        y1: 128,
        x2: 192,
        y2: 256,
      }),
    ]);
  });

  it("rejects an unsupported output shape", () => {
    expect(() =>
      postprocessDetectedOutput(
        new Float32Array(10),
        [1, 10],
        {
          originalWidth: 640,
          originalHeight: 640,
          modelWidth: 640,
          modelHeight: 640,
          scale: 1,
          padX: 0,
          padY: 0,
        },
        classes,
        0.45,
        0.5,
      ),
    ).toThrow("模型输出形状不受支持");
  });
});
