//! Port of Kotlin `HillCrowdSimulation`. Trig runs in f64 and rounds to f32 like `kotlin.math` on Float.
use super::terrain;
use std::f64::consts::{PI, TAU};

pub const MAX_PLAYERS: usize = 50;
const SUMMIT_SPEED: f64 = 1.6;
const SLOPE_SPEED: f64 = SUMMIT_SPEED * 1.5;
const MAX_STEP_SECONDS: f64 = 0.1;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Node {
    pub x: i32,
    pub z: i32,
    pub height: f32,
}
pub struct Route {
    pub nodes: Vec<Node>,
    pub summit_start: usize,
    pub summit_lap_end: usize,
    pub descent_start: usize,
    arrival: Vec<f64>,
    pub travel_seconds: f64,
    pub headway_seconds: f64,
    pub cycle_seconds: f64,
    pub initial_slot: usize,
}
fn sin(value: f32) -> f32 {
    (value as f64).sin() as f32
}
fn cos(value: f32) -> f32 {
    (value as f64).cos() as f32
}
fn atan2(y: f32, x: f32) -> f32 {
    (y as f64).atan2(x as f64) as f32
}
fn smooth(value: f32) -> f32 {
    let v = value.clamp(0., 1.);
    v * v * (3. - 2. * v)
}
fn angle_delta(from: f32, to: f32) -> f32 {
    atan2(sin(to - from), cos(to - from))
}
impl Route {
    pub fn new() -> Self {
        const APPROACH: [(i32, i32); 2] = [(-24, 12), (-7, 0)];
        const LAP: [(i32, i32); 13] = [
            (-2, -1),
            (1, -1),
            (1, 0),
            (2, 0),
            (2, 2),
            (1, 2),
            (1, 3),
            (-2, 3),
            (-2, 2),
            (-3, 2),
            (-3, 0),
            (-2, 0),
            (-2, -1),
        ];
        let waypoints: Vec<_> = APPROACH
            .iter()
            .chain(&LAP)
            .chain(&[LAP[1], (8, 3), (24, 12)])
            .copied()
            .collect();
        let node = |x, z| Node {
            x,
            z,
            height: terrain::cell_height(x as f64 + 0.5, z as f64 + 0.5, 1) as f32,
        };
        let mut nodes = vec![node(waypoints[0].0, waypoints[0].1)];
        let (mut summit_start, mut summit_lap_end, mut descent_start) = (0, 0, 0);
        for (leg, pair) in waypoints.windows(2).enumerate() {
            let (mut x, mut z) = pair[0];
            let end = pair[1];
            let dx = (end.0 - x).abs();
            let dz = (end.1 - z).abs();
            let (mut moved_x, mut moved_z) = (0, 0);
            // Interleaved cardinal steps never cut diagonally through a higher neighboring column.
            while x != end.0 || z != end.1 {
                if x != end.0
                    && (z == end.1
                        || (moved_x as f64 + 0.5) * dz as f64 <= (moved_z as f64 + 0.5) * dx as f64)
                {
                    x += (end.0 - x).signum();
                    moved_x += 1;
                } else {
                    z += (end.1 - z).signum();
                    moved_z += 1;
                }
                nodes.push(node(x, z));
            }
            let arrived = leg + 1;
            if arrived == APPROACH.len() {
                summit_start = nodes.len() - 1;
            } else if arrived == APPROACH.len() + LAP.len() - 1 {
                summit_lap_end = nodes.len() - 1;
            } else if arrived == APPROACH.len() + LAP.len() {
                descent_start = nodes.len() - 1;
            }
        }
        let speed = |index: usize| {
            if (summit_start..descent_start).contains(&index) {
                SUMMIT_SPEED
            } else {
                SLOPE_SPEED
            }
        };
        let mut arrival = vec![0.; nodes.len()];
        for index in 0..nodes.len() - 1 {
            arrival[index + 1] = arrival[index] + 1. / speed(index);
        }
        let travel_seconds = arrival[nodes.len() - 1];
        let lap_seconds = arrival[summit_lap_end] - arrival[summit_start];
        // A lap lasts an integer plus half a queue interval, so the second crossing falls between newcomers.
        let headway_seconds = lap_seconds / ((lap_seconds / 1.8).floor() + 0.5);
        let cycle_seconds =
            (MAX_PLAYERS as f64).max((travel_seconds / headway_seconds).ceil()) * headway_seconds;
        let initial_slot = (arrival[summit_start] / headway_seconds).ceil() as usize;
        Self {
            nodes,
            summit_start,
            summit_lap_end,
            descent_start,
            arrival,
            travel_seconds,
            headway_seconds,
            cycle_seconds,
            initial_slot,
        }
    }
    pub fn length(&self) -> f64 {
        (self.nodes.len() - 1) as f64
    }
    fn segment_speed(&self, index: usize) -> f64 {
        if (self.summit_start..self.descent_start).contains(&index) {
            SUMMIT_SPEED
        } else {
            SLOPE_SPEED
        }
    }
    fn wrap_time(&self, seconds: f64) -> f64 {
        let wrapped = seconds % self.cycle_seconds;
        if wrapped < 1e-9 || self.cycle_seconds - wrapped < 1e-9 {
            0.
        } else if (wrapped - self.travel_seconds).abs() < 1e-9 {
            self.travel_seconds
        } else {
            wrapped
        }
    }
    fn distance_at_time(&self, seconds: f64) -> f64 {
        let time = seconds.clamp(0., self.travel_seconds);
        let after = self.arrival.partition_point(|&arrival| arrival < time);
        if self.arrival.get(after) == Some(&time) {
            return after as f64;
        }
        let index = after.saturating_sub(1).min(self.nodes.len() - 2);
        index as f64 + (time - self.arrival[index]) * self.segment_speed(index)
    }
    fn wrap_distance(&self, distance: f64) -> f64 {
        let length = self.length();
        let remainder = ((distance % length) + length) % length;
        // Equal elapsed time split into different frame sizes can land either side of an integer.
        if (remainder - remainder.round()).abs() < 1e-9 {
            remainder.round() % length
        } else {
            remainder
        }
    }
    fn heading(&self, index: usize) -> f32 {
        atan2(
            (self.nodes[index + 1].x - self.nodes[index].x) as f32,
            (self.nodes[index + 1].z - self.nodes[index].z) as f32,
        )
    }
    fn pose(&self, distance: f64, runner: &mut Runner) {
        let wrapped = self.wrap_distance(distance);
        let index = wrapped.floor() as usize;
        let t = (wrapped - index as f64) as f32;
        let a = self.nodes[index];
        let b = self.nodes[index + 1];
        let rise = b.height - a.height;
        // Lift before crossing a riser; on descent, clear the edge before dropping.
        let travel = if rise > 0. {
            smooth((t - 0.3) / 0.7)
        } else if rise < 0. {
            smooth(t / 0.7)
        } else {
            t
        };
        let lift = if rise > 0. {
            smooth(t / 0.3)
        } else if rise < 0. {
            smooth((t - 0.7) / 0.3)
        } else {
            t
        };
        runner.x = a.x as f32 + 0.5 + (b.x - a.x) as f32 * travel;
        runner.z = a.z as f32 + 0.5 + (b.z - a.z) as f32 * travel;
        runner.y = a.height
            + rise * lift
            + sin(runner.phase).abs() * 0.06
            + if rise != 0. {
                sin(t * PI as f32) * 0.12
            } else {
                0.
            };
        let current = self.heading(index);
        // Begin turning before a corner and finish just after it, rather than snapping 90 degrees.
        runner.yaw = if t < 0.5 {
            let previous = self.heading(index.saturating_sub(1));
            current + angle_delta(current, previous) * (0.5 - t)
        } else {
            let next = self.heading((index + 1).min(self.nodes.len() - 2));
            current + angle_delta(current, next) * (t - 0.5)
        };
        runner.shadow_x = runner.x.floor() + 0.5;
        runner.shadow_z = runner.z.floor() + 0.5;
        runner.shadow_y = if runner.x.floor() as i32 == a.x && runner.z.floor() as i32 == a.z {
            a.height
        } else {
            b.height
        };
    }
}
impl Default for Route {
    fn default() -> Self {
        Self::new()
    }
}
/// Java `String.hashCode`: Kotlin derives each runner's stride offset from it.
pub fn java_hash(value: &str) -> i32 {
    value.encode_utf16().fold(0i32, |hash, unit| {
        hash.wrapping_mul(31).wrapping_add(unit as i32)
    })
}
#[derive(Clone, Debug)]
pub struct Runner {
    pub id: String,
    pub slot: usize,
    stride_offset: f64,
    pub visible: bool,
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub yaw: f32,
    pub phase: f32,
    pub shadow_x: f32,
    pub shadow_y: f32,
    pub shadow_z: f32,
}
pub struct Simulation {
    pub route: Route,
    pub runners: Vec<Runner>,
    elapsed: f64,
}
impl Simulation {
    pub fn new() -> Self {
        Self {
            route: Route::new(),
            runners: Vec::new(),
            elapsed: 0.,
        }
    }
    /// Existing identities keep their slot and order; new ones fill the spread-out free slots.
    pub fn set_players(&mut self, ids: &[&str]) {
        let mut wanted: Vec<&str> = Vec::with_capacity(ids.len().min(MAX_PLAYERS));
        for &id in ids {
            if wanted.len() < MAX_PLAYERS && !wanted.contains(&id) {
                wanted.push(id);
            }
        }
        self.runners.retain(|r| wanted.contains(&r.id.as_str()));
        for id in wanted {
            if self.runners.iter().any(|r| r.id == id) {
                continue;
            }
            let slot = (0..MAX_PLAYERS)
                .map(|i| (self.route.initial_slot + i * 17) % MAX_PLAYERS)
                .find(|slot| self.runners.iter().all(|r| r.slot != *slot))
                .expect("at most MAX_PLAYERS identities are retained");
            let mut runner = Runner {
                id: id.to_owned(),
                slot,
                stride_offset: (java_hash(id) & 255) as f64 * 0.024,
                visible: true,
                x: 0.,
                y: 0.,
                z: 0.,
                yaw: 0.,
                phase: 0.,
                shadow_x: 0.,
                shadow_y: 0.,
                shadow_z: 0.,
            };
            Self::update_pose(&self.route, self.elapsed, &mut runner);
            self.runners.push(runner);
        }
    }
    pub fn advance(&mut self, delta: f64) {
        if !delta.is_finite() || delta <= 0. {
            return;
        }
        self.elapsed = (self.elapsed + delta.min(MAX_STEP_SECONDS)) % self.route.cycle_seconds;
        for runner in &mut self.runners {
            Self::update_pose(&self.route, self.elapsed, runner);
        }
    }
    fn update_pose(route: &Route, elapsed: f64, runner: &mut Runner) {
        let seconds = route.wrap_time(elapsed + runner.slot as f64 * route.headway_seconds);
        runner.visible = seconds < route.travel_seconds;
        if !runner.visible {
            return;
        }
        let position = route.wrap_distance(route.distance_at_time(seconds));
        runner.phase = ((position * 4. + runner.stride_offset) % TAU) as f32;
        route.pose(position, runner);
    }
}
impl Default for Simulation {
    fn default() -> Self {
        Self::new()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    const REFERENCE: &str = include_str!("hill_crowd_reference.json");
    fn close(actual: f64, expected: &Value, tolerance: f64, label: &str) {
        let expected = expected.as_f64().unwrap();
        assert!(
            (actual - expected).abs() <= tolerance,
            "{label}: {actual} vs {expected}"
        );
    }
    #[test]
    fn route_constants_and_nodes_match_kotlin() {
        let reference: Value = serde_json::from_str(REFERENCE).unwrap();
        let route = Route::new();
        let expected = reference["route"]["nodes"].as_array().unwrap();
        assert_eq!(route.nodes.len(), expected.len());
        for (node, value) in route.nodes.iter().zip(expected) {
            assert_eq!(node.x as i64, value[0].as_i64().unwrap());
            assert_eq!(node.z as i64, value[1].as_i64().unwrap());
            assert_eq!(node.height as f64, value[2].as_f64().unwrap());
        }
        let r = &reference["route"];
        assert_eq!(
            route.summit_start as u64,
            r["summitStart"].as_u64().unwrap()
        );
        assert_eq!(
            route.summit_lap_end as u64,
            r["summitLapEnd"].as_u64().unwrap()
        );
        assert_eq!(
            route.descent_start as u64,
            r["descentStart"].as_u64().unwrap()
        );
        assert_eq!(
            route.initial_slot as u64,
            r["initialSlot"].as_u64().unwrap()
        );
        close(route.travel_seconds, &r["travel"], 1e-9, "travel");
        close(route.headway_seconds, &r["headway"], 1e-9, "headway");
        close(route.cycle_seconds, &r["cycle"], 1e-9, "cycle");
    }
    #[test]
    fn stepped_poses_match_kotlin() {
        let reference: Value = serde_json::from_str(REFERENCE).unwrap();
        let ids: Vec<String> = (0..MAX_PLAYERS).map(|i| format!("preview-{i}")).collect();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        let mut simulation = Simulation::new();
        simulation.set_players(&ids);
        let (mut previous, mut simulated) = (0f64, 0f64);
        for capture in reference["captures"].as_array().unwrap() {
            let time = capture["time"].as_f64().unwrap();
            while simulated < time {
                simulated = time.min(simulated + 1. / 60.);
                simulation.advance(simulated - previous);
                previous = simulated;
            }
            let expected = capture["runners"].as_array().unwrap();
            assert_eq!(simulation.runners.len(), expected.len());
            for (runner, value) in simulation.runners.iter().zip(expected) {
                let label = format!("{} at {time}", runner.id);
                assert_eq!(runner.id, value["id"].as_str().unwrap(), "{label}");
                assert_eq!(
                    runner.slot as u64,
                    value["slot"].as_u64().unwrap(),
                    "{label}"
                );
                assert_eq!(
                    runner.visible,
                    value["visible"].as_bool().unwrap(),
                    "{label}"
                );
                if !runner.visible {
                    continue;
                }
                for (actual, key) in [
                    (runner.x, "x"),
                    (runner.y, "y"),
                    (runner.z, "z"),
                    (runner.yaw, "yaw"),
                    (runner.phase, "phase"),
                    (runner.shadow_x, "shadowX"),
                    (runner.shadow_y, "shadowY"),
                    (runner.shadow_z, "shadowZ"),
                ] {
                    close(actual as f64, &value[key], 1e-4, &format!("{label} {key}"));
                }
            }
        }
    }
    #[test]
    fn route_is_walkable_and_avoids_boulders() {
        let route = Route::new();
        assert!(route.nodes[0].x < -15 && route.nodes.last().unwrap().x > 15);
        for pair in route.nodes.windows(2) {
            assert_eq!(
                (pair[1].x - pair[0].x).abs() + (pair[1].z - pair[0].z).abs(),
                1
            );
            assert!((pair[1].height - pair[0].height).abs() <= 2.);
        }
        for node in &route.nodes {
            assert!(![(4, 2), (-5, 1)].contains(&(node.x, node.z)));
        }
        let lap = &route.nodes[route.summit_start..=route.summit_lap_end];
        assert_eq!(lap[0], lap[lap.len() - 1]);
        assert!(route.descent_start > route.summit_lap_end);
    }
    #[test]
    fn slots_are_stable_when_identities_arrive_and_leave() {
        let mut simulation = Simulation::new();
        simulation.set_players(&["builtin:steve"]);
        let first = simulation.route.initial_slot;
        assert_eq!(simulation.runners[0].slot, first);
        simulation.set_players(&["a"]);
        assert_eq!(
            simulation.runners[0].slot, first,
            "the freed slot is reused"
        );
        simulation.set_players(&["a", "b"]);
        assert_eq!(simulation.runners[0].slot, first);
        assert_eq!(simulation.runners[1].slot, (first + 17) % MAX_PLAYERS);
        simulation.set_players(&["a", "b", "b", "a"]);
        assert_eq!(simulation.runners.len(), 2);
        let many: Vec<String> = (0..80).map(|i| format!("skin-{i}")).collect();
        let many: Vec<&str> = many.iter().map(String::as_str).collect();
        simulation.set_players(&many);
        assert_eq!(simulation.runners.len(), MAX_PLAYERS);
        let mut slots: Vec<_> = simulation.runners.iter().map(|r| r.slot).collect();
        slots.sort_unstable();
        slots.dedup();
        assert_eq!(slots.len(), MAX_PLAYERS);
    }
    #[test]
    fn advancing_ignores_invalid_time_and_caps_each_step() {
        let mut simulation = Simulation::new();
        simulation.set_players(&["a"]);
        let start = simulation.elapsed;
        for delta in [0., -1., f64::NAN, f64::INFINITY] {
            simulation.advance(delta);
            assert_eq!(simulation.elapsed, start);
        }
        simulation.advance(30.);
        assert!((simulation.elapsed - start - 0.1).abs() < 1e-12);
    }
    #[test]
    fn java_string_hash_matches_known_values() {
        assert_eq!(java_hash(""), 0);
        assert_eq!(java_hash("abc"), 96354);
        assert_eq!(
            java_hash("builtin:steve") & 255,
            java_hash("builtin:steve") as u8 as i32
        );
        assert_eq!(java_hash("polygenelubricants"), i32::MIN);
    }
}
