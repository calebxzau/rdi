#[derive(Clone, Copy, Default)]
pub struct Spring {
    pub value: f32,
    pub velocity: f32,
    pub target: f32,
    pub stiffness: f32,
    pub damping: f32,
}
impl Spring {
    pub fn target(&mut self, target: f32, stiffness: f32, damping: f32) {
        self.target = target;
        self.stiffness = stiffness;
        self.damping = damping;
    }
    pub fn step(&mut self, dt: f32) -> bool {
        let steps = (dt.clamp(0.0, 0.1) * 1000.0).ceil().max(1.0) as usize;
        let h = dt.clamp(0.0, 0.1) / steps as f32;
        for _ in 0..steps {
            self.velocity += (self.stiffness * (self.target - self.value)
                - 2.0 * self.damping * self.stiffness.sqrt() * self.velocity)
                * h;
            self.value += self.velocity * h;
        }
        let active = (self.value - self.target).abs() > 0.0001 || self.velocity.abs() > 0.001;
        if !active {
            self.value = self.target;
            self.velocity = 0.0;
        }
        active
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn critical_converges_without_overshoot() {
        for k in [1500.0, 10000.0] {
            let mut s = Spring::default();
            s.target(1.0, k, 1.0);
            for _ in 0..240 {
                s.step(1.0 / 120.0);
                assert!(s.value <= 1.0);
            }
            assert_eq!(s.value, 1.0);
        }
    }
    #[test]
    fn release_overshoots_then_settles() {
        let mut s = Spring {
            value: 1.0,
            ..Default::default()
        };
        s.target(0.0, 400.0, 0.55);
        let mut crossed = false;
        for _ in 0..600 {
            s.step(1.0 / 120.0);
            crossed |= s.value < 0.0;
        }
        assert!(crossed);
        assert_eq!(s.value, 0.0);
    }
}
