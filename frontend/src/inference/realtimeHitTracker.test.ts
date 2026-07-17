import { describe, expect, it } from "vitest";
import { RealtimeHitTracker } from "./realtimeHitTracker";

describe("RealtimeHitTracker", () => {
  it("persists once when a defect reaches the configured streak", () => {
    const tracker = new RealtimeHitTracker();

    expect(tracker.consume(true, 3)).toEqual({ hitStreak: 1, shouldPersist: false });
    expect(tracker.consume(true, 3)).toEqual({ hitStreak: 2, shouldPersist: false });
    expect(tracker.consume(true, 3)).toEqual({ hitStreak: 3, shouldPersist: true });
    expect(tracker.consume(true, 3)).toEqual({ hitStreak: 4, shouldPersist: false });
  });

  it("starts a new event streak after a normal frame", () => {
    const tracker = new RealtimeHitTracker();
    tracker.consume(true, 1);

    expect(tracker.consume(false, 1)).toEqual({ hitStreak: 0, shouldPersist: false });
    expect(tracker.consume(true, 1)).toEqual({ hitStreak: 1, shouldPersist: true });
  });

  it("treats a non-positive threshold as one frame", () => {
    const tracker = new RealtimeHitTracker();
    expect(tracker.consume(true, 0).shouldPersist).toBe(true);
  });
});
