//! FileDownloader's host EWMA, URL/host cooldowns and active-chunk source scoring.
use std::{
    collections::{BTreeSet, HashMap},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
const INITIAL: f64 = 1024. * 1024.;
#[derive(Clone)]
struct Host {
    speed: Option<(f64, Instant)>,
    failures: u32,
    until: Instant,
}
#[derive(Default)]
struct History {
    hosts: HashMap<String, Host>,
    urls: HashMap<String, Instant>,
}
static HISTORY: OnceLock<Mutex<History>> = OnceLock::new();
fn host(url: &str) -> String {
    reqwest::Url::parse(url)
        .ok()
        .and_then(|u| {
            u.host_str().map(|h| {
                if h == "127.0.0.1" {
                    format!("{h}:{}", u.port_or_known_default().unwrap_or(0))
                } else {
                    h.to_owned()
                }
            })
        })
        .unwrap_or_else(|| url.into())
}
pub fn cooling(url: &str) -> bool {
    let now = Instant::now();
    let h = HISTORY.get_or_init(Default::default).lock().unwrap();
    h.urls.get(url).is_some_and(|t| *t > now)
        || h.hosts.get(&host(url)).is_some_and(|h| h.until > now)
}
pub fn speed(url: &str) -> Option<f64> {
    HISTORY
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .hosts
        .get(&host(url))
        .and_then(|h| h.speed)
        .filter(|(_, t)| t.elapsed() < Duration::from_secs(600))
        .map(|(v, _)| v)
}
pub fn success(url: &str, bytes: u64, elapsed: Duration) {
    let now = Instant::now();
    let mut h = HISTORY.get_or_init(Default::default).lock().unwrap();
    h.urls.remove(url);
    let h = h.hosts.entry(host(url)).or_insert(Host {
        speed: None,
        failures: 0,
        until: now,
    });
    h.failures = 0;
    h.until = now;
    if bytes >= 512 * 1024 {
        let measured = bytes as f64 / elapsed.as_secs_f64().max(0.001);
        let old = h
            .speed
            .filter(|(_, t)| t.elapsed() < Duration::from_secs(600))
            .map(|(v, _)| v);
        h.speed = Some((
            old.map(|v| v * 0.7 + measured * 0.3).unwrap_or(measured),
            now,
        ));
    }
}
pub fn failure(url: &str, host_failure: bool, retry_after: Option<u64>) {
    let now = Instant::now();
    let mut h = HISTORY.get_or_init(Default::default).lock().unwrap();
    h.urls.insert(
        url.into(),
        now + Duration::from_millis(retry_after.unwrap_or(30000)),
    );
    if host_failure {
        let h = h.hosts.entry(host(url)).or_insert(Host {
            speed: None,
            failures: 0,
            until: now,
        });
        h.failures += 1;
        let delay = retry_after.unwrap_or((30000u64 << (h.failures - 1).min(4)).min(300000));
        h.until = h.until.max(now + Duration::from_millis(delay));
    }
}
struct Stats {
    url: String,
    speed: f64,
    active: u32,
    failures: u32,
    completed: u32,
    until: Instant,
}
pub struct Scheduler(Mutex<Vec<Stats>>);
impl Scheduler {
    pub fn new(urls: impl Iterator<Item = String>) -> Self {
        Self(Mutex::new(
            urls.map(|url| {
                let cached = speed(&url);
                Stats {
                    url,
                    speed: cached.unwrap_or(INITIAL),
                    active: 0,
                    failures: 0,
                    completed: u32::from(cached.is_some()),
                    until: Instant::now(),
                }
            })
            .collect(),
        ))
    }
    pub fn acquire(&self, excluded: &BTreeSet<usize>) -> usize {
        let now = Instant::now();
        let mut stats = self.0.lock().unwrap();
        let available: Vec<_> = stats
            .iter()
            .enumerate()
            .filter(|(_, s)| s.until <= now && !cooling(&s.url))
            .map(|(i, _)| i)
            .collect();
        let fresh: Vec<_> = available
            .iter()
            .copied()
            .filter(|i| !excluded.contains(i))
            .collect();
        let eligible = if !fresh.is_empty() {
            fresh
        } else if !available.is_empty() {
            available
        } else {
            (0..stats.len()).collect()
        };
        let chosen = eligible
            .into_iter()
            .max_by(|a, b| {
                let score = |i: usize| {
                    let s = &stats[i];
                    if s.until > now {
                        0.
                    } else {
                        s.speed.max(1.)
                            / (1. + s.active as f64 * 0.75)
                            / (1. + s.failures as f64 * 2.)
                    }
                };
                score(*a).total_cmp(&score(*b)).then(b.cmp(a))
            })
            .unwrap();
        stats[chosen].active += 1;
        chosen
    }
    pub fn success(&self, index: usize, bytes: u64, elapsed: Duration) {
        let mut stats = self.0.lock().unwrap();
        let s = &mut stats[index];
        s.active = s.active.saturating_sub(1);
        let measured = bytes as f64 / elapsed.as_secs_f64().max(0.001);
        let w = if s.completed == 0 { 1. } else { 0.3 };
        s.speed = s.speed * (1. - w) + measured * w;
        s.completed += 1;
        s.failures = s.failures.saturating_sub(1);
        success(&s.url, bytes, elapsed);
    }
    pub fn failure(&self, index: usize, host_failure: bool, retry: Option<u64>) {
        let mut stats = self.0.lock().unwrap();
        let s = &mut stats[index];
        s.active = s.active.saturating_sub(1);
        s.failures += 1;
        s.until = Instant::now() + Duration::from_millis(retry.unwrap_or(5000));
        failure(&s.url, host_failure, retry);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn active_chunks_and_failures_reduce_score() {
        let s = Scheduler::new(
            ["https://a.invalid/a".into(), "https://b.invalid/b".into()].into_iter(),
        );
        let a = s.acquire(&Default::default());
        assert_eq!(a, 0);
        assert_eq!(s.acquire(&Default::default()), 1);
        s.failure(0, false, None);
        assert_eq!(s.acquire(&Default::default()), 1);
    }
}
