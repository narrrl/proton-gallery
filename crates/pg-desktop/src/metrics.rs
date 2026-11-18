//! Opt-in CPU widget construction timing. Excludes GPU submission/presentation.
use std::time::Instant;

pub struct ViewTiming(Option<Instant>);
impl ViewTiming {
    pub fn start() -> Self {
        static ENABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
        Self(
            ENABLED
                .get_or_init(|| std::env::var_os("PG_METRICS").is_some())
                .then(Instant::now),
        )
    }
}
impl Drop for ViewTiming {
    fn drop(&mut self) {
        if let Some(start) = self.0 {
            eprintln!("PG_METRICS view_us={}", start.elapsed().as_micros());
        }
    }
}
