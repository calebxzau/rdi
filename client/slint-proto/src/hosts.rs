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
        self.merge(values);
    }
    pub fn shown(&self, others: bool, all: bool) -> Vec<HostBrief> {
        let mut v: Vec<_> = self
            .items
            .values()
            .rev()
            .filter(|h| others || all || h.playable)
            .cloned()
            .collect();
        v.sort_by_key(|h| !(all || h.playable));
        v
    }
}
pub fn cell(index: usize) -> (usize, usize) {
    (index / ROWS, index % ROWS)
}
pub fn columns(scroll: f32, width: f32, count: usize, margin: usize) -> std::ops::Range<usize> {
    let total = count.div_ceil(ROWS);
    let first = (((-scroll - PADDING).max(0.0) / PITCH).floor() as usize)
        .saturating_sub(margin)
        .min(total);
    let last =
        ((((-scroll + width - PADDING).max(0.0) / PITCH).floor() as usize) + 1 + margin).min(total);
    first..last.max(first)
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
        h.page(vec![host("02", true)]);
        let ids = |v: Vec<HostBrief>| v.into_iter().map(|h| h.id).collect::<Vec<_>>();
        assert_eq!(ids(h.shown(true, false)), ["02", "01", "03"]);
        assert_eq!(ids(h.shown(false, false)), ["02", "01"]);
        assert_eq!(ids(h.shown(false, true)), ["03", "02", "01"]);
        assert!(!h.ended);
        h.page(vec![]);
        assert!(h.ended);
    }
    #[test]
    fn grid_bounds() {
        assert_eq!(cell(4), (1, 1));
        assert_eq!(columns(0.0, 1280.0, 100, 0), 0..10);
        assert_eq!(columns(-1380.0, 500.0, 100, 2), 7..16);
        assert_eq!(columns(0.0, 1280.0, 0, 2), 0..0);
    }
}
