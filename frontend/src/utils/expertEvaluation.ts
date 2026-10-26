export type ExpertReviewStatus = "PASS" | "REJECTED" | "MISSED" | "FALSE_ALARM";

export const EXPERT_SAMPLE_SIZE = 20;
export const SCORE_PER_CORRECT_SAMPLE = 1.75;

export function isExpertCorrect(status: string) {
  return ["CONFIRMED", "PASS", "REJECTED"].includes(status);
}

export function isExpertWrong(status: string) {
  return ["MISSED", "FALSE_ALARM"].includes(status);
}

export function isExpertReviewed(status: string) {
  return isExpertCorrect(status) || isExpertWrong(status);
}

export function resolveExpertReviewStatus(
  resultJudge: string,
  correct: boolean,
): ExpertReviewStatus {
  const machinePassed = resultJudge === "PASS";
  if (correct) return machinePassed ? "PASS" : "REJECTED";
  return machinePassed ? "MISSED" : "FALSE_ALARM";
}

export function calculateExpertScore(correctCount: number) {
  return Math.min(EXPERT_SAMPLE_SIZE, Math.max(0, correctCount)) * SCORE_PER_CORRECT_SAMPLE;
}

export function calculateExpertAccuracy(correctCount: number, reviewedCount: number) {
  if (reviewedCount <= 0) return 0;
  return (Math.max(0, correctCount) / reviewedCount) * 100;
}
