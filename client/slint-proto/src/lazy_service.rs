//! Shared on-demand process generations. Failed mutations are never replayed.
use crate::service::Service;
use anyhow::{Result, anyhow, ensure};
use serde_json::{Value, json};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::time::{Duration, Instant};

type Started = std::result::Result<Arc<Service>, String>;
type Factory = dyn Fn() -> Result<Service> + Send + Sync;
#[derive(Default)]
struct Attempt {
    result: Mutex<Option<Started>>,
    ready: Condvar,
}
#[derive(Default)]
struct State {
    running: Option<Arc<Service>>,
    starting: Option<Arc<Attempt>>,
    closed: bool,
}
pub struct LazyService {
    state: Mutex<State>,
    /// A shutdown proposal excludes requests, including their startup prelude.
    requests: RwLock<()>,
    factory: Box<Factory>,
    session: Value,
    uid: String,
    processes: Mutex<Vec<Value>>,
    idle_since: Mutex<Option<Instant>>,
}
impl LazyService {
    pub fn new(
        factory: impl Fn() -> Result<Service> + Send + Sync + 'static,
        session: Value,
        uid: String,
    ) -> Self {
        Self {
            state: Mutex::default(),
            requests: RwLock::default(),
            factory: Box::new(factory),
            session,
            uid,
            processes: Mutex::default(),
            idle_since: Mutex::default(),
        }
    }
    fn generation(&self) -> Result<Arc<Service>> {
        let (attempt, owner) = {
            let mut state = self.state.lock().unwrap();
            ensure!(!state.closed, "已退出登录");
            if let Some(service) = &state.running {
                return Ok(service.clone());
            }
            if let Some(attempt) = &state.starting {
                (attempt.clone(), false)
            } else {
                let attempt = Arc::new(Attempt::default());
                state.starting = Some(attempt.clone());
                (attempt, true)
            }
        };
        if owner {
            let result = (|| {
                let start = Instant::now();
                let service = Arc::new((self.factory)()?);
                let reply = service.request(
                    "session.set",
                    self.session.clone(),
                    Duration::from_secs(60),
                )?;
                ensure!(
                    reply["uid"].as_str() == Some(self.uid.as_str()),
                    "登录身份不一致"
                );
                tracing::info!(
                    elapsed_ms = start.elapsed().as_millis() as u64,
                    "启动准备完成"
                );
                let processes = self.processes.lock().unwrap().clone();
                if !processes.is_empty() {
                    service.request(
                        "game.restore",
                        json!({"sessions":processes}),
                        Duration::from_secs(60),
                    )?;
                }
                for method in ["game.subscribe", "tasks.subscribe"] {
                    service.request(method, json!({}), Duration::from_secs(60))?;
                }
                Ok(service)
            })()
            .map_err(|error: anyhow::Error| format!("{error:#}"));
            let mut state = self.state.lock().unwrap();
            let result = if state.closed {
                if let Ok(service) = &result {
                    service.terminate();
                }
                Err("已退出登录".into())
            } else {
                result
            };
            if let Ok(service) = &result {
                state.running = Some(service.clone());
            }
            *attempt.result.lock().unwrap() = Some(result);
            state.starting = None;
            attempt.ready.notify_all();
        }
        let mut result = attempt.result.lock().unwrap();
        while result.is_none() {
            result = attempt.ready.wait(result).unwrap();
        }
        result.as_ref().unwrap().clone().map_err(anyhow::Error::msg)
    }
    pub fn request(&self, method: &str, params: Value) -> Result<Value> {
        let _guard = self.requests.read().unwrap();
        *self.idle_since.lock().unwrap() = None;
        self.generation()?
            .request(method, params, Duration::from_secs(60))
    }
    pub fn update_processes(&self, processes: Vec<Value>) {
        *self.processes.lock().unwrap() = processes;
    }
    pub fn take_events(&self) -> Result<Vec<Value>> {
        let service = self.state.lock().unwrap().running.clone();
        service
            .map(|s| s.take_events())
            .unwrap_or_else(|| Ok(vec![]))
    }
    pub fn take_unexpected_exit(&self) -> Result<bool> {
        // An orderly shutdown owns the write lock until the generation is removed.
        let Ok(_guard) = self.requests.try_read() else {
            return Ok(false);
        };
        let mut state = self.state.lock().unwrap();
        if state
            .running
            .as_ref()
            .is_some_and(|s| s.has_exited().unwrap_or(true))
        {
            state.running.take();
            *self.idle_since.lock().unwrap() = None;
            return Ok(true);
        }
        Ok(false)
    }
    pub fn stop_immediately(&self) {
        let mut state = self.state.lock().unwrap();
        state.closed = true;
        let starting = state.starting.clone();
        if let Some(service) = state.running.take() {
            service.terminate();
        }
        drop(state);
        // A start already in progress must be gone before logout edits its account files.
        if let Some(starting) = starting {
            let mut result = starting.result.lock().unwrap();
            while result.is_none() {
                result = starting.ready.wait(result).unwrap();
            }
        }
    }
    pub fn idle_due(&self, eligible: bool) -> bool {
        let Ok(_guard) = self.requests.try_write() else {
            *self.idle_since.lock().unwrap() = None;
            return false;
        };
        let running = self.state.lock().unwrap().running.is_some();
        let mut since = self.idle_since.lock().unwrap();
        if !eligible || !running {
            *since = None;
            return false;
        }
        since.get_or_insert_with(Instant::now).elapsed() >= Duration::from_secs(60)
    }

    pub fn stop_if_idle(&self) -> Result<bool> {
        let _guard = self.requests.write().unwrap();
        *self.idle_since.lock().unwrap() = None;
        let service = self.state.lock().unwrap().running.clone();
        let Some(service) = service else {
            return Ok(false);
        };
        let reply = service.request("shutdown", json!({"ifIdle":true}), Duration::from_secs(60))?;
        let stopped = reply["stopped"]
            .as_bool()
            .ok_or_else(|| anyhow!("准备状态无效"))?;
        if stopped {
            self.state.lock().unwrap().running.take();
        }
        Ok(stopped)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol;
    use std::{
        sync::atomic::{AtomicUsize, Ordering},
        thread,
    };
    type Harness = (Arc<LazyService>, Arc<AtomicUsize>, Arc<Mutex<Vec<String>>>);
    fn harness(fail_first: bool, stopped: bool) -> Harness {
        let count = Arc::new(AtomicUsize::new(0));
        let calls = Arc::new(Mutex::new(vec![]));
        let (factory_count, factory_calls) = (count.clone(), calls.clone());
        let lazy = LazyService::new(
            move || {
                let generation = factory_count.fetch_add(1, Ordering::SeqCst);
                let (output, mut writer) = std::io::pipe()?;
                let (mut reader, input) = std::io::pipe()?;
                let calls = factory_calls.clone();
                thread::spawn(move || {
                    while let Ok(Some(frame)) = protocol::read_frame(&mut reader) {
                        let method = frame["method"].as_str().unwrap();
                        calls.lock().unwrap().push(method.to_owned());
                        if method == "session.set" {
                            thread::sleep(Duration::from_millis(80));
                        }
                        let result = match method {
                            "handshake" => json!({"protocolVersion":protocol::VERSION}),
                            "session.set" => {
                                json!({"uid":if fail_first && generation == 0 {"wrong"} else {"fake"}})
                            }
                            "shutdown" => json!({"stopped":stopped}),
                            _ => json!({}),
                        };
                        if protocol::write_frame(
                            &mut writer,
                            &json!({"id":frame["id"],"result":result}),
                        )
                        .is_err()
                        {
                            break;
                        }
                        if method == "shutdown" && stopped {
                            break;
                        }
                    }
                });
                Service::fake(output, input)
            },
            json!({}),
            "fake".into(),
        );
        (Arc::new(lazy), count, calls)
    }
    #[test]
    fn concurrent_first_calls_share_one_start_and_ordered_prelude() {
        let (lazy, count, calls) = harness(false, true);
        lazy.update_processes(vec![json!({"pid":7})]);
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let lazy = lazy.clone();
                thread::spawn(move || lazy.request("action", json!({})))
            })
            .collect();
        for worker in workers {
            worker.join().unwrap().unwrap();
        }
        assert_eq!(count.load(Ordering::SeqCst), 1);
        assert_eq!(
            &calls.lock().unwrap()[..5],
            &[
                "handshake",
                "session.set",
                "game.restore",
                "game.subscribe",
                "tasks.subscribe"
            ]
        );
    }
    #[test]
    fn failed_start_is_shared_and_next_action_retries() {
        let (lazy, count, _) = harness(true, true);
        let first = {
            let lazy = lazy.clone();
            thread::spawn(move || lazy.request("mutate", json!({})))
        };
        while count.load(Ordering::SeqCst) == 0 {
            thread::yield_now();
        }
        assert!(lazy.request("mutate", json!({})).is_err());
        assert!(first.join().unwrap().is_err());
        assert_eq!(count.load(Ordering::SeqCst), 1);
        lazy.request("retry", json!({})).unwrap();
        assert_eq!(count.load(Ordering::SeqCst), 2);
    }
    #[test]
    fn accepted_idle_stop_is_silent_and_next_action_starts_again() {
        let (lazy, count, _) = harness(false, true);
        lazy.request("first", json!({})).unwrap();
        assert!(lazy.stop_if_idle().unwrap());
        assert!(!lazy.take_unexpected_exit().unwrap());
        assert!(lazy.take_events().unwrap().is_empty());
        lazy.request("second", json!({})).unwrap();
        assert_eq!(count.load(Ordering::SeqCst), 2);
    }
    #[test]
    fn refused_idle_stop_keeps_generation_and_resets_timer() {
        let (lazy, count, _) = harness(false, false);
        lazy.request("first", json!({})).unwrap();
        assert!(!lazy.stop_if_idle().unwrap());
        lazy.request("second", json!({})).unwrap();
        assert_eq!(count.load(Ordering::SeqCst), 1);
        assert!(!lazy.idle_due(true));
        *lazy.idle_since.lock().unwrap() = Some(Instant::now() - Duration::from_secs(61));
        assert!(lazy.idle_due(true));
        assert!(!lazy.idle_due(false));
        assert!(!lazy.idle_due(true));
    }
    #[test]
    fn action_waits_for_accepted_shutdown_before_starting() {
        let (lazy, count, _) = harness(false, true);
        lazy.request("first", json!({})).unwrap();
        let guard = lazy.requests.write().unwrap();
        let next = { let lazy = lazy.clone(); thread::spawn(move || lazy.request("second", json!({}))) };
        // Same transition as stop_if_idle, while the proposal owns the write lock.
        let service = lazy.state.lock().unwrap().running.clone().unwrap();
        assert_eq!(service.request("shutdown", json!({"ifIdle":true}), Duration::from_secs(5)).unwrap()["stopped"], true);
        lazy.state.lock().unwrap().running.take();
        assert_eq!(count.load(Ordering::SeqCst), 1);
        drop(guard);
        next.join().unwrap().unwrap();
        assert_eq!(count.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn logout_while_stopped_never_spawns() {
        let (lazy, count, _) = harness(false, true);
        lazy.stop_immediately();
        assert!(lazy.request("action", json!({})).is_err());
        assert_eq!(count.load(Ordering::SeqCst), 0);
    }
}
