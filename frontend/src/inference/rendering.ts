import type { InferenceDetection } from "./types";

export async function renderDetectionImage(
  source: CanvasImageSource,
  width: number,
  height: number,
  detections: InferenceDetection[],
) {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("浏览器无法创建结果图画布");
  context.drawImage(source, 0, 0, width, height);
  drawDetections(context, detections, 1, 1);
  return canvasToBlob(canvas, "image/jpeg", 0.88);
}

export async function captureRealtimeEvidence(
  video: HTMLVideoElement,
  detections: InferenceDetection[],
) {
  if (!video.videoWidth || !video.videoHeight) {
    throw new Error("摄像头画面尚未就绪");
  }
  const sourceCanvas = document.createElement("canvas");
  sourceCanvas.width = video.videoWidth;
  sourceCanvas.height = video.videoHeight;
  const sourceContext = sourceCanvas.getContext("2d");
  if (!sourceContext) throw new Error("浏览器无法创建证据图画布");
  sourceContext.drawImage(video, 0, 0, sourceCanvas.width, sourceCanvas.height);

  const resultCanvas = document.createElement("canvas");
  resultCanvas.width = sourceCanvas.width;
  resultCanvas.height = sourceCanvas.height;
  const resultContext = resultCanvas.getContext("2d");
  if (!resultContext) throw new Error("浏览器无法创建结果图画布");
  resultContext.drawImage(sourceCanvas, 0, 0);
  drawDetections(resultContext, detections, 1, 1);

  const [sourceBlob, resultBlob] = await Promise.all([
    canvasToBlob(sourceCanvas, "image/jpeg", 0.82),
    canvasToBlob(resultCanvas, "image/jpeg", 0.86),
  ]);
  return { sourceBlob, resultBlob };
}

export function drawDetections(
  context: CanvasRenderingContext2D,
  detections: InferenceDetection[],
  scaleX: number,
  scaleY: number,
) {
  const fontSize = Math.max(14, Math.round(Math.min(context.canvas.width, context.canvas.height) / 45));
  context.lineWidth = Math.max(2, Math.round(fontSize / 7));
  context.font = `${fontSize}px Microsoft YaHei, sans-serif`;
  context.textBaseline = "top";

  for (const detection of detections) {
    const x = detection.x1 * scaleX;
    const y = detection.y1 * scaleY;
    const width = detection.width * scaleX;
    const height = detection.height * scaleY;
    const color = detection.displayColor || "#00d4ff";
    context.strokeStyle = color;
    context.fillStyle = color;
    context.strokeRect(x, y, width, height);

    const label = `${detection.defectName} ${detection.confidence.toFixed(3)}`;
    const labelHeight = fontSize + 10;
    const labelWidth = context.measureText(label).width + 14;
    const labelY = Math.max(0, y - labelHeight);
    context.fillRect(x, labelY, labelWidth, labelHeight);
    context.fillStyle = "#ffffff";
    context.fillText(label, x + 7, labelY + 5);
  }
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number) {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error("浏览器生成结果图失败"));
    }, type, quality);
  });
}
