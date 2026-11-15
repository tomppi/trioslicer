//! Photo keyframes for the photogrammetry pass (dense mesh + texture on top of
//! the laser scan): when to take a still, and the board pose at the exact
//! exposure the still came from.
//!
//! The engine records every analysis frame in a [`PoseTrack`] keyed by its
//! sensor timestamp. A still is captured in the same request as an analysis
//! frame, so it carries that frame's timestamp: its pose is the frame's own
//! pose when the processing thread saw the frame, else the pose interpolated
//! between the neighbours (a frame the thread skipped while busy).

use std::collections::VecDeque;

use serde::{Deserialize, Serialize};

use crate::geom::{Pose, norm, rotation_distance, sub};

#[derive(Deserialize, Serialize, Clone, Copy, Debug, PartialEq)]
#[serde(default)]
pub struct KeyframeCfg {
    /// A keyframe every this many seconds; 0 = off.
    pub interval_s: f64,
    /// A keyframe once the camera has moved this far from the last one (mm);
    /// 0 = off.
    pub move_mm: f64,
    /// … or turned this far (degrees); 0 = off.
    pub turn_deg: f64,
    /// A due keyframe waits while the camera moves faster than this (mm/s) …
    pub max_speed_mm_s: f64,
    /// … or turns faster than this (deg/s) — motion blur in the still …
    pub max_turn_deg_s: f64,
    /// … or while the board pose fits worse than this (reprojection RMS, px) …
    pub max_reproj_px: f64,
    /// … or rests on fewer ChArUco corners than this: a one-camera planar pose
    /// from a handful of corners is weak (orbiter-native's `MIN_POSE_CORNERS`).
    pub min_corners: usize,
}

impl Default for KeyframeCfg {
    fn default() -> Self {
        KeyframeCfg {
            interval_s: 5.0,
            move_mm: 50.0,
            turn_deg: 10.0,
            max_speed_mm_s: 40.0,
            max_turn_deg_s: 8.0,
            max_reproj_px: 1.5,
            min_corners: 12,
        }
    }
}

/// One analysis frame that had a board pose, and what else the photograph
/// taken with it will want to say about it.
#[derive(Clone, Copy, Debug)]
pub struct TrackedPose {
    pub ts_ns: i64,
    pub pose: Pose,
    pub reproj_rms: f64,
    pub corners: usize,
    /// Runner-up IPPE branch's error over the chosen one's (≫1 unambiguous,
    /// ≈1 near-frontal); NaN when IPPE gave no alternative.
    pub margin: f64,
    /// Laser stripe pixels found in the frame (0 = no stripe in view).
    pub stripe_px: usize,
    /// Stripe points that landed inside the working volume.
    pub kept: usize,
    /// Laplacian variance over the board — a motion-blur / focus proxy,
    /// comparable within one analysis size.
    pub sharpness: f64,
}

/// The pose at a still's timestamp.
#[derive(Clone, Copy, Debug)]
pub struct PoseAt {
    pub pose: Pose,
    /// The frame's own numbers; for an interpolated pose the worse of the
    /// two frames' (any stripe in either counts).
    pub frame: TrackedPose,
    /// 0 for the frame's own pose; else the gap between the two frames it was
    /// interpolated from (ns).
    pub interpolated_gap_ns: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Miss {
    /// The timestamp is newer than every frame processed so far: ask again
    /// after the next frame.
    NotYet,
    /// No board pose at (or close enough around) the timestamp.
    NoPose,
    /// Older than every frame still kept: it came back too late.
    Evicted,
}

/// The last couple of seconds of analysis frames, newest last. Frames without
/// a board are kept too, so a pose is never interpolated across a gap where
/// the board was lost.
pub struct PoseTrack {
    frames: VecDeque<(i64, Option<TrackedPose>)>,
    cap: usize,
}

impl PoseTrack {
    /// Exact-match tolerance: one timestamp may be rounded differently by
    /// the two outputs of a request.
    const EXACT_NS: i64 = 1_000_000;

    pub fn new(cap: usize) -> Self {
        PoseTrack { frames: VecDeque::with_capacity(cap), cap }
    }

    pub fn clear(&mut self) {
        self.frames.clear();
    }

    pub fn push(&mut self, ts_ns: i64, pose: Option<TrackedPose>) {
        if self.frames.back().is_some_and(|&(t, _)| ts_ns <= t) {
            // a reopened camera restarts its clock
            self.frames.clear();
        }
        if self.frames.len() == self.cap {
            self.frames.pop_front();
        }
        self.frames.push_back((ts_ns, pose));
    }

    pub fn newest_ts(&self) -> Option<i64> {
        self.frames.back().map(|f| f.0)
    }

    pub fn latest(&self) -> Option<&TrackedPose> {
        self.frames.back().and_then(|f| f.1.as_ref())
    }

    /// The pose at `ts_ns`: the frame's own, or interpolated between the
    /// processed frames around it when both had a pose and are at most
    /// `max_gap_ns` apart.
    pub fn at(&self, ts_ns: i64, max_gap_ns: i64) -> Result<PoseAt, Miss> {
        let newest = self.newest_ts().ok_or(Miss::NotYet)?;
        if ts_ns > newest + Self::EXACT_NS {
            return Err(Miss::NotYet);
        }
        if self.frames.front().is_some_and(|f| ts_ns < f.0 - Self::EXACT_NS) {
            return Err(Miss::Evicted);
        }
        let i = self.frames.partition_point(|f| f.0 < ts_ns - Self::EXACT_NS);
        if let Some(&(t, p)) = self.frames.get(i)
            && (t - ts_ns).abs() <= Self::EXACT_NS
        {
            let p = p.ok_or(Miss::NoPose)?;
            return Ok(PoseAt { pose: p.pose, frame: p, interpolated_gap_ns: 0 });
        }
        // frames i-1 and i bracket the timestamp
        let (Some(a), Some(b)) = (i.checked_sub(1).and_then(|j| self.frames.get(j)), self.frames.get(i)) else {
            return Err(Miss::NoPose);
        };
        let (Some(pa), Some(pb)) = (a.1, b.1) else { return Err(Miss::NoPose) };
        let gap = b.0 - a.0;
        if gap > max_gap_ns {
            return Err(Miss::NoPose);
        }
        let s = (ts_ns - a.0) as f64 / gap as f64;
        let pose = Pose::lerp(&pa.pose, &pb.pose, s);
        let frame = TrackedPose {
            ts_ns,
            pose,
            reproj_rms: pa.reproj_rms.max(pb.reproj_rms),
            corners: pa.corners.min(pb.corners),
            margin: pa.margin.min(pb.margin),
            stripe_px: pa.stripe_px.max(pb.stripe_px),
            kept: pa.kept.max(pb.kept),
            sharpness: pa.sharpness.min(pb.sharpness),
        };
        Ok(PoseAt { pose, frame, interpolated_gap_ns: gap })
    }

    /// Camera speed (mm/s, deg/s) between the newest frame and the frame
    /// about `window_ns` before it — a window, not the last frame pair, so
    /// the per-frame pose jitter does not read as motion. `None` until the
    /// board has been seen through the whole window.
    pub fn speed(&self, window_ns: i64) -> Option<(f64, f64)> {
        let b = self.latest()?;
        let since = b.ts_ns - window_ns;
        // the oldest posed frame inside the window, with no board loss in between
        let mut a = None;
        for (t, p) in self.frames.iter().rev() {
            match p {
                Some(p) if *t >= since => a = Some(*p),
                Some(p) => {
                    a = Some(*p);
                    break;
                }
                None => return None,
            }
        }
        let a = a?;
        let dt = (b.ts_ns - a.ts_ns) as f64 * 1e-9;
        if dt < 0.5 * window_ns as f64 * 1e-9 {
            return None;
        }
        let d = norm(sub(b.pose.camera_position(), a.pose.camera_position()));
        let ang = rotation_distance(&a.pose.r, &b.pose.r).to_degrees();
        Some((d / dt, ang / dt))
    }
}

/// Why a due keyframe is waiting.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Hold {
    NoBoard,
    PoseRms,
    FewCorners,
    Settling,
    Moving,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Due {
    No,
    Hold(Hold),
    Now,
}

/// Decides when the next keyframe is due: the first one at once, then on the
/// interval or the movement since the last one, whichever comes first — and
/// only while the camera is steady and the pose is good.
#[derive(Default)]
pub struct Trigger {
    last: Option<(i64, Pose)>,
    /// The frame a still was requested at (or a failure backs off from).
    pending_since: Option<i64>,
    forced: bool,
    failures: u32,
}

impl Trigger {
    /// A requested still that never came back (capture failed) stops
    /// blocking the next one after this long; a failed one waits as long
    /// before the next try.
    pub const PENDING_TIMEOUT_NS: i64 = 3_000_000_000;
    /// Motion is measured over this window.
    pub const SPEED_WINDOW_NS: i64 = 200_000_000;

    pub fn reset(&mut self) {
        *self = Trigger::default();
    }

    /// Take the next keyframe as soon as the camera is steady, regardless of
    /// the interval and movement.
    pub fn force(&mut self) {
        self.forced = true;
    }

    pub fn pending(&self) -> bool {
        self.pending_since.is_some()
    }

    /// Forget a requested still: it will not come back (the camera closed),
    /// and a restarted sensor clock would hold the next one forever.
    pub fn clear_pending(&mut self) {
        self.pending_since = None;
    }

    /// Stills that failed in a row since the last one that was taken.
    pub fn failures(&self) -> u32 {
        self.failures
    }

    /// Evaluate the newest frame of `track`; `Now` marks a still as requested.
    pub fn check(&mut self, cfg: &KeyframeCfg, track: &PoseTrack) -> Due {
        let Some(ts) = track.newest_ts() else { return Due::No };
        if let Some(t) = self.pending_since {
            if ts - t < Self::PENDING_TIMEOUT_NS {
                return Due::No;
            }
            self.pending_since = None;
        }
        let cur = track.latest();
        let due = self.forced
            || match self.last {
                None => true,
                Some((t, last)) => {
                    (cfg.interval_s > 0.0 && (ts - t) as f64 * 1e-9 >= cfg.interval_s)
                        || cur.is_some_and(|c| {
                            let moved = norm(sub(c.pose.camera_position(), last.camera_position()));
                            let turned = rotation_distance(&last.r, &c.pose.r).to_degrees();
                            (cfg.move_mm > 0.0 && moved >= cfg.move_mm) || (cfg.turn_deg > 0.0 && turned >= cfg.turn_deg)
                        })
                }
            };
        if !due {
            return Due::No;
        }
        let Some(cur) = cur else { return Due::Hold(Hold::NoBoard) };
        if cur.reproj_rms > cfg.max_reproj_px {
            return Due::Hold(Hold::PoseRms);
        }
        if cur.corners < cfg.min_corners {
            return Due::Hold(Hold::FewCorners);
        }
        let Some((v, w)) = track.speed(Self::SPEED_WINDOW_NS) else { return Due::Hold(Hold::Settling) };
        if v > cfg.max_speed_mm_s || w > cfg.max_turn_deg_s {
            return Due::Hold(Hold::Moving);
        }
        self.pending_since = Some(ts);
        self.forced = false;
        Due::Now
    }

    /// A still was posed: the next one counts from it. Only a still exposed
    /// after the outstanding request answers it — a late one from a request
    /// that already timed out does not.
    pub fn taken(&mut self, ts_ns: i64, pose: Pose) {
        if self.last.is_none_or(|(t, _)| ts_ns > t) {
            self.last = Some((ts_ns, pose));
        }
        if self.pending_since.is_some_and(|t| ts_ns >= t) {
            self.pending_since = None;
        }
        self.failures = 0;
    }

    /// The still could not be taken or used. The next try waits
    /// [`Self::PENDING_TIMEOUT_NS`] from `now_ns`, so a failure that repeats
    /// does not turn into a capture loop.
    pub fn failed(&mut self, now_ns: i64) {
        self.pending_since = Some(now_ns);
        self.failures += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::{rodrigues, scale};

    const FRAME: i64 = 33_333_333;

    /// A camera at board-frame centre `c` looking down -Z, turned `yaw` rad.
    fn pose_at(c: [f64; 3], yaw: f64) -> Pose {
        let r = rodrigues([0.0, 0.0, yaw]);
        Pose { r, t: scale(crate::geom::mat_vec(&r, c), -1.0) }
    }

    fn tracked(ts: i64, c: [f64; 3], yaw: f64) -> Option<TrackedPose> {
        Some(TrackedPose {
            ts_ns: ts,
            pose: pose_at(c, yaw),
            reproj_rms: 0.3,
            corners: 40,
            margin: 50.0,
            stripe_px: 0,
            kept: 0,
            sharpness: 1.0,
        })
    }

    #[test]
    fn exact_interpolated_and_missing_lookups() {
        let mut t = PoseTrack::new(8);
        t.push(1_000 * FRAME, tracked(1_000 * FRAME, [0.0, 0.0, -300.0], 0.0));
        // frame 1001 skipped by the processing thread
        t.push(1_002 * FRAME, tracked(1_002 * FRAME, [20.0, 0.0, -300.0], 0.2));
        t.push(1_003 * FRAME, None);
        t.push(1_004 * FRAME, tracked(1_004 * FRAME, [40.0, 0.0, -300.0], 0.4));

        let exact = t.at(1_000 * FRAME, 3 * FRAME).unwrap();
        assert_eq!(exact.interpolated_gap_ns, 0);
        let mid = t.at(1_001 * FRAME, 3 * FRAME).unwrap();
        assert_eq!(mid.interpolated_gap_ns, 2 * FRAME);
        let c = mid.pose.camera_position();
        assert!((c[0] - 10.0).abs() < 1e-6 && (c[2] + 300.0).abs() < 1e-6, "{c:?}");
        assert!((rotation_distance(&mid.pose.r, &rodrigues([0.0, 0.0, 0.1]))) < 1e-9);
        // the board was lost at 1003: no pose there, none interpolated across it
        assert_eq!(t.at(1_003 * FRAME, 3 * FRAME).unwrap_err(), Miss::NoPose);
        assert_eq!(t.at(1_003 * FRAME + FRAME / 2, 3 * FRAME).unwrap_err(), Miss::NoPose);
        assert_eq!(t.at(1_001 * FRAME, FRAME).unwrap_err(), Miss::NoPose);
        assert_eq!(t.at(1_005 * FRAME, 3 * FRAME).unwrap_err(), Miss::NotYet);
        assert_eq!(t.at(10 * FRAME, 3 * FRAME).unwrap_err(), Miss::Evicted);
    }

    #[test]
    fn clock_restart_clears_the_track() {
        let mut t = PoseTrack::new(4);
        t.push(5_000, tracked(5_000, [0.0; 3], 0.0));
        t.push(100, tracked(100, [0.0; 3], 0.0));
        assert_eq!(t.frames.len(), 1);
    }

    #[test]
    fn speed_over_a_window_and_board_loss() {
        let mut t = PoseTrack::new(32);
        for i in 0..10 {
            // 30 fps, 1 mm and 0.1° per frame → 30 mm/s, 3°/s
            t.push(i * FRAME, tracked(i * FRAME, [i as f64, 0.0, -300.0], (0.1 * i as f64).to_radians()));
        }
        let (v, w) = t.speed(Trigger::SPEED_WINDOW_NS).unwrap();
        assert!((v - 30.0).abs() < 0.5 && (w - 3.0).abs() < 0.1, "{v} {w}");
        t.push(10 * FRAME, None);
        assert!(t.speed(Trigger::SPEED_WINDOW_NS).is_none());
    }

    fn run(trig: &mut Trigger, cfg: &KeyframeCfg, t: &mut PoseTrack, frame: i64, c: [f64; 3], yaw: f64) -> Due {
        t.push(frame * FRAME, tracked(frame * FRAME, c, yaw));
        trig.check(cfg, t)
    }

    #[test]
    fn first_then_interval_then_motion() {
        let cfg = KeyframeCfg { interval_s: 5.0, move_mm: 50.0, turn_deg: 10.0, ..Default::default() };
        let (mut trig, mut t) = (Trigger::default(), PoseTrack::new(64));
        let c = [0.0, 0.0, -300.0];
        // the first keyframe waits for a measured, steady camera
        assert_eq!(run(&mut trig, &cfg, &mut t, 0, c, 0.0), Due::Hold(Hold::Settling));
        let mut f = 1;
        let first = loop {
            match run(&mut trig, &cfg, &mut t, f, c, 0.0) {
                Due::Now => break f,
                Due::Hold(Hold::Settling) => f += 1,
                d => panic!("{d:?}"),
            }
        };
        assert!(first * FRAME >= Trigger::SPEED_WINDOW_NS / 2);
        // in flight: nothing more until it is taken
        assert_eq!(run(&mut trig, &cfg, &mut t, f + 1, c, 0.0), Due::No);
        trig.taken(first * FRAME, pose_at(c, 0.0));
        // standing still: the interval fires after 5 s
        let mut g = f + 2;
        while run(&mut trig, &cfg, &mut t, g, c, 0.0) == Due::No {
            g += 1;
        }
        assert_eq!(g - first, 151, "150 frames of 33.33 ms fall a hair short of 5 s");
        trig.taken(g * FRAME, pose_at(c, 0.0));
        // moving 60 mm slowly (≤ 30 mm/s) fires before the interval
        let start = g;
        let due = loop {
            g += 1;
            let x = (g - start) as f64; // 1 mm per frame
            if run(&mut trig, &cfg, &mut t, g, [x, 0.0, -300.0], 0.0) == Due::Now {
                break x;
            }
        };
        assert!((50.0..=51.0).contains(&due), "{due}");
    }

    #[test]
    fn fast_motion_bad_pose_and_no_board_hold() {
        let cfg = KeyframeCfg::default();
        let (mut trig, mut t) = (Trigger::default(), PoseTrack::new(64));
        for f in 0..10 {
            // 10 mm per frame = 300 mm/s
            let d = run(&mut trig, &cfg, &mut t, f, [10.0 * f as f64, 0.0, -300.0], 0.0);
            assert!(matches!(d, Due::Hold(Hold::Moving | Hold::Settling)), "{d:?}");
        }
        t.push(10 * FRAME, Some(TrackedPose { reproj_rms: 9.0, ..tracked(10 * FRAME, [0.0; 3], 0.0).unwrap() }));
        assert_eq!(trig.check(&cfg, &t), Due::Hold(Hold::PoseRms));
        t.push(10 * FRAME + 1, Some(TrackedPose { corners: 8, ..tracked(10 * FRAME, [0.0; 3], 0.0).unwrap() }));
        assert_eq!(trig.check(&cfg, &t), Due::Hold(Hold::FewCorners));
        t.push(11 * FRAME, None);
        assert_eq!(trig.check(&cfg, &t), Due::Hold(Hold::NoBoard));
    }

    #[test]
    fn a_failed_still_backs_off_and_a_late_one_answers_nothing() {
        let cfg = KeyframeCfg::default();
        let (mut trig, mut t) = (Trigger::default(), PoseTrack::new(256));
        let c = [0.0, 0.0, -300.0];
        let mut f = 0;
        while run(&mut trig, &cfg, &mut t, f, c, 0.0) != Due::Now {
            f += 1;
        }
        // the still was refused: no new request until the back-off has passed
        trig.failed(f * FRAME);
        assert_eq!(trig.failures(), 1);
        let blocked = Trigger::PENDING_TIMEOUT_NS / FRAME;
        for g in f + 1..f + blocked {
            assert_eq!(run(&mut trig, &cfg, &mut t, g, c, 0.0), Due::No, "frame {g}");
        }
        let req = f + blocked + 1;
        assert_eq!(run(&mut trig, &cfg, &mut t, req, c, 0.0), Due::Now);
        // a still exposed before this request (an older, timed-out one) does not answer it
        trig.taken((f + 2) * FRAME, pose_at(c, 0.0));
        assert!(trig.pending() && trig.failures() == 0);
        trig.taken((req + 1) * FRAME, pose_at(c, 0.0));
        assert!(!trig.pending());
        // and `last` never moves back in time
        trig.taken((f + 3) * FRAME, pose_at([500.0, 0.0, 0.0], 0.0));
        assert_eq!(run(&mut trig, &cfg, &mut t, req + 2, c, 0.0), Due::No);
    }

    #[test]
    fn a_lost_still_times_out_and_force_overrides_criteria() {
        let cfg = KeyframeCfg { interval_s: 0.0, move_mm: 0.0, turn_deg: 0.0, ..Default::default() };
        let (mut trig, mut t) = (Trigger::default(), PoseTrack::new(256));
        let c = [0.0, 0.0, -300.0];
        let mut f = 0;
        while run(&mut trig, &cfg, &mut t, f, c, 0.0) != Due::Now {
            f += 1;
        }
        trig.taken(f * FRAME, pose_at(c, 0.0));
        for g in f + 1..f + 200 {
            assert_eq!(run(&mut trig, &cfg, &mut t, g, c, 0.0), Due::No, "nothing enabled after the first");
        }
        trig.force();
        assert_eq!(run(&mut trig, &cfg, &mut t, f + 200, c, 0.0), Due::Now);
        // never taken: blocked for the timeout, then free again
        let blocked = Trigger::PENDING_TIMEOUT_NS / FRAME;
        trig.force();
        for g in f + 201..f + 200 + blocked {
            assert_eq!(run(&mut trig, &cfg, &mut t, g, c, 0.0), Due::No);
        }
        assert_eq!(run(&mut trig, &cfg, &mut t, f + 201 + blocked, c, 0.0), Due::Now);
    }
}
