//! Fault scenarios on simulated nodes, running on Tokio's virtual clock.

mod common;

use common::*;
use rdi_session_resume::{
    EndReason, Tuning,
    anchor::{Anchor, AnchorEvent},
    client::{AttachReason, SessionEvent},
    protocol::{
        CLIENT_HELLO_LEN, ClientHello, Frame, HelloStatus, SERVER_HELLO_LEN, ServerHello, VERSION,
    },
};
use std::{net::SocketAddr, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex},
    task::JoinHandle,
    time::{Instant, sleep, timeout},
};

const TARGET_RECOVERY_MS: u64 = 8_000;

async fn round_trip(app: &mut DuplexStream, message: &[u8]) {
    app.write_all(message).await.unwrap();
    let mut echoed = vec![0u8; message.len()];
    timeout(Duration::from_secs(30), app.read_exact(&mut echoed))
        .await
        .expect("echo timed out")
        .unwrap();
    assert_eq!(echoed, message);
}

async fn wait_until(mut condition: impl FnMut() -> bool) {
    timeout(Duration::from_secs(60), async {
        while !condition() {
            sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("condition not reached");
}

#[tokio::test(start_paused = true)]
async fn data_flows_through_the_first_node() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"hello").await;
    assert_eq!(client.log.attached_nodes(), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn killed_node_fails_over_quickly_without_losing_data() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    network.kill("a");
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a", "b"]);
    assert!(client.log.last_elapsed_ms().unwrap() <= 3_000);
}

#[tokio::test(start_paused = true)]
async fn silent_node_is_replaced_within_the_budget() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    let frozen_at = Instant::now();
    network.freeze("a");
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a", "b"]);
    assert!(frozen_at.elapsed() <= Duration::from_millis(TARGET_RECOVERY_MS));
    // The reported time covers the outage, including the detection window.
    let reported = client.log.last_elapsed_ms().unwrap();
    assert!(
        (3_000..=TARGET_RECOVERY_MS).contains(&reported),
        "{reported}"
    );
}

#[tokio::test(start_paused = true)]
async fn node_that_accepts_but_forwards_nothing_does_not_win() {
    // Like a tunnel that is down behind a node still accepting connections.
    let network = Network::new(&["a", "b", "c"], echo_anchor());
    network.freeze("b");
    let mut client = connect(&network, &["a", "b", "c"]);
    round_trip(&mut client.app, b"before").await;

    network.freeze("a");
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a", "c"]);
}

#[tokio::test(start_paused = true)]
async fn one_way_silence_is_detected_from_stalled_acks() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a", "b"], anchor);
    let config = rdi_session_resume::client::ClientConfig {
        soft_switch_interval: Duration::ZERO,
        ..Default::default()
    };
    let mut client = connect_with(&network, &["a", "b"], config);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();
    let mut byte = [0u8; 1];
    server.read_exact(&mut byte).await.unwrap();

    // Downstream traffic keeps arriving, so only the upstream acknowledgements reveal the fault.
    network.freeze_up("a");
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let feeder_stop = Arc::clone(&stop);
    let (mut server_read, mut server_write) = tokio::io::split(server);
    let feeder = tokio::spawn(async move {
        while !feeder_stop.load(std::sync::atomic::Ordering::Relaxed) {
            if server_write.write_all(b"tick").await.is_err() {
                return;
            }
            sleep(Duration::from_millis(100)).await;
        }
    });
    client.app.write_all(b"stuck").await.unwrap();
    let mut received = [0u8; 5];
    timeout(
        Duration::from_secs(10),
        server_read.read_exact(&mut received),
    )
    .await
    .expect("upstream bytes did not recover")
    .unwrap();
    assert_eq!(&received, b"stuck");
    assert_eq!(client.log.attached_nodes(), ["a", "b"]);
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    feeder.abort();
}

#[tokio::test(start_paused = true)]
async fn slow_acknowledgement_does_not_switch_again_too_soon() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a", "b"], anchor);
    let mut client = connect(&network, &["a", "b"]);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();
    let mut byte = [0u8; 1];
    server.read_exact(&mut byte).await.unwrap();

    // The application at the anchor stops reading, so acknowledgements stall on a healthy path.
    client.app.write_all(&vec![1; 40 * 1024]).await.unwrap();
    sleep(Duration::from_secs(10)).await;

    assert_eq!(client.log.attached_nodes(), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn manual_switch_keeps_the_old_path_until_the_new_one_answers() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    client.table.switch_to("b".to_string());
    wait_until(|| client.log.attached_nodes().len() == 2).await;
    round_trip(&mut client.app, b"after").await;
    sleep(Duration::from_secs(10)).await;
    round_trip(&mut client.app, b"later").await;

    // Exactly one switch: losing the old path must not start another one.
    assert_eq!(client.log.attached_nodes(), ["a", "b"]);
    let events = client.log.all();
    assert!(
        !events
            .iter()
            .any(|event| matches!(event, SessionEvent::PathLost { .. }))
    );
    assert!(events.iter().any(|event| matches!(
        event,
        SessionEvent::Attached {
            reason: AttachReason::Manual,
            ..
        }
    )));
}

#[tokio::test(start_paused = true)]
async fn failed_manual_switch_stays_on_the_working_path() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    network.freeze("b");
    client.table.switch_to("b".to_string());
    wait_until(|| {
        client
            .log
            .all()
            .iter()
            .any(|event| matches!(event, SessionEvent::SwitchFailed { .. }))
    })
    .await;
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn write_side_backpressure_is_bounded_and_recovers() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a"], anchor);
    let mut client = connect(&network, &["a"]);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();

    // Nobody reads at the anchor, so the client must eventually stop accepting bytes.
    let total = 4 * 1024 * 1024;
    let writer = tokio::spawn(async move {
        client.app.write_all(&vec![7u8; total]).await.unwrap();
        client
    });
    sleep(Duration::from_secs(5)).await;
    assert!(!writer.is_finished(), "unbounded buffering upstream");

    let mut received = vec![0u8; total + 1];
    timeout(Duration::from_secs(30), server.read_exact(&mut received))
        .await
        .expect("bytes did not resume")
        .unwrap();
    assert!(received[1..].iter().all(|&byte| byte == 7));
    writer.await.unwrap();
}

#[tokio::test(start_paused = true)]
async fn slow_game_client_does_not_look_like_a_dead_path() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a", "b"], anchor);
    let mut client = connect(&network, &["a", "b"]);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();

    // The game stops reading while the server pushes more than one window.
    let writer = tokio::spawn(async move {
        let _ = server.write_all(&vec![1u8; 3 * 1024 * 1024]).await;
        server
    });
    sleep(Duration::from_secs(15)).await;
    assert_eq!(client.log.attached_nodes(), ["a"]);

    let mut received = vec![0u8; 3 * 1024 * 1024];
    timeout(
        Duration::from_secs(30),
        client.app.read_exact(&mut received),
    )
    .await
    .expect("bytes did not resume")
    .unwrap();
    writer.await.unwrap();
    assert_eq!(client.log.attached_nodes(), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn disconnect_message_survives_a_path_failure() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a", "b"], anchor);
    let mut client = connect(&network, &["a", "b"]);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();
    let mut byte = [0u8; 1];
    server.read_exact(&mut byte).await.unwrap();

    // The room says why it is closing while the node carrying that message goes silent.
    network.freeze("a");
    server.write_all("房间未启动".as_bytes()).await.unwrap();
    drop(server);

    let mut message = Vec::new();
    timeout(
        Duration::from_secs(30),
        client.app.read_to_end(&mut message),
    )
    .await
    .expect("disconnect message timed out")
    .unwrap();
    assert_eq!(String::from_utf8(message).unwrap(), "房间未启动");
    wait_until(|| {
        client.log.all().contains(&SessionEvent::Ended {
            reason: EndReason::Closed,
        })
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn closing_the_game_ends_the_session_immediately() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a"], Arc::clone(&anchor));
    let mut client = connect(&network, &["a"]);
    client.app.write_all(b"bye").await.unwrap();
    let mut server = sessions.recv().await.unwrap();
    assert_eq!(anchor.session_count(), 1);

    let started = Instant::now();
    drop(client.app);
    let mut received = Vec::new();
    server.read_to_end(&mut received).await.unwrap();

    assert_eq!(received, b"bye");
    wait_until(|| anchor.session_count() == 0).await;
    assert!(started.elapsed() < Duration::from_secs(1));
}

#[tokio::test(start_paused = true)]
async fn anchor_restart_ends_the_game_connection() {
    let network = Network::new(&["a", "b"], echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    network.replace_anchor(echo_anchor());
    network.kill("a");

    let mut rest = Vec::new();
    timeout(Duration::from_secs(10), client.app.read_to_end(&mut rest))
        .await
        .expect("game connection stayed open")
        .unwrap();
    wait_until(|| {
        client.log.all().contains(&SessionEvent::Ended {
            reason: EndReason::Gone,
        })
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn anchor_restart_waits_for_a_silent_node_then_ends_the_game_connection() {
    let network = Network::new(&["a", "b", "c"], echo_anchor());
    let mut client = connect(&network, &["a", "b", "c"]);
    round_trip(&mut client.app, b"before").await;

    network.replace_anchor(echo_anchor());
    network.freeze("b");
    let killed_at = Instant::now();
    network.kill("a");

    let mut rest = Vec::new();
    timeout(Duration::from_secs(10), client.app.read_to_end(&mut rest))
        .await
        .expect("game connection stayed open")
        .unwrap();
    // The answer from c is not final until b has had its full attempt.
    assert!(killed_at.elapsed() >= Duration::from_secs(2));
    wait_until(|| {
        client.log.all().contains(&SessionEvent::Ended {
            reason: EndReason::Gone,
        })
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn node_on_another_anchor_does_not_end_the_session() {
    let network = Network::new(&["a", "b"], echo_anchor());
    network.route_node_to("b", echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    // b answers at once; the failed node a is tried half a second later and works again.
    network.kill("a");
    network.heal("a");
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a", "a"]);
}

#[tokio::test(start_paused = true)]
async fn manual_switch_to_another_anchor_keeps_the_session() {
    let network = Network::new(&["a", "b"], echo_anchor());
    network.route_node_to("b", echo_anchor());
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    client.table.switch_to("b".to_string());
    wait_until(|| {
        client
            .log
            .all()
            .iter()
            .any(|event| matches!(event, SessionEvent::SwitchFailed { .. }))
    })
    .await;
    round_trip(&mut client.app, b"after").await;

    assert_eq!(client.log.attached_nodes(), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn both_ends_give_up_when_no_path_returns() {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a"], Arc::clone(&anchor));
    let mut client = connect(&network, &["a"]);
    client.app.write_all(b"x").await.unwrap();
    let mut server = sessions.recv().await.unwrap();
    let mut byte = [0u8; 1];
    server.read_exact(&mut byte).await.unwrap();

    let killed_at = Instant::now();
    network.kill("a");

    let mut rest = Vec::new();
    server.read_to_end(&mut rest).await.unwrap();
    let anchor_gave_up = killed_at.elapsed();
    assert!(anchor_gave_up >= Duration::from_secs(19));
    assert!(anchor_gave_up <= Duration::from_secs(21));
    assert_eq!(anchor.session_count(), 0);

    client.app.read_to_end(&mut rest).await.unwrap();
    let client_gave_up = killed_at.elapsed();
    assert!(client_gave_up >= Duration::from_secs(24));
    assert!(
        client_gave_up < Duration::from_secs(30),
        "must beat Minecraft's read timeout"
    );
    assert!(client.log.all().contains(&SessionEvent::Ended {
        reason: EndReason::Expired
    }));
}

#[tokio::test(start_paused = true)]
async fn session_that_never_connects_expires() {
    let network = Network::new(&["a"], echo_anchor());
    network.kill("a");
    let mut client = connect(&network, &["a"]);
    let mut rest = Vec::new();
    let started = Instant::now();
    client.app.read_to_end(&mut rest).await.unwrap();
    assert!(started.elapsed() <= Duration::from_secs(11));
}

#[tokio::test(start_paused = true)]
async fn create_retries_when_the_first_reply_was_lost() {
    // The hello reaches the anchor, the reply does not; the retry has to resume.
    let (anchor, _sessions) = manual_anchor();
    let (mut first, first_anchor) = duplex(1024);
    let token = [9u8; 16];
    let peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
    let handler = Arc::clone(&anchor);
    tokio::spawn(async move { handler.handle_path(Box::new(first_anchor), peer).await });
    write_hello(&mut first, token, false, false, 1).await;
    assert_eq!(read_hello(&mut first).await.status, HelloStatus::Ok);
    drop(first);

    let (mut second, second_anchor) = duplex(1024);
    let handler = Arc::clone(&anchor);
    tokio::spawn(async move { handler.handle_path(Box::new(second_anchor), peer).await });
    write_hello(&mut second, token, false, false, 2).await;
    assert_eq!(
        read_hello(&mut second).await.status,
        HelloStatus::TokenInUse
    );
}

async fn write_hello(
    stream: &mut DuplexStream,
    token: [u8; 16],
    resume: bool,
    probe: bool,
    generation: u32,
) {
    let hello = ClientHello {
        version: VERSION,
        resume,
        probe,
        token,
        generation,
        recv_offset: 0,
    };
    let bytes: [u8; CLIENT_HELLO_LEN] = hello.encode();
    stream.write_all(&bytes).await.unwrap();
}

async fn read_hello(stream: &mut DuplexStream) -> ServerHello {
    let mut bytes = [0u8; SERVER_HELLO_LEN];
    stream.read_exact(&mut bytes).await.unwrap();
    ServerHello::decode(&bytes).unwrap()
}

fn open_path(anchor: &Arc<Anchor>) -> DuplexStream {
    let (ours, theirs) = duplex(64 * 1024);
    let handler = Arc::clone(anchor);
    let peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
    tokio::spawn(async move { handler.handle_path(Box::new(theirs), peer).await });
    ours
}

#[tokio::test(start_paused = true)]
async fn older_generation_cannot_take_the_session_back() {
    let (anchor, _sessions) = manual_anchor();
    let token = [3u8; 16];

    let mut current = open_path(&anchor);
    write_hello(&mut current, token, false, false, 5).await;
    assert_eq!(read_hello(&mut current).await.status, HelloStatus::Ok);

    let mut stale = open_path(&anchor);
    write_hello(&mut stale, token, true, false, 5).await;
    assert_eq!(
        read_hello(&mut stale).await.status,
        HelloStatus::StaleGeneration
    );

    // The rejected connection left the current one attached.
    let mut newer = open_path(&anchor);
    write_hello(&mut newer, token, true, false, 6).await;
    assert_eq!(read_hello(&mut newer).await.status, HelloStatus::Ok);
    let mut rest = Vec::new();
    timeout(Duration::from_secs(1), current.read_to_end(&mut rest))
        .await
        .expect("replaced path was not closed")
        .unwrap();
}

#[tokio::test(start_paused = true)]
async fn probe_has_no_effect_on_the_session() {
    let (anchor, _sessions) = manual_anchor();
    let token = [4u8; 16];

    let mut prober = open_path(&anchor);
    write_hello(&mut prober, token, true, true, 0).await;
    assert_eq!(
        read_hello(&mut prober).await.status,
        HelloStatus::UnknownSession
    );
    write_hello(&mut prober, token, false, true, 0).await;
    assert_eq!(read_hello(&mut prober).await.status, HelloStatus::Ok);
    assert_eq!(anchor.session_count(), 0);

    // A probed connection can go on to attach for real.
    write_hello(&mut prober, token, false, false, 1).await;
    assert_eq!(read_hello(&mut prober).await.status, HelloStatus::Ok);
    assert_eq!(anchor.session_count(), 1);

    let mut other = open_path(&anchor);
    write_hello(&mut other, token, true, true, 0).await;
    assert_eq!(read_hello(&mut other).await.status, HelloStatus::Ok);
    sleep(Duration::from_secs(1)).await;
    prober.write_all(&[0x03, 0, 0, 0, 1]).await.unwrap();
    timeout(Duration::from_secs(1), read_until_pong(&mut prober, 1))
        .await
        .expect("probe took the session from the attached path");
}

/// Reads frames until the pong arrives; an acknowledgement can legitimately come first.
async fn read_until_pong(stream: &mut DuplexStream, nonce: u32) {
    let mut bytes = Vec::new();
    let mut buffer = [0u8; 256];
    loop {
        while let Some((frame, used)) = Frame::decode(&bytes).unwrap() {
            if frame == Frame::Pong(nonce) {
                return;
            }
            bytes.drain(..used);
        }
        let count = stream.read(&mut buffer).await.unwrap();
        assert_ne!(count, 0, "path closed before the pong");
        bytes.extend_from_slice(&buffer[..count]);
    }
}

#[tokio::test(start_paused = true)]
async fn endless_probing_gets_the_connection_closed() {
    let (anchor, _sessions) = manual_anchor();
    let mut prober = open_path(&anchor);
    for _ in 0..4 {
        write_hello(&mut prober, [6u8; 16], false, true, 0).await;
        assert_eq!(read_hello(&mut prober).await.status, HelloStatus::Ok);
    }
    let mut rest = Vec::new();
    timeout(Duration::from_secs(1), prober.read_to_end(&mut rest))
        .await
        .expect("connection stayed open")
        .unwrap();
    assert!(rest.is_empty());
}

#[tokio::test(start_paused = true)]
async fn replay_claiming_unsent_bytes_is_refused() {
    let (anchor, _sessions) = manual_anchor();
    let token = [5u8; 16];
    let mut first = open_path(&anchor);
    write_hello(&mut first, token, false, false, 1).await;
    read_hello(&mut first).await;

    let mut second = open_path(&anchor);
    let hello = ClientHello {
        version: VERSION,
        resume: true,
        probe: false,
        token,
        generation: 2,
        recv_offset: 1_000,
    };
    second.write_all(&hello.encode()).await.unwrap();
    assert_eq!(
        read_hello(&mut second).await.status,
        HelloStatus::InvalidOffset
    );
    wait_until(|| anchor.session_count() == 0).await;
}

#[tokio::test(start_paused = true)]
async fn anchor_reports_a_session_from_start_to_end() {
    let (anchor, events) = observed_anchor();
    let network = Network::new(&["a", "b"], anchor);
    let mut client = connect(&network, &["a", "b"]);
    round_trip(&mut client.app, b"before").await;

    network.freeze("a");
    round_trip(&mut client.app, b"after").await;
    drop(client.app);
    wait_until(|| {
        events
            .lock()
            .unwrap()
            .iter()
            .any(|event| matches!(event, AnchorEvent::Ended { .. }))
    })
    .await;

    let peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
    let recorded = events.lock().unwrap().clone();
    let [
        AnchorEvent::Started { session: 1, peer: started_peer },
        AnchorEvent::Resumed {
            session: 1,
            peer: resumed_peer,
            silent_ms,
            ..
        },
        AnchorEvent::Ended {
            session: 1,
            reason: EndReason::Closed,
            resumes: 1,
            ..
        },
    ] = recorded[..]
    else {
        panic!("unexpected events: {recorded:?}");
    };
    assert_eq!((started_peer, resumed_peer), (peer, peer));
    // The outage lasted until the client noticed it, so about three seconds.
    assert!((1_000..=8_000).contains(&silent_ms), "{silent_ms}");
}

#[tokio::test(start_paused = true)]
async fn anchor_does_not_report_a_rejected_connection_as_a_resume() {
    let (anchor, events) = observed_anchor();
    let token = [5u8; 16];

    let mut current = open_path(&anchor);
    write_hello(&mut current, token, false, false, 5).await;
    assert_eq!(read_hello(&mut current).await.status, HelloStatus::Ok);
    let mut stale = open_path(&anchor);
    write_hello(&mut stale, token, true, false, 5).await;
    assert_eq!(
        read_hello(&mut stale).await.status,
        HelloStatus::StaleGeneration
    );
    let mut unknown = open_path(&anchor);
    write_hello(&mut unknown, [6u8; 16], true, false, 1).await;
    assert_eq!(
        read_hello(&mut unknown).await.status,
        HelloStatus::UnknownSession
    );
    let mut probe = open_path(&anchor);
    write_hello(&mut probe, [6u8; 16], true, true, 0).await;
    assert_eq!(
        read_hello(&mut probe).await.status,
        HelloStatus::UnknownSession
    );

    let peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
    assert_eq!(
        events.lock().unwrap().as_slice(),
        [
            AnchorEvent::Started { session: 1, peer },
            AnchorEvent::UnknownSession { peer },
            AnchorEvent::UnknownSession { peer },
        ]
    );
}

#[tokio::test(start_paused = true)]
async fn full_anchor_refuses_new_sessions() {
    let anchor = Anchor::new(1, Tuning::anchor(), |_app, _peer| {});
    let mut first = open_path(&anchor);
    write_hello(&mut first, [1u8; 16], false, false, 1).await;
    assert_eq!(read_hello(&mut first).await.status, HelloStatus::Ok);
    let mut second = open_path(&anchor);
    write_hello(&mut second, [2u8; 16], false, true, 0).await;
    assert_eq!(read_hello(&mut second).await.status, HelloStatus::Full);
}

fn spawn_writer(
    mut stream: tokio::io::WriteHalf<DuplexStream>,
    seed: u64,
    total: u64,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut rng = Rng(seed | 1);
        let mut offset = 0u64;
        let mut chunk = Vec::new();
        while offset < total {
            let size = (rng.below(32 * 1024) + 1).min(total - offset);
            chunk.clear();
            chunk.extend((0..size).map(|index| pattern_byte(seed, offset + index)));
            stream.write_all(&chunk).await.unwrap();
            offset += size;
            // Mostly paced so faults land mid-transfer, with bursts that fill the window.
            if rng.below(8) != 0 {
                sleep(Duration::from_millis(rng.below(200))).await;
            }
        }
    })
}

fn spawn_reader(
    mut stream: tokio::io::ReadHalf<DuplexStream>,
    seed: u64,
    total: u64,
) -> JoinHandle<u64> {
    tokio::spawn(async move {
        let mut offset = 0u64;
        let mut buffer = vec![0u8; 32 * 1024];
        while offset < total {
            let count = stream.read(&mut buffer).await.unwrap();
            assert_ne!(
                count, 0,
                "stream ended early at {offset} of {total}, seed {seed}"
            );
            for (index, byte) in buffer[..count].iter().enumerate() {
                let position = offset + index as u64;
                assert_eq!(
                    *byte,
                    pattern_byte(seed, position),
                    "wrong byte at {position}, seed {seed}"
                );
            }
            offset += count as u64;
        }
        offset
    })
}

/// Breaks and repairs nodes at random, always leaving one healthy.
async fn run_faults(
    network: Arc<Network>,
    table: rdi_session_resume::client::NodeTable,
    seed: u64,
) {
    let names = ["a", "b", "c"];
    let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
    let mut broken: Option<&str> = None;
    loop {
        sleep(Duration::from_millis(100 + rng.below(4_000))).await;
        if let Some(node) = broken.take() {
            network.heal(node);
        }
        let node = names[rng.below(3) as usize];
        match rng.below(4) {
            0 => {
                network.kill(node);
                broken = Some(node);
            }
            1 => {
                network.freeze(node);
                broken = Some(node);
            }
            2 => {
                network.freeze_up(node);
                broken = Some(node);
            }
            _ => table.switch_to(node.to_string()),
        }
    }
}

async fn run_random_transfer(seed: u64, total: u64) {
    let (anchor, mut sessions) = manual_anchor();
    let network = Network::new(&["a", "b", "c"], Arc::clone(&anchor));
    let client = connect(&network, &["a", "b", "c"]);
    let table = client.table.clone();
    let log = client.log.clone();

    let up_seed = seed.wrapping_add(1);
    let down_seed = seed.wrapping_add(2);
    let (client_read, client_write) = tokio::io::split(client.app);
    let up_writer = spawn_writer(client_write, up_seed, total);
    let server = sessions.recv().await.unwrap();
    let (server_read, server_write) = tokio::io::split(server);
    let down_writer = spawn_writer(server_write, down_seed, total);
    let up_reader = spawn_reader(server_read, up_seed, total);
    let down_reader = spawn_reader(client_read, down_seed, total);

    let faults = tokio::spawn(run_faults(Arc::clone(&network), table, seed));
    assert_eq!(up_reader.await.unwrap(), total, "seed {seed}");
    assert_eq!(down_reader.await.unwrap(), total, "seed {seed}");
    faults.abort();
    up_writer.await.unwrap();
    down_writer.await.unwrap();

    let events = log.all();
    assert!(
        !events.iter().any(|event| matches!(
            event,
            SessionEvent::Ended {
                reason: EndReason::Violation(_) | EndReason::Expired | EndReason::Gone
            }
        )),
        "seed {seed}: {events:?}"
    );
    let attaches = log.attached_nodes().len();
    println!("seed {seed}: {total} bytes each way, {attaches} attaches");
    assert!(attaches >= 2, "seed {seed}: faults never moved the session");
}

#[tokio::test(start_paused = true)]
async fn random_faults_keep_both_streams_byte_exact() {
    for seed in 1..=8 {
        run_random_transfer(seed, 4 * 1024 * 1024).await;
    }
}

/// The full-size run from the plan: `cargo test --release -- --ignored`.
#[tokio::test(start_paused = true)]
#[ignore = "takes minutes in debug builds"]
async fn random_faults_keep_64_mib_byte_exact() {
    for seed in 100..=103 {
        run_random_transfer(seed, 64 * 1024 * 1024).await;
    }
}
