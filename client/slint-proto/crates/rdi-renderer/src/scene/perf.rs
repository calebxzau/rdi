use std::time::{Duration, Instant};
pub struct Monitor {
    count: usize,
    total: Duration,
    max: Duration,
    threshold_ms: f64,
}
impl Default for Monitor {
    fn default() -> Self {
        Self::with_threshold(45.)
    }
}
impl Monitor {
    pub fn with_threshold(threshold_ms: f64) -> Self {
        Self {
            count: 0,
            total: Duration::ZERO,
            max: Duration::ZERO,
            threshold_ms,
        }
    }
    pub fn sample(&mut self, work: Duration) -> bool {
        self.count += 1;
        if self.count <= 30 {
            return false;
        }
        self.total += work;
        self.max = self.max.max(work);
        if self.count < 90 {
            return false;
        }
        let average = self.total.as_secs_f64() * 1000. / 60.;
        tracing::info!(
            average_ms = average,
            max_ms = self.max.as_secs_f64() * 1000.,
            threshold_ms = self.threshold_ms,
            "Scene completion window (CPU encode + submit-to-done; excludes presentation)"
        );
        self.count = 30;
        self.total = Duration::ZERO;
        self.max = Duration::ZERO;
        average > self.threshold_ms
    }
}
/// How often the scene pacer asks [`Clock`] for a frame while the scene animates. The clock can
/// only release a frame on a tick, so a tick close to its 1/60s gate (such as 16ms) regularly lands
/// just before the deadline and turns one frame into two. A much shorter tick avoids that.
pub const PACER_INTERVAL_MS: u64 = 4;
/// Five-second summaries of the time between rendered frames, for `--frame-stats`.
#[derive(Default)]
pub struct FramePacing {
    last: Option<Instant>,
    window: Option<Instant>,
    intervals: Vec<f64>,
}
pub struct PacingSummary {
    pub count: usize,
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
    pub max_ms: f64,
    pub over_25ms: usize,
    pub over_30ms: usize,
}
impl FramePacing {
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    pub fn frame(&mut self, now: Instant) -> Option<PacingSummary> {
        if let Some(last) = self.last {
            self.intervals
                .push(now.duration_since(last).as_secs_f64() * 1000.);
        }
        self.last = Some(now);
        let window = *self.window.get_or_insert(now);
        if now.duration_since(window) < Duration::from_secs(5) || self.intervals.is_empty() {
            return None;
        }
        self.window = Some(now);
        let mut values = std::mem::take(&mut self.intervals);
        values.sort_by(f64::total_cmp);
        let percentile = |q: f64| values[((values.len() - 1) as f64 * q) as usize];
        Some(PacingSummary {
            count: values.len(),
            p50_ms: percentile(0.5),
            p95_ms: percentile(0.95),
            p99_ms: percentile(0.99),
            max_ms: values[values.len() - 1],
            over_25ms: values.iter().filter(|v| **v > 25.).count(),
            over_30ms: values.iter().filter(|v| **v > 30.).count(),
        })
    }
}
/// One clock for timer pacing and animation. Inactive time never enters elapsed animation time.
pub struct Clock {
    last: Option<Instant>,
    deadline: Instant,
    elapsed: f64,
    generation: u64,
}
impl Default for Clock {
    fn default() -> Self {
        Self::new()
    }
}
impl Clock {
    pub fn new() -> Self {
        Self {
            last: None,
            deadline: Instant::now(),
            elapsed: 0.,
            generation: 0,
        }
    }
    pub fn tick(&mut self, now: Instant, active: bool) -> bool {
        if !active {
            self.last = None;
            self.deadline = now;
            return false;
        }
        if self.last.is_none() {
            self.generation += 1;
        }
        if let Some(last) = self.last {
            self.elapsed += now.duration_since(last).as_secs_f64().min(0.1);
        }
        self.last = Some(now);
        if now < self.deadline {
            return false;
        }
        self.deadline += Duration::from_secs_f64(1. / 60.);
        if self.deadline < now {
            self.deadline = now + Duration::from_secs_f64(1. / 60.);
        }
        true
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn elapsed(&self) -> f64 {
        self.elapsed
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn warmup_spikes_and_sustained_slowness() {
        let mut m = Monitor::default();
        for _ in 0..30 {
            assert!(!m.sample(Duration::from_secs(1)))
        }
        for _ in 0..59 {
            assert!(!m.sample(Duration::from_millis(7)))
        }
        assert!(!m.sample(Duration::from_millis(100)));
        for _ in 0..59 {
            assert!(!m.sample(Duration::from_millis(46)))
        }
        assert!(m.sample(Duration::from_millis(46)));
    }
    #[test]
    fn pause_resume_and_clamp() {
        let mut c = Clock::new();
        let n = Instant::now();
        assert!(c.tick(n, true));
        c.tick(n + Duration::from_secs(1), true);
        assert_eq!(c.elapsed(), 0.1);
        c.tick(n + Duration::from_secs(2), false);
        assert!(c.tick(n + Duration::from_secs(60), true));
        assert_eq!(c.elapsed(), 0.1);
    }
    #[test]
    fn lowered_threshold_keeps_warmup_and_full_window() {
        let mut monitor = Monitor::with_threshold(0.001);
        for _ in 0..89 {
            assert!(!monitor.sample(Duration::from_millis(1)));
        }
        assert!(monitor.sample(Duration::from_millis(1)));
    }
    /// Longest gap between released frames over ten seconds of evenly spaced ticks.
    fn longest_release_gap(tick: Duration) -> (Duration, usize) {
        let mut clock = Clock::new();
        let start = Instant::now();
        clock.deadline = start;
        let (mut longest, mut released, mut last) = (Duration::ZERO, 0, start);
        let mut now = start;
        while now < start + Duration::from_secs(10) {
            if clock.tick(now, true) {
                longest = longest.max(now - last);
                released += 1;
                last = now;
            }
            now += tick;
        }
        (longest, released)
    }
    #[test]
    fn pacer_tick_never_doubles_a_frame() {
        let (longest, released) =
            longest_release_gap(Duration::from_millis(PACER_INTERVAL_MS));
        assert!(longest <= Duration::from_millis(21), "{longest:?}");
        assert!((595..=605).contains(&released), "{released}");
        // The tick this replaces: same frame rate, but it regularly waits a whole extra tick.
        let (longest, released) = longest_release_gap(Duration::from_millis(16));
        assert_eq!(longest, Duration::from_millis(32));
        assert!((595..=605).contains(&released), "{released}");
    }
    #[test]
    fn frame_pacing_reports_each_window_and_resets() {
        let mut pacing = FramePacing::default();
        let start = Instant::now();
        let mut now = start;
        assert!(pacing.frame(now).is_none());
        let mut summary = None;
        for i in 1..=330 {
            now += Duration::from_millis(if i % 100 == 0 { 40 } else { 16 });
            summary = summary.or(pacing.frame(now));
        }
        let summary = summary.expect("five seconds elapsed");
        assert!(summary.count >= 300);
        assert_eq!(summary.over_30ms, summary.over_25ms);
        assert!(summary.over_30ms >= 3);
        assert!((summary.max_ms - 40.).abs() < 0.001);
        assert!((summary.p50_ms - 16.).abs() < 0.001);
        pacing.reset();
        assert!(pacing.frame(now + Duration::from_secs(60)).is_none());
    }
    #[test]
    fn paused_callbacks_never_advance_or_request_scene_frames() {
        let mut clock = Clock::new();
        let start = Instant::now();
        assert!(clock.tick(start, true));
        clock.tick(start + Duration::from_millis(16), true);
        let before = clock.elapsed();
        let generation = clock.generation();
        for i in 2..200 {
            assert!(!clock.tick(start + Duration::from_millis(i * 16), false));
        }
        assert_eq!(clock.elapsed(), before);
        assert!(clock.tick(start + Duration::from_secs(40), true));
        assert_eq!(clock.elapsed(), before);
        assert_eq!(clock.generation(), generation + 1);
    }
}
