//! Screen state uses the existing installerSnapshot shape; no transport or disk I/O.
use super::{
    installer::Request,
    scanner::{Candidate, RUNTIMES, version_entry, version_label},
};
use anyhow::{Context, Result};
use serde_json::{Value, json};
use std::path::PathBuf;
#[derive(Default)]
pub struct State {
    pub value: Value,
    pub archive: Option<std::sync::Arc<super::archive::Preview>>,
    pub optional: std::collections::BTreeSet<String>,
}
impl State {
    pub fn open() -> Self {
        Self {
            archive: None,
            optional: Default::default(),
            value: json!({"scanning":true,"archiveLoading":false,"directoryLoading":false,"error":"","candidates":[],"pickedCandidates":[],"runtimes":RUNTIMES.map(|(v,l,label)|json!({"mcVersion":v,"modLoader":l,"label":label}))}),
        }
    }
    pub fn candidates(&mut self, candidates: Vec<Candidate>) {
        self.value["candidates"] = json!(candidates);
        self.value["scanning"] = json!(false);
    }
    fn editor(&mut self, source: &str, path: &str, name: &str, v: &str, l: &str, selectable: bool) {
        self.archive = None;
        self.optional.clear();
        self.value["editor"] = json!({"name":name,"comment":"","iconUrl":"","mcVersion":v,"modLoader":l,"source":source,"sourceDirectory":path,"archivePath":"","versionSelectable":selectable,"canSubmit":true,"submitting":false,"previewLoading":false,"resolving":false,"confirmMissingFiles":false,"progress":"","progressFraction":-1,"includeSaves":false,"contents":[],"missingFiles":[]});
        self.value["error"] = json!("");
        self.value["pickedCandidates"] = json!([]);
    }
    fn select(&mut self, c: Value) -> Result<()> {
        let version = version_entry(c["mcVersion"].as_str().context("请选择要导入的版本")?)?;
        self.editor(
            "directory",
            c["gameDirectory"].as_str().unwrap_or_default(),
            c["name"].as_str().unwrap_or_default(),
            version,
            c["modLoader"].as_str().unwrap_or_default(),
            false,
        );
        Ok(())
    }
    pub fn directory(&mut self, path: PathBuf, result: Result<Vec<Candidate>>) {
        self.value["directoryLoading"] = json!(false);
        match result {
            Ok(items) if items.len() == 1 => {
                if let Err(error) = self.select(json!(&items[0])) {
                    self.error(error);
                }
            }
            Ok(items) if !items.is_empty() => self.value["pickedCandidates"] = json!(items),
            Ok(_) if path.join("mods").is_dir() => self.editor(
                "directory",
                &path.to_string_lossy(),
                &path.file_name().unwrap_or_default().to_string_lossy(),
                RUNTIMES[0].0,
                RUNTIMES[0].1,
                true,
            ),
            Ok(_) => {
                self.value["error"] = json!(
                    "这个文件夹里没有找到1.20.1或1.21.1的整合包（需要包含mods文件夹或版本文件）"
                )
            }
            Err(error) => self.error(error),
        }
    }
    pub fn error(&mut self, error: anyhow::Error) {
        tracing::warn!(%error,"导入整合包操作失败");
        self.value["error"] = json!(error.root_cause().to_string());
    }
    pub fn action(&mut self, action: &str, value: &str, index: usize) -> Result<()> {
        match action {
            "candidate" | "pickedCandidate" => {
                let key = if action == "candidate" {
                    "candidates"
                } else {
                    "pickedCandidates"
                };
                self.select(
                    self.value[key]
                        .get(index)
                        .cloned()
                        .context("请选择要导入的版本")?,
                )?;
            }
            "create" => {
                let (v, l, _) = RUNTIMES.get(index).context("请选择游戏版本")?;
                self.editor("empty", "", version_label(v), v, l, false);
            }
            "name" | "comment" | "icon" => {
                if self.value["editor"].is_object() {
                    self.value["editor"][if action == "icon" { "iconUrl" } else { action }] =
                        json!(value);
                    self.value["error"] = json!("");
                }
            }
            "runtime" if self.value["editor"]["versionSelectable"] == true => {
                let (v, l, _) = RUNTIMES.get(index).context("请选择游戏版本")?;
                self.value["editor"]["mcVersion"] = json!(v);
                self.value["editor"]["modLoader"] = json!(l);
                self.value["error"] = json!("");
            }
            "includeSaves" if self.value["editor"]["source"] == "directory" => {
                self.value["editor"]["includeSaves"] =
                    json!(self.value["editor"]["includeSaves"] != true)
            }
            "back" => {
                self.archive = None;
                self.optional.clear();
                self.value.as_object_mut().unwrap().remove("editor");
            }
            "dismissCandidates" => self.value["pickedCandidates"] = json!([]),
            "dismissError" => self.value["error"] = json!(""),
            "dismissMissing" => self.value["editor"]["confirmMissingFiles"] = json!(false),
            "optional" => {
                if let Some(a) = &self.archive
                    && a.files.iter().any(|f| f.key == value && !f.required)
                {
                    if !self.optional.remove(value) {
                        self.optional.insert(value.into());
                    }
                    self.value["editor"]["contents"] = json!(a.contents(&self.optional));
                }
            }
            "runtime" | "includeSaves" => {}
            _ => anyhow::bail!("不支持的导入操作"),
        }
        Ok(())
    }
    pub fn preview(&mut self, preview: super::archive::Preview) {
        self.editor(
            "archive",
            "",
            &preview.name,
            &preview.version,
            &preview.loader,
            false,
        );
        self.value["archiveLoading"] = json!(false);
        self.value["editor"]["archiveFormat"] = json!(preview.format);
        self.value["editor"]["archivePath"] = json!(preview.path.to_string_lossy());
        self.value["editor"]["comment"] = json!(preview.summary);
        self.value["editor"]["contents"] = json!(preview.contents(&self.optional));
        self.value["editor"]["missingFiles"] = json!(preview.unavailable);
        self.archive = Some(std::sync::Arc::new(preview));
    }
    pub fn request(&self) -> Result<Request> {
        let e = &self.value["editor"];
        anyhow::ensure!(e["canSubmit"] == true, "整合包尚未准备好导入");
        let text = |key: &str| e[key].as_str().unwrap_or_default().to_owned();
        let request = Request {
            archive: None,
            source: if e["source"] == "directory" {
                Some(PathBuf::from(text("sourceDirectory")))
            } else {
                None
            },
            include_saves: e["includeSaves"] == true,
            version: text("mcVersion"),
            loader: text("modLoader"),
            name: text("name"),
            comment: text("comment"),
            icon: text("iconUrl"),
        };
        request.validate()?;
        Ok(request)
    }
}
