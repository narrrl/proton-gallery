use crate::gallery::Message;
use iced::Task;
use proton_gallery_app_rs::Generation;

#[cfg(target_os = "linux")]
pub fn authenticate(request: u64, generation: Generation) -> Task<Message> {
    use proton_gallery_app_rs::desktop_auth::Subject;

    Task::perform(
        async {
            let subject = Subject::new(std::process::id(), rustix::process::getuid().as_raw())
                .map_err(|error| error.to_string())?;
            subject
                .authenticate("io.narl.proton-gallery.unlock-hidden-albums")
                .await
                .map_err(|error| error.to_string())
        },
        move |result| Message::HiddenUnlocked(request, generation, result),
    )
}

#[cfg(target_os = "windows")]
pub fn authenticate(request: u64, generation: Generation) -> Task<Message> {
    use raw_window_handle::RawWindowHandle;

    iced::window::oldest().then(move |window| {
        let Some(window) = window else {
            return Task::done(Message::HiddenUnlocked(
                request,
                generation,
                Err(unavailable()),
            ));
        };
        iced::window::run(window, |window| {
            match window.window_handle().ok()?.as_raw() {
                RawWindowHandle::Win32(handle) => Some(handle.hwnd.get()),
                _ => None,
            }
        })
        .then(move |hwnd| {
            Task::perform(
                async move {
                    let hwnd = hwnd.ok_or_else(unavailable)?;
                    tokio::task::spawn_blocking(move || verify_windows_hello(hwnd))
                        .await
                        .map_err(|_| unavailable())?
                },
                move |result| Message::HiddenUnlocked(request, generation, result),
            )
        })
    })
}

#[cfg(target_os = "windows")]
fn unavailable() -> String {
    "Windows Hello is unavailable. Set it up in Windows sign-in options.".into()
}

#[cfg(target_os = "windows")]
#[allow(unsafe_code)]
fn verify_windows_hello(hwnd: isize) -> Result<(), String> {
    use windows::Security::Credentials::UI::{UserConsentVerificationResult, UserConsentVerifier};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::WinRT::{
        IUserConsentVerifierInterop, RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize,
    };
    use windows::core::{HSTRING, factory};
    use windows_future::IAsyncOperation;

    struct Apartment;
    impl Drop for Apartment {
        fn drop(&mut self) {
            // This guard is dropped on the worker thread that initialized WinRT.
            unsafe { RoUninitialize() };
        }
    }

    // Initialize WinRT on this worker before creating the consent verifier.
    unsafe { RoInitialize(RO_INIT_MULTITHREADED) }.map_err(|_| unavailable())?;
    let _apartment = Apartment;
    let verifier =
        factory::<UserConsentVerifier, IUserConsentVerifierInterop>().map_err(|_| unavailable())?;
    // The HWND comes from the running Iced window. Windows validates the handle
    // and owns the prompt and the lifetime of the returned operation.
    let operation: IAsyncOperation<UserConsentVerificationResult> = unsafe {
        verifier.RequestVerificationForWindowAsync(
            HWND(hwnd as *mut std::ffi::c_void),
            &HSTRING::from("Unlock Hidden albums in Proton Gallery"),
        )
    }
    .map_err(|_| unavailable())?;
    match operation.join().map_err(|_| unavailable())? {
        UserConsentVerificationResult::Verified => Ok(()),
        UserConsentVerificationResult::Canceled => {
            Err("System authentication was canceled.".into())
        }
        UserConsentVerificationResult::RetriesExhausted => {
            Err("System authentication was denied.".into())
        }
        _ => Err(unavailable()),
    }
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
pub fn authenticate(request: u64, generation: Generation) -> Task<Message> {
    Task::done(Message::HiddenUnlocked(
        request,
        generation,
        Err("System authentication is unavailable on this platform.".into()),
    ))
}
