use crate::api::model::HostBrief;
use std::collections::BTreeMap;
pub const ROWS: usize = 3;
pub const TILE: f32 = 126.0;
pub const PITCH: f32 = 138.0;
pub const PADDING: f32 = 16.0;
pub const HEIGHT: f32 = 434.0;
#[derive(Default)]
pub struct Hosts {
    pub items: BTreeMap<String, HostBrief>,
    pub next_page: usize,
    pub ended: bool,
}
impl Hosts {
    pub fn merge(&mut self, values: Vec<HostBrief>) {
        for h in values {
            self.items.insert(h.id.clone(), h);
        }
    }
    pub fn page(&mut self, values: Vec<HostBrief>) {
        self.ended = values.is_empty();
        self.next_page += 1;
        // The legacy paginated endpoint returns the rooms outside /host/my.
        for mut host in values {
            host.playable = false;
            self.items.entry(host.id.clone()).or_insert(host);
        }
    }
    pub fn shown(&self, include_other_rooms: bool) -> Vec<&HostBrief> {
        let mut v: Vec<_> = self
            .items
            .values()
            .rev()
            .filter(|h| include_other_rooms || h.playable)
            .collect();
        v.sort_by_key(|h| !h.playable);
        v
    }
}
pub fn visible_rows(
    scroll: f32,
    height: f32,
    pitch: f32,
    count: usize,
) -> std::ops::Range<usize> {
    let max_offset = (count as f32 * pitch + 2.0 * PADDING - height).max(0.0);
    let top = (-scroll).clamp(0.0, max_offset) - PADDING;
    let first = (top / pitch).floor().max(0.0) as usize;
    let end = ((top + height) / pitch).ceil().max(0.0) as usize;
    first.min(count)..end.min(count)
}
pub fn grid_metrics() -> rdi_renderer::glass::GridMetrics {
    rdi_renderer::glass::GridMetrics {
        tile: TILE,
        pitch: PITCH,
        padding: PADDING,
        rows: ROWS,
        height: HEIGHT,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn host(id: &str, p: bool) -> HostBrief {
        serde_json::from_value(
            serde_json::json!({"_id":id,"name":id,"modpackName":"m","playable":p}),
        )
        .unwrap()
    }
    #[test]
    fn merging_order_filter_and_end() {
        let mut h = Hosts::default();
        h.merge(vec![host("01", true), host("03", false), host("02", true)]);
        h.page(vec![host("02", false), host("04", true)]);
        let ids = |v: Vec<&HostBrief>| v.into_iter().map(|h| h.id.clone()).collect::<Vec<_>>();
        assert_eq!(ids(h.shown(true)), ["02", "01", "04", "03"]);
        assert_eq!(ids(h.shown(false)), ["02", "01"]);
        assert_eq!(h.next_page, 1);
        assert!(!h.ended);
        h.page(vec![]);
        assert!(h.ended);
    }
    #[test]
    fn visible_rows_follow_vertical_scroll_and_clamp_after_resize() {
        assert_eq!(visible_rows(0.0, 434.0, PITCH, 10), 0..4);
        assert_eq!(visible_rows(-154.0, 292.0, PITCH, 10), 1..4);
        assert_eq!(visible_rows(-1380.0, 292.0, PITCH, 3), 1..3);
        assert_eq!(visible_rows(0.0, 434.0, PITCH, 0), 0..0);
    }
}
