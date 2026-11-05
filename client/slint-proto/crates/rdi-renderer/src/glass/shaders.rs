//! WGSL sources. WGSL has no includes, so the shared files are prepended with `concat!`.
pub const BACKGROUND: &str = concat!(include_str!("common.wgsl"), include_str!("background.wgsl"));
pub const BLUR: &str = concat!(include_str!("common.wgsl"), include_str!("blur.wgsl"));
pub const GRID: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("liquid.wgsl"),
    include_str!("grid.wgsl")
);
pub const PILL: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("liquid.wgsl"),
    include_str!("pill.wgsl")
);
pub const HAZE: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("haze_shape.wgsl"),
    include_str!("haze_fixed.wgsl")
);
pub const RIM: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("haze_shape.wgsl"),
    include_str!("rim.wgsl")
);

#[cfg(test)]
pub mod tests {
    use super::*;
    /// The full Haze port, kept as the reference that `HAZE` must render the same as.
    pub const HAZE_GENERIC: &str = concat!(
        include_str!("common.wgsl"),
        include_str!("haze_shape.wgsl"),
        include_str!("haze.wgsl")
    );
    pub const ALL: [(&str, &str); 7] = [
        ("background", BACKGROUND),
        ("blur", BLUR),
        ("grid", GRID),
        ("pill", PILL),
        ("haze", HAZE),
        ("haze generic", HAZE_GENERIC),
        ("rim", RIM),
    ];
    #[test]
    fn fixed_haze_keeps_the_generic_uniform_layout() {
        assert_eq!(params_layout(HAZE), params_layout(HAZE_GENERIC));
    }
    pub fn module(source: &str) -> naga::Module {
        naga::front::wgsl::parse_str(source)
            .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)))
    }
    /// Member offsets and size of the shader's `Params` uniform struct.
    pub fn params_layout(source: &str) -> (Vec<u32>, u32) {
        let module = module(source);
        for (_, ty) in module.types.iter() {
            if ty.name.as_deref() == Some("Params")
                && let naga::TypeInner::Struct { members, span } = &ty.inner
            {
                return (members.iter().map(|m| m.offset).collect(), *span);
            }
        }
        panic!("no Params struct");
    }
    #[test]
    fn shaders_validate() {
        for (name, source) in ALL {
            let module = module(source);
            naga::valid::Validator::new(
                naga::valid::ValidationFlags::all(),
                naga::valid::Capabilities::empty(),
            )
            .validate(&module)
            .unwrap_or_else(|e| panic!("{name}: {}", e.emit_to_string(source)));
        }
    }
}
