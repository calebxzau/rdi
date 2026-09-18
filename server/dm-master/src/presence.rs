use crate::model::{PresenceState, SessionRequest};
use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::Mutex;
use uuid::Uuid;

const HEARTBEAT_EXPIRY: Duration = Duration::from_secs(30);

#[derive(Clone)]
pub struct Presence {
    state: Arc<Mutex<State>>,
}

struct State {
    gateway_healthy: bool,
    sync_required: bool,
    last_heartbeat: Option<Instant>,
    sessions: HashMap<Uuid, SessionRequest>,
}

impl Default for Presence {
    fn default() -> Self {
        Self {
            state: Arc::new(Mutex::new(State {
                gateway_healthy: false,
                sync_required: true,
                last_heartbeat: None,
                sessions: HashMap::new(),
            })),
        }
    }
}

impl Presence {
    pub async fn heartbeat(&self) -> bool {
        let mut state = self.state.lock().await;
        if state
            .last_heartbeat
            .is_some_and(|last| last.elapsed() > HEARTBEAT_EXPIRY)
        {
            state.sync_required = true;
        }
        state.gateway_healthy = true;
        state.last_heartbeat = Some(Instant::now());
        state.sync_required
    }

    pub async fn sync(&self, sessions: Vec<SessionRequest>) -> Result<(), String> {
        let mut state = self.state.lock().await;
        if !state.gateway_healthy {
            return Err("gateway heartbeat has not been received".into());
        }
        state.sessions = sessions
            .into_iter()
            .map(|session| (session.host_id, session))
            .collect();
        state.sync_required = false;
        Ok(())
    }

    pub async fn online(&self, host_id: Uuid, session: SessionRequest) -> Result<(), String> {
        let mut state = self.state.lock().await;
        if !state.gateway_healthy || state.sync_required {
            return Err("gateway synchronization is required".into());
        }
        state.sessions.insert(host_id, session);
        Ok(())
    }

    pub async fn offline(&self, host_id: Uuid, session_id: Uuid) {
        let mut state = self.state.lock().await;
        if state
            .sessions
            .get(&host_id)
            .is_some_and(|session| session.session_id == session_id)
        {
            state.sessions.remove(&host_id);
        }
    }

    pub async fn state_for(&self, host_id: Uuid) -> (PresenceState, Option<u16>) {
        let mut state = self.state.lock().await;
        if state
            .last_heartbeat
            .is_some_and(|last| last.elapsed() > HEARTBEAT_EXPIRY)
        {
            state.gateway_healthy = false;
            state.sync_required = true;
        }
        if !state.gateway_healthy || state.sync_required {
            return (PresenceState::Unavailable, None);
        }
        match state.sessions.get(&host_id) {
            Some(session) => (PresenceState::Online, Some(session.game_port)),
            None => (PresenceState::Offline, None),
        }
    }

    pub async fn is_healthy(&self) -> bool {
        let state = self.state.lock().await;
        state.gateway_healthy
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(host_id: Uuid, session_id: Uuid) -> SessionRequest {
        SessionRequest {
            host_id,
            session_id,
            game_port: 26000,
        }
    }

    #[tokio::test]
    async fn requires_sync_and_ignores_stale_offline() {
        let presence = Presence::default();
        let host_id = Uuid::now_v7();
        let first = Uuid::now_v7();
        let second = Uuid::now_v7();
        assert!(presence.heartbeat().await);
        presence.sync(vec![]).await.unwrap();
        presence
            .online(host_id, session(host_id, first))
            .await
            .unwrap();
        presence
            .online(host_id, session(host_id, second))
            .await
            .unwrap();
        presence.offline(host_id, first).await;
        assert!(matches!(
            presence.state_for(host_id).await.0,
            PresenceState::Online
        ));
        presence.offline(host_id, second).await;
        assert!(matches!(
            presence.state_for(host_id).await.0,
            PresenceState::Offline
        ));
    }

    #[tokio::test]
    async fn heartbeat_after_expiry_requires_full_sync() {
        let presence = Presence::default();
        let host_id = Uuid::now_v7();
        presence.heartbeat().await;
        presence.sync(vec![]).await.unwrap();
        presence
            .online(host_id, session(host_id, Uuid::now_v7()))
            .await
            .unwrap();
        {
            let mut state = presence.state.lock().await;
            state.last_heartbeat = Some(Instant::now() - HEARTBEAT_EXPIRY - Duration::from_secs(1));
        }
        assert!(matches!(
            presence.state_for(host_id).await.0,
            PresenceState::Unavailable
        ));
        assert!(presence.heartbeat().await);
        assert!(matches!(
            presence.state_for(host_id).await.0,
            PresenceState::Unavailable
        ));
        presence.sync(vec![]).await.unwrap();
        assert!(matches!(
            presence.state_for(host_id).await.0,
            PresenceState::Offline
        ));
    }
}
