import type {
  BrowserModelClass,
  InferenceDetection,
  LetterboxTransform,
} from "./types";

export function postprocessDetectedOutput(
  data: Float32Array,
  dims: readonly number[],
  transform: LetterboxTransform,
  classes: BrowserModelClass[],
  confidenceThreshold: number,
  iouThreshold: number,
): InferenceDetection[] {
  if (dims.length !== 3 || dims[0] !== 1) {
    throw new Error(`模型输出形状不受支持: [${dims.join(", ")}]`);
  }

  const transposed = dims[1] === 6;
  const rowCount = transposed ? dims[2] : dims[1];
  const columnCount = transposed ? dims[1] : dims[2];
  if (columnCount !== 6 || data.length < rowCount * columnCount) {
    throw new Error(`模型输出不是 [1, N, 6] 或 [1, 6, N]: [${dims.join(", ")}]`);
  }

  const classMap = new Map(classes.map((item) => [item.index, item]));
  const normalizedCoordinates = maxCoordinate(data, rowCount, transposed) <= 2;
  const candidates: InferenceDetection[] = [];

  for (let row = 0; row < rowCount; row += 1) {
    const confidence = valueAt(data, row, 4, rowCount, transposed);
    if (!Number.isFinite(confidence) || confidence < confidenceThreshold || confidence > 1) continue;

    const classIndex = Math.round(valueAt(data, row, 5, rowCount, transposed));
    const modelClass = classMap.get(classIndex);
    if (!modelClass) continue;

    const coordinateScaleX = normalizedCoordinates ? transform.modelWidth : 1;
    const coordinateScaleY = normalizedCoordinates ? transform.modelHeight : 1;
    const rawX1 = valueAt(data, row, 0, rowCount, transposed) * coordinateScaleX;
    const rawY1 = valueAt(data, row, 1, rowCount, transposed) * coordinateScaleY;
    const rawX2 = valueAt(data, row, 2, rowCount, transposed) * coordinateScaleX;
    const rawY2 = valueAt(data, row, 3, rowCount, transposed) * coordinateScaleY;

    const x1 = clamp(Math.round((rawX1 - transform.padX) / transform.scale), 0, transform.originalWidth);
    const y1 = clamp(Math.round((rawY1 - transform.padY) / transform.scale), 0, transform.originalHeight);
    const x2 = clamp(Math.round((rawX2 - transform.padX) / transform.scale), 0, transform.originalWidth);
    const y2 = clamp(Math.round((rawY2 - transform.padY) / transform.scale), 0, transform.originalHeight);
    if (x2 <= x1 || y2 <= y1) continue;

    const width = x2 - x1;
    const height = y2 - y1;
    candidates.push({
      defectCode: modelClass.defectCode,
      defectName: modelClass.defectName,
      displayColor: modelClass.displayColor,
      confidence,
      x1,
      y1,
      x2,
      y2,
      width,
      height,
      area: width * height,
    });
  }

  return applyClassAwareNms(candidates, iouThreshold);
}

export function applyClassAwareNms(
  detections: InferenceDetection[],
  iouThreshold: number,
): InferenceDetection[] {
  const sorted = [...detections].sort((a, b) => b.confidence - a.confidence);
  const selected: InferenceDetection[] = [];
  for (const candidate of sorted) {
    const overlaps = selected.some((existing) =>
      existing.defectCode === candidate.defectCode && intersectionOverUnion(existing, candidate) > iouThreshold,
    );
    if (!overlaps) selected.push(candidate);
  }
  return selected;
}

function valueAt(
  data: Float32Array,
  row: number,
  column: number,
  rowCount: number,
  transposed: boolean,
) {
  return transposed ? data[column * rowCount + row] : data[row * 6 + column];
}

function maxCoordinate(data: Float32Array, rowCount: number, transposed: boolean) {
  let max = 0;
  for (let row = 0; row < Math.min(rowCount, 300); row += 1) {
    for (let column = 0; column < 4; column += 1) {
      max = Math.max(max, Math.abs(valueAt(data, row, column, rowCount, transposed)));
    }
  }
  return max;
}

function intersectionOverUnion(a: InferenceDetection, b: InferenceDetection) {
  const x1 = Math.max(a.x1, b.x1);
  const y1 = Math.max(a.y1, b.y1);
  const x2 = Math.min(a.x2, b.x2);
  const y2 = Math.min(a.y2, b.y2);
  const intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  if (intersection <= 0) return 0;
  return intersection / (a.area + b.area - intersection);
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
