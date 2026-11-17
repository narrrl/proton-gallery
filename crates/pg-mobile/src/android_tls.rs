//! Initialize rustls' Android trust verifier with the application ClassLoader.
//! This is the sole JNI export; all gallery APIs use generated UniFFI bindings.
use jni::{
    JNIEnv,
    objects::JObject,
    sys::{JNI_FALSE, JNI_TRUE, jboolean},
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_narl_protongallery_PlatformTls_initialize(
    mut env: JNIEnv,
    _receiver: JObject,
    context: JObject,
) -> jboolean {
    // Route Rust log records (and tracing events, via its "log" feature) to
    // logcat and to the in-app connection report.
    crate::diagnostics::install();
    let ready = match rustls_platform_verifier::android::init_with_env(&mut env, context) {
        Ok(()) => true,
        Err(error) => {
            log::error!("certificate verifier init: {error}");
            false
        }
    };
    crate::diagnostics::set_verifier_ready(ready);
    if ready { JNI_TRUE } else { JNI_FALSE }
}
