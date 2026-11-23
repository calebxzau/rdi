//! Game rule table and the draft logic of Compose `GameRuleModal`.
//! The table comes from `protocol/fixtures/game_rules.json`, which a Kotlin test writes from
//! `AllGameRules` in display order (categories by Chinese collation, rules by name).
use serde::Deserialize;
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::OnceLock,
};

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ValueType {
    Boolean,
    Integer,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    pub id: String,
    pub name: String,
    pub description: String,
    pub value_type: ValueType,
    /// The default value.
    pub value: String,
}

#[derive(Deserialize)]
pub struct Category {
    pub name: String,
    pub rules: Vec<Rule>,
}

#[derive(Deserialize)]
struct Table {
    categories: Vec<Category>,
}

/// Every category in display order.
pub fn categories() -> &'static [Category] {
    static TABLE: OnceLock<Vec<Category>> = OnceLock::new();
    TABLE.get_or_init(|| {
        match serde_json::from_str::<Table>(include_str!("../../protocol/fixtures/game_rules.json")) {
            Ok(table) => table.categories,
            Err(error) => {
                tracing::error!(%error, "游戏规则表无法读取");
                Vec::new()
            }
        }
    })
}

pub fn rule(id: &str) -> Option<&'static Rule> {
    categories().iter().flat_map(|c| &c.rules).find(|r| r.id == id)
}

/// One rule whose effective value changed between opening and saving.
#[derive(Clone, Debug, PartialEq)]
pub struct Change {
    pub id: String,
    pub old: Option<String>,
    pub new: Option<String>,
}

/// `findChangedGameRules`: effective values compared, booleans without case, integers trimmed.
pub fn changed_rules(opening: &BTreeMap<String, String>, draft: &BTreeMap<String, String>) -> Vec<Change> {
    let effective = |id: &str, rules: &BTreeMap<String, String>| {
        rules.get(id).cloned().or_else(|| rule(id).map(|r| r.value.clone()))
    };
    let mut ids: Vec<&String> = opening.keys().collect();
    ids.extend(draft.keys().filter(|id| !opening.contains_key(*id)));
    ids.into_iter()
        .filter_map(|id| {
            let (old, new) = (effective(id, opening), effective(id, draft));
            let equal = match (&old, &new) {
                (Some(a), Some(b)) if rule(id).is_some_and(|r| r.value_type == ValueType::Boolean) => {
                    a.eq_ignore_ascii_case(b)
                }
                (Some(a), Some(b)) => a.trim() == b.trim(),
                (a, b) => a == b,
            };
            (!equal).then(|| Change { id: id.clone(), old, new })
        })
        .collect()
}

/// `displayValue`: 开启/关闭 for booleans, the default for a missing value.
pub fn display_value(id: &str, value: Option<&str>) -> String {
    let rule = rule(id);
    match (value, rule) {
        (None, None) => "默认值（未知）".into(),
        (None, Some(rule)) => rule.value.clone(),
        (Some(value), Some(rule)) if rule.value_type == ValueType::Boolean => {
            if value.eq_ignore_ascii_case("true") { "开启" } else { "关闭" }.into()
        }
        (Some(value), _) => value.into(),
    }
}

/// The dialog's working copy.
#[derive(Clone, Debug, Default)]
pub struct Draft {
    pub opening: BTreeMap<String, String>,
    pub rules: BTreeMap<String, String>,
    pub invalid: BTreeSet<String>,
    /// The integer field texts the player typed, which may differ from `rules`.
    pub texts: BTreeMap<String, String>,
}

impl Draft {
    pub fn open(overrides: &BTreeMap<String, String>) -> Self {
        Self { opening: overrides.clone(), rules: overrides.clone(), ..Default::default() }
    }
    /// `updateOverride`: a value equal to the default (any case) or blank removes the override.
    pub fn update_override(&mut self, id: &str, value: &str) {
        let Some(rule) = rule(id) else { return };
        let normalized = value.trim();
        if normalized.eq_ignore_ascii_case(&rule.value) || normalized.is_empty() {
            self.rules.remove(id);
        } else {
            self.rules.insert(id.into(), normalized.into());
        }
    }
    pub fn toggle(&mut self, id: &str, checked: bool) {
        self.update_override(id, if checked { "true" } else { "false" });
    }
    /// The integer field's `onValueChange`; returns the text the field shows afterwards.
    pub fn edit_integer(&mut self, id: &str, input: &str) -> String {
        let Some(rule) = rule(id) else { return input.into() };
        if let Ok(parsed) = input.trim().parse::<i32>() {
            self.invalid.remove(id);
            self.update_override(id, &parsed.to_string());
            self.texts.insert(id.into(), input.into());
            input.into()
        } else if input.trim().is_empty() {
            self.invalid.remove(id);
            self.update_override(id, "");
            self.texts.remove(id);
            rule.value.clone()
        } else {
            self.invalid.insert(id.into());
            self.texts.insert(id.into(), input.into());
            input.into()
        }
    }
    /// 重置: every override, invalid mark and typed text is dropped.
    pub fn reset(&mut self) {
        self.rules.clear();
        self.invalid.clear();
        self.texts.clear();
    }
    /// 游戏规则设定（N项未保存）, where N counts the overrides in the draft as Compose does.
    pub fn title(&self) -> String {
        if self.rules.is_empty() {
            "游戏规则设定".into()
        } else {
            format!("游戏规则设定（{}项未保存）", self.rules.len())
        }
    }
    pub fn can_save(&self) -> bool {
        self.invalid.is_empty()
    }
    pub fn value(&self, rule: &Rule) -> String {
        self.rules.get(&rule.id).cloned().unwrap_or_else(|| rule.value.clone())
    }
    pub fn text(&self, rule: &Rule) -> String {
        self.texts.get(&rule.id).cloned().unwrap_or_else(|| self.value(rule))
    }
    /// The confirmation lines: `名称（id）：旧→新`.
    pub fn change_lines(&self) -> Vec<String> {
        changed_rules(&self.opening, &self.rules)
            .into_iter()
            .map(|change| {
                let name = rule(&change.id).map_or("未知规则", |r| r.name.as_str());
                format!(
                    "{name}（{}）：{}→{}",
                    change.id,
                    display_value(&change.id, change.old.as_deref()),
                    display_value(&change.id, change.new.as_deref())
                )
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn map(entries: &[(&str, &str)]) -> BTreeMap<String, String> {
        entries.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    #[test]
    fn table_has_all_kotlin_rules_in_display_order() {
        let count: usize = categories().iter().map(|c| c.rules.len()).sum();
        assert_eq!(count, 60);
        let ids: BTreeSet<_> = categories().iter().flat_map(|c| &c.rules).map(|r| &r.id).collect();
        assert_eq!(ids.len(), 60);
        assert_eq!(rule("keepInventory").unwrap().value, "false");
        assert_eq!(rule("randomTickSpeed").unwrap().value_type, ValueType::Integer);
        for category in categories() {
            let names: Vec<_> = category.rules.iter().map(|r| r.name.encode_utf16().collect::<Vec<_>>()).collect();
            assert!(names.windows(2).all(|w| w[0] <= w[1]), "{}", category.name);
        }
    }

    #[test]
    fn overrides_equal_to_default_or_blank_are_removed() {
        let mut draft = Draft::open(&map(&[("keepInventory", "true")]));
        draft.toggle("keepInventory", false);
        assert!(draft.rules.is_empty());
        draft.update_override("doFireTick", " FALSE ");
        assert_eq!(draft.rules["doFireTick"], "FALSE");
        draft.update_override("doFireTick", "True");
        assert!(draft.rules.is_empty());
        draft.update_override("unknownRule", "x");
        assert!(draft.rules.is_empty());
    }

    #[test]
    fn integer_fields_validate_like_compose() {
        let mut draft = Draft::open(&BTreeMap::new());
        assert_eq!(draft.edit_integer("randomTickSpeed", "10"), "10");
        assert_eq!(draft.rules["randomTickSpeed"], "10");
        assert_eq!(draft.edit_integer("randomTickSpeed", "1x"), "1x");
        assert!(!draft.can_save());
        assert_eq!(draft.rules["randomTickSpeed"], "10", "an invalid text keeps the last value");
        assert_eq!(draft.edit_integer("randomTickSpeed", "  "), "3", "blank shows the default");
        assert!(draft.can_save() && draft.rules.is_empty());
        assert_eq!(draft.edit_integer("spawnRadius", " +07 "), " +07 ");
        assert_eq!(draft.rules["spawnRadius"], "7");
        assert_eq!(draft.edit_integer("spawnRadius", "99999999999"), "99999999999");
        assert!(draft.invalid.contains("spawnRadius"));
        draft.reset();
        assert!(draft.rules.is_empty() && draft.can_save() && draft.texts.is_empty());
    }

    #[test]
    fn change_list_and_display_values() {
        let opening = map(&[("keepInventory", "true"), ("randomTickSpeed", "5")]);
        let mut draft = Draft::open(&opening);
        draft.update_override("randomTickSpeed", "5");
        draft.toggle("keepInventory", false);
        draft.toggle("doFireTick", false);
        assert_eq!(
            draft.change_lines(),
            ["死亡后保留物品栏（keepInventory）：开启→关闭", "火焰蔓延（doFireTick）：开启→关闭"]
        );
        assert_eq!(draft.title(), "游戏规则设定（2项未保存）");
        assert_eq!(
            changed_rules(&map(&[("keepInventory", "TRUE")]), &map(&[("keepInventory", "true")])),
            []
        );
        assert_eq!(
            changed_rules(&map(&[("randomTickSpeed", " 4")]), &map(&[("randomTickSpeed", "4 ")])),
            []
        );
        assert_eq!(display_value("missing", None), "默认值（未知）");
        assert_eq!(display_value("randomTickSpeed", None), "3");
        assert_eq!(display_value("missing", Some("x")), "x");
        assert!(Draft::open(&BTreeMap::new()).change_lines().is_empty());
        assert_eq!(Draft::default().title(), "游戏规则设定");
    }
}
