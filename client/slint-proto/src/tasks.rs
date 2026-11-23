//! Launcher-lifetime task history. Blocking workers publish state; the UI samples it at 34ms.
use anyhow::{Result, ensure};
use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

#[derive(Clone, Default)]
pub struct Cancellation(Arc<AtomicBool>);
impl Cancellation {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }
    pub fn check(&self) -> Result<()> {
        ensure!(!self.cancelled(), "任务已取消");
        Ok(())
    }
    pub fn cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}
#[derive(Clone)]
pub struct Row {
    pub key: String,
    pub title: String,
    pub depth: usize,
    pub group: bool,
    pub expanded: bool,
    pub message: String,
    pub fraction: Option<f32>,
    pub done: bool,
}
struct Entry {
    id: String,
    title: String,
    dedupe: String,
    status: u8,
    created_at: u64,
    message: String,
    fraction: Option<f32>,
    error: String,
    rows: Vec<Row>,
    /// Shown instead of 安装完成 once the task succeeds (`Context::finish`).
    done_message: Option<String>,
    /// The row that `Context::progress` updates; 0 for single-leaf tasks.
    current: usize,
    cancel: Cancellation,
}
impl Entry {
    fn snapshot(&self) -> Value {
        json!({"createdAt":self.created_at,"runId":self.id,"title":self.title,"message":self.message,
            "fraction":self.fraction.unwrap_or(-1.),"error":self.error,"terminal":self.status>=2,"status":self.status,
            "rows":self.rows.iter().map(|r| json!({"key":r.key,"title":r.title,"depth":r.depth,
                "group":r.group,"expanded":r.expanded,"detail":r.fraction.map(|f|format!("{}%",(f.clamp(0.,1.)*100.) as u32)).unwrap_or_else(||r.message.clone()),
                "fraction":if r.done {1.} else {r.fraction.unwrap_or(-1.)},
                "status":if r.done {2} else if self.status>=2 {self.status} else if !r.message.is_empty() {1} else {0}})).collect::<Vec<_>>()})
    }
}
#[derive(Clone, Default)]
pub struct Manager(Arc<Mutex<Vec<Entry>>>);
#[derive(Clone)]
pub struct Context {
    manager: Manager,
    id: String,
    pub cancel: Cancellation,
}
impl Context {
    pub fn progress(&self, message: impl Into<String>, fraction: Option<f32>) {
        let mut entries = self.manager.0.lock().unwrap();
        if let Some(e) = entries.iter_mut().find(|e| e.id == self.id) {
            e.message = message.into();
            e.fraction = fraction;
            let current = e.current;
            e.rows[current].message = e.message.clone();
            e.rows[current].fraction = fraction;
            if current > 0 {
                // The group row shows the step message with the overall fraction.
                let steps = (e.rows.len() - 1) as f32;
                e.rows[0].message = e.message.clone();
                e.rows[0].fraction =
                    Some(((current - 1) as f32 + fraction.unwrap_or(0.).clamp(0., 1.)) / steps);
            }
        }
    }
    /// The message the task keeps once it succeeds, such as where a download was saved.
    pub fn finish(&self, message: impl Into<String>) {
        let mut entries = self.manager.0.lock().unwrap();
        if let Some(e) = entries.iter_mut().find(|e| e.id == self.id) {
            e.done_message = Some(message.into());
        }
    }
    /// Starts visible step `index` of a task created by `submit_steps`; earlier steps are done.
    pub fn step(&self, index: usize) {
        let mut entries = self.manager.0.lock().unwrap();
        if let Some(e) = entries.iter_mut().find(|e| e.id == self.id)
            && index + 1 < e.rows.len()
        {
            for row in &mut e.rows[1..=index] {
                row.done = true;
            }
            e.current = index + 1;
            // The step is running before its first progress report.
            e.message = "进行中".into();
            e.fraction = None;
            e.rows[index + 1].message = e.message.clone();
            e.rows[0].message = e.rows[index + 1].title.clone();
            e.rows[0].fraction = Some(index as f32 / (e.rows.len() - 1) as f32);
        }
    }
}
impl Manager {
    pub fn submit(
        &self,
        runtime: &tokio::runtime::Handle,
        title: String,
        dedupe: String,
        work: impl FnOnce(Context) -> Result<()> + Send + 'static,
    ) -> String {
        self.submit_steps(runtime, title, dedupe, &[], work)
    }
    /// Like `submit`, with one visible row per step under a group row.
    pub fn submit_steps(
        &self,
        runtime: &tokio::runtime::Handle,
        title: String,
        dedupe: String,
        steps: &[&str],
        work: impl FnOnce(Context) -> Result<()> + Send + 'static,
    ) -> String {
        let mut entries = self.0.lock().unwrap();
        if let Some(e) = entries.iter().find(|e| e.dedupe == dedupe && e.status < 2) {
            return e.id.clone();
        }
        let id = uuid::Uuid::now_v7().to_string();
        let cancel = Cancellation::default();
        entries.push(Entry {
            created_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64,
            id: id.clone(),
            title: title.clone(),
            dedupe,
            status: 0,
            message: "准备中".into(),
            fraction: None,
            error: String::new(),
            rows: std::iter::once(Row {
                key: id.clone(),
                title,
                depth: 0,
                group: !steps.is_empty(),
                expanded: true,
                message: String::new(),
                fraction: None,
                done: false,
            })
            .chain(steps.iter().enumerate().map(|(i, step)| Row {
                key: format!("{id} / {i}"),
                title: (*step).into(),
                depth: 1,
                group: false,
                expanded: true,
                message: String::new(),
                fraction: None,
                done: false,
            }))
            .collect(),
            done_message: None,
            current: 0,
            cancel: cancel.clone(),
        });
        drop(entries);
        let context = Context {
            manager: self.clone(),
            id: id.clone(),
            cancel,
        };
        runtime.spawn_blocking(move || {
            {
                let mut entries = context.manager.0.lock().unwrap();
                entries
                    .iter_mut()
                    .find(|e| e.id == context.id)
                    .unwrap()
                    .status = 1;
            }
            // A panicking worker must still end its task, or logout and exit stay blocked.
            let worker = context.clone();
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                worker.cancel.check().and_then(|()| work(worker.clone()))
            }))
            .unwrap_or_else(|_| Err(anyhow::anyhow!("任务意外中断")));
            let mut entries = context
                .manager
                .0
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let e = entries.iter_mut().find(|e| e.id == context.id).unwrap();
            match result {
                Ok(()) => {
                    e.status = 2;
                    e.fraction = Some(1.);
                    e.message = e.done_message.take().unwrap_or_else(|| "安装完成".into());
                    for row in &mut e.rows {
                        row.done = true;
                    }
                }
                Err(_) if e.cancel.cancelled() => {
                    e.status = 4;
                    e.message = "任务已取消".into();
                    e.error = e.message.clone();
                }
                Err(error) => {
                    tracing::error!(error=%format!("{error:#}"),title=%e.title,"整合包任务失败");
                    e.status = 3;
                    e.error = error.root_cause().to_string();
                    e.message = "任务失败".into();
                }
            }
        });
        id
    }
    pub fn cancel(&self, id: &str) {
        if let Some(e) = self
            .0
            .lock()
            .unwrap()
            .iter()
            .find(|e| e.id == id && e.status < 2)
        {
            e.cancel.cancel();
        }
    }
    /// The run id of the unfinished task with this dedupe key.
    pub fn active(&self, dedupe: &str) -> Option<String> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .find(|e| e.dedupe == dedupe && e.status < 2)
            .map(|e| e.id.clone())
    }
    pub fn owns(&self, id: &str) -> bool {
        self.0.lock().unwrap().iter().any(|e| e.id == id)
    }
    pub fn snapshot(&self, id: &str) -> Option<Value> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .find(|e| e.id == id)
            .map(Entry::snapshot)
    }
    pub fn list(&self) -> Vec<Value> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .rev()
            .map(Entry::snapshot)
            .collect()
    }
    pub fn running(&self) -> bool {
        self.0.lock().unwrap().iter().any(|e| e.status < 2)
    }
    pub fn shutdown(&self, timeout: Duration) {
        for e in self.0.lock().unwrap().iter().filter(|e| e.status < 2) {
            e.cancel.cancel();
        }
        let until = Instant::now() + timeout;
        while self.running() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(10));
        }
        if self.running() {
            tracing::warn!("等待导入任务清理超时");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_hierarchical_snapshot() {
        let fixture: Value =
            serde_json::from_str(include_str!("../protocol/fixtures/j4a.json")).unwrap();
        let entry = Entry {
            id: "run".into(),
            title: "安装".into(),
            dedupe: String::new(),
            status: 1,
            created_at: 1,
            message: "复制中".into(),
            fraction: Some(0.4),
            error: String::new(),
            cancel: Cancellation::default(),
            done_message: None,
            current: 0,
            rows: vec![
                Row {
                    key: "root".into(),
                    title: "安装".into(),
                    depth: 0,
                    group: true,
                    expanded: false,
                    message: "复制中".into(),
                    fraction: Some(0.4),
                    done: false,
                },
                Row {
                    key: "root / file#0".into(),
                    title: "文件".into(),
                    depth: 1,
                    group: false,
                    expanded: true,
                    message: "复制中".into(),
                    fraction: Some(0.4),
                    done: false,
                },
            ],
        };
        let mut expected = fixture["taskSnapshot"].clone();
        expected["fraction"] = json!(0.4_f32);
        for row in expected["rows"].as_array_mut().unwrap() {
            row["fraction"] = json!(0.4_f32);
        }
        assert_eq!(entry.snapshot(), expected);
    }
    #[test]
    fn dedupe_cancel_and_history() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let tasks = Manager::default();
        let id = tasks.submit(runtime.handle(), "复制".into(), "same".into(), |c| {
            while !c.cancel.cancelled() {
                std::thread::sleep(Duration::from_millis(1));
            }
            c.cancel.check()
        });
        assert_eq!(uuid::Uuid::parse_str(&id).unwrap().get_version_num(), 7);
        assert_eq!(
            id,
            tasks.submit(
                runtime.handle(),
                "duplicate".into(),
                "same".into(),
                |_| panic!("dedupe")
            )
        );
        tasks.shutdown(Duration::from_secs(2));
        let state = tasks.snapshot(&id).unwrap();
        assert_eq!(state["status"], 4);
        assert_eq!(state["message"], "任务已取消");
        assert_eq!(tasks.list().len(), 1);
        let next = tasks.submit(runtime.handle(), "新任务".into(), "same".into(), |c| {
            c.progress("正在保存", Some(0.5));
            Ok(())
        });
        let until = Instant::now() + Duration::from_secs(2);
        while tasks.running() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(1));
        }
        assert_ne!(next, id);
        assert_eq!(tasks.snapshot(&next).unwrap()["rows"][0]["status"], 2);
    }
    #[test]
    fn panicking_worker_fails_its_task() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let tasks = Manager::default();
        let id = tasks.submit(runtime.handle(), "崩溃".into(), "panic".into(), |_| {
            panic!("worker panic")
        });
        let until = Instant::now() + Duration::from_secs(2);
        while tasks.running() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(1));
        }
        assert!(!tasks.running());
        let state = tasks.snapshot(&id).unwrap();
        assert_eq!(state["status"], 3);
        assert_eq!(state["error"], "任务意外中断");
    }
}
