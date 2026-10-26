import { describe, expect, it } from "vitest";
import {
  calculateExpertAccuracy,
  calculateExpertScore,
  isExpertCorrect,
  isExpertReviewed,
  isExpertWrong,
  resolveExpertReviewStatus,
} from "./expertEvaluation";

describe("expert evaluation", () => {
  it("maps expert correct and wrong decisions to review statuses", () => {
    expect(resolveExpertReviewStatus("PASS", true)).toBe("PASS");
    expect(resolveExpertReviewStatus("FAIL", true)).toBe("REJECTED");
    expect(resolveExpertReviewStatus("PASS", false)).toBe("MISSED");
    expect(resolveExpertReviewStatus("FAIL", false)).toBe("FALSE_ALARM");
  });

  it("classifies reviewed results", () => {
    expect(isExpertCorrect("REJECTED")).toBe(true);
    expect(isExpertWrong("MISSED")).toBe(true);
    expect(isExpertReviewed("FALSE_ALARM")).toBe(true);
    expect(isExpertReviewed("PENDING")).toBe(false);
  });

  it("calculates the 35-point score from 20 samples", () => {
    expect(calculateExpertScore(20)).toBe(35);
    expect(calculateExpertScore(12)).toBe(21);
    expect(calculateExpertAccuracy(15, 20)).toBe(75);
  });
});
