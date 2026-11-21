//! Positive and negative TTL entries, LRU eviction and one in-flight loader per key.
use std::{
    collections::HashMap,
    hash::Hash,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
type Flight<V> = Arc<tokio::sync::Mutex<Option<std::result::Result<Option<V>, String>>>>;
struct Entry<V> {
    value: Option<V>,
    expires: Instant,
    used: u64,
}
pub struct MemoryCache<K, V> {
    values: Mutex<(HashMap<K, Entry<V>>, u64)>,
    loads: Mutex<HashMap<K, Flight<V>>>,
    positive: Duration,
    negative: Duration,
    max: usize,
}
impl<K: Clone + Eq + Hash, V: Clone> MemoryCache<K, V> {
    pub fn new(positive: Duration, negative: Duration, max: usize) -> Self {
        Self {
            values: Mutex::new((HashMap::new(), 0)),
            loads: Mutex::new(HashMap::new()),
            positive,
            negative,
            max,
        }
    }
    pub fn get(&self, key: &K) -> Option<Option<V>> {
        self.get_at(key, Instant::now())
    }
    fn get_at(&self, key: &K, now: Instant) -> Option<Option<V>> {
        let mut state = self.values.lock().unwrap();
        state.1 += 1;
        let tick = state.1;
        if let Some(e) = state.0.get_mut(key)
            && e.expires > now
        {
            e.used = tick;
            return Some(e.value.clone());
        }
        state.0.remove(key);
        None
    }
    pub fn put(&self, key: K, value: Option<V>) {
        let mut state = self.values.lock().unwrap();
        state.1 += 1;
        let tick = state.1;
        let ttl = if value.is_some() {
            self.positive
        } else {
            self.negative
        };
        state.0.insert(
            key,
            Entry {
                value,
                expires: Instant::now() + ttl,
                used: tick,
            },
        );
        while state.0.len() > self.max {
            let old = state
                .0
                .iter()
                .min_by_key(|(_, v)| v.used)
                .map(|(k, _)| k.clone())
                .unwrap();
            state.0.remove(&old);
        }
    }
    pub async fn get_or_load(
        &self,
        key: K,
        load: impl std::future::Future<Output = anyhow::Result<Option<V>>>,
    ) -> anyhow::Result<Option<V>> {
        if let Some(v) = self.get(&key) {
            return Ok(v);
        }
        let lock = {
            let mut loads = self.loads.lock().unwrap();
            loads.retain(|_, v| Arc::strong_count(v) > 1);
            loads
                .entry(key.clone())
                .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(None)))
                .clone()
        };
        let mut guard = lock.lock().await;
        if let Some(result) = &*guard {
            return result.clone().map_err(anyhow::Error::msg);
        }
        if let Some(v) = self.get(&key) {
            return Ok(v);
        }
        match load.await {
            Ok(v) => {
                self.put(key, v.clone());
                *guard = Some(Ok(v.clone()));
                Ok(v)
            }
            Err(error) => {
                *guard = Some(Err(error.to_string()));
                Err(error)
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn positive_negative_expiry_failure_and_lru() {
        let c = MemoryCache::new(Duration::from_secs(600), Duration::from_secs(30), 2);
        c.put("a", Some(1));
        c.put("b", None);
        assert_eq!(c.get(&"a"), Some(Some(1)));
        c.put("c", Some(3));
        assert_eq!(c.get(&"b"), None);
        assert_eq!(
            c.get_at(&"a", Instant::now() + Duration::from_secs(601)),
            None
        );
        assert!(
            c.get_or_load("x", async { anyhow::bail!("failed") })
                .await
                .is_err()
        );
        assert_eq!(
            c.get_or_load("x", async { Ok(Some(7)) }).await.unwrap(),
            Some(7)
        );
    }
    #[tokio::test]
    async fn concurrent_load_is_single_flight() {
        let c = MemoryCache::new(Duration::from_secs(600), Duration::from_secs(30), 64);
        let n = std::sync::atomic::AtomicUsize::new(0);
        let load = || async {
            n.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tokio::task::yield_now().await;
            Ok(Some(4))
        };
        let (a, b) = tokio::join!(c.get_or_load("k", load()), c.get_or_load("k", load()));
        assert_eq!(a.unwrap(), b.unwrap());
        assert_eq!(n.load(std::sync::atomic::Ordering::Relaxed), 1);
    }
}
