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
