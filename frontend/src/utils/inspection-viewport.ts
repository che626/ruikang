export interface InspectionBox {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface ViewportTransform {
  scale: number;
  x: number;
  y: number;
}

export interface ViewportLabel {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function fitInspectionViewport(
  imageWidth: number,
  imageHeight: number,
  viewportWidth: number,
  viewportHeight: number,
  boxes: readonly InspectionBox[],
  focus: boolean,
): ViewportTransform {
  const validBoxes = boxes.filter((box) =>
    [box.x1, box.y1, box.x2, box.y2].every(Number.isFinite) && box.x2 > box.x1 && box.y2 > box.y1
      && box.x1 < imageWidth && box.y1 < imageHeight && box.x2 > 0 && box.y2 > 0,
  );
  if (!focus || !validBoxes.length) {
    const scale = Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight);
    return { scale, x: (viewportWidth - imageWidth * scale) / 2, y: (viewportHeight - imageHeight * scale) / 2 };
  }

  const left = Math.max(0, Math.min(...validBoxes.map((box) => box.x1)));
  const top = Math.max(0, Math.min(...validBoxes.map((box) => box.y1)));
  const right = Math.min(imageWidth, Math.max(...validBoxes.map((box) => box.x2)));
  const bottom = Math.min(imageHeight, Math.max(...validBoxes.map((box) => box.y2)));
  const boxWidth = Math.max(1, right - left);
  const boxHeight = Math.max(1, bottom - top);
  // Keep surrounding fabric for context, plus screen space for readable labels.
  const contextX = Math.max(imageWidth * 0.015, Math.min(boxWidth * 0.15, imageWidth * 0.04), 12);
  const contextY = Math.max(imageHeight * 0.015, Math.min(boxHeight * 0.15, imageHeight * 0.04), 12);
  const margin = Math.min(16, viewportWidth * 0.06, viewportHeight * 0.06);
  const scale = Math.min(
    Math.max(1, viewportWidth - margin * 2) / (boxWidth + contextX * 2),
    Math.max(1, viewportHeight - margin * 2 - 24) / (boxHeight + contextY * 2),
    3,
  );
  return {
    scale,
    x: viewportWidth / 2 - (left + right) / 2 * scale,
    y: viewportHeight / 2 - (top + bottom) / 2 * scale,
  };
}

export function placeInspectionLabels(
  boxes: readonly InspectionBox[],
  labelWidths: readonly number[],
  viewportWidth: number,
  viewportHeight: number,
): ViewportLabel[] {
  const edge = 6;
  const height = Math.min(24, viewportHeight - edge * 2);
  const gap = 4;
  const placed: ViewportLabel[] = [];
  const overlaps = (label: ViewportLabel) => placed.some((other) =>
    label.x < other.x + other.width + gap && label.x + label.width + gap > other.x
      && label.y < other.y + other.height + gap && label.y + label.height + gap > other.y,
  );
  const coversBox = (label: ViewportLabel) => boxes.some((box) =>
    label.x < box.x2 && label.x + label.width > box.x1 && label.y < box.y2 && label.y + label.height > box.y1,
  );

  boxes.forEach((box, index) => {
    const width = Math.min(labelWidths[index] ?? 100, viewportWidth - edge * 2);
    const clamp = (x: number, y: number): ViewportLabel => ({
      x: Math.max(edge, Math.min(x, viewportWidth - width - edge)),
      y: Math.max(edge, Math.min(y, viewportHeight - height - edge)),
      width,
      height,
    });
    const candidates = [
      clamp(box.x1, box.y1 - height - gap),
      clamp(box.x2 - width, box.y1 - height - gap),
      clamp(box.x1, box.y2 + gap),
      clamp(box.x2 - width, box.y2 + gap),
      clamp(box.x2 + gap, box.y1),
      clamp(box.x1 - width - gap, box.y1),
    ];
    let label = candidates.find((candidate) => !overlaps(candidate) && !coversBox(candidate));
    if (!label) {
      // Spread clustered labels into free rows; the viewer draws a connector.
      const free: ViewportLabel[] = [];
      for (let y = edge; y <= viewportHeight - height - edge; y += height + gap) {
        for (let x = edge; x <= viewportWidth - width - edge; x += Math.max(24, Math.round(width / 2))) {
          const candidate = clamp(x, y);
          if (!overlaps(candidate)) free.push(candidate);
        }
      }
      label = free.sort((a, b) => Number(coversBox(a)) - Number(coversBox(b))
        || Math.hypot(a.x - box.x1, a.y - box.y1) - Math.hypot(b.x - box.x1, b.y - box.y1))[0];
    }
    placed.push(label ?? candidates.find((candidate) => !overlaps(candidate)) ?? candidates[0]);
  });
  const crowded = placed.some((label, index) => placed.slice(0, index).some((other) =>
    label.x < other.x + other.width && label.x + label.width > other.x
      && label.y < other.y + other.height && label.y + label.height > other.y,
  ));
  if (!crowded) return placed;
  // A dense set of defects needs a compact label grid instead of hiding labels
  // underneath one another. Connector lines still identify the matching boxes.
  const columns = Math.max(1, Math.ceil(Math.sqrt(boxes.length * viewportWidth / viewportHeight)));
  const rows = Math.ceil(boxes.length / columns);
  const cellWidth = (viewportWidth - edge * 2 - gap * (columns - 1)) / columns;
  const cellHeight = (viewportHeight - edge * 2 - gap * (rows - 1)) / rows;
  return boxes.map((_, index) => ({
    x: edge + (index % columns) * (cellWidth + gap),
    y: edge + Math.floor(index / columns) * (cellHeight + gap),
    width: Math.min(labelWidths[index] ?? 100, cellWidth),
    height: Math.min(height, cellHeight),
  }));
}
