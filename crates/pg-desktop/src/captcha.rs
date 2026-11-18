//! A browser-hosted CAPTCHA bridge. Only the verification token crosses back,
//! by an explicit copy/paste; passwords and session credentials never enter it.
use std::io::Write;

fn page(url: &str) -> Result<String, String> {
    if !url.starts_with("https://verify.proton.me/?") || url.len() > 16_384 {
        return Err("Untrusted verification page.".into());
    }
    let source = serde_json::to_string(url)
        .map_err(|e| e.to_string())?
        .replace('<', "\\u003c");
    Ok(format!(
        r#"<!doctype html><meta charset="utf-8">
<title>Proton Gallery verification</title>
<style>body{{font:18px system-ui;max-width:800px;margin:24px auto;padding:16px}}iframe{{width:100%;height:520px;border:0}}textarea{{width:100%;height:100px}}</style>
<h1>Verify your sign-in</h1><p>Complete Proton’s CAPTCHA. Then copy the token below and paste it into Proton Gallery.</p>
<iframe title="Proton verification" referrerpolicy="no-referrer"></iframe>
<label>Verification token<textarea readonly placeholder="The token appears here after verification"></textarea></label>
<script>
const frame=document.querySelector('iframe');frame.src={source};
window.addEventListener('message', event=>{{
 if(event.origin!=='https://verify.proton.me'||event.source!==frame.contentWindow)return;
 try{{const data=typeof event.data==='string'?JSON.parse(event.data):event.data;
 if(data.type==='HUMAN_VERIFICATION_SUCCESS'&&data.payload.type==='captcha'&&
 typeof data.payload.token==='string'&&data.payload.token.length<=8192){{
 const output=document.querySelector('textarea');output.value=data.payload.token;output.focus();output.select();
 }} }}catch{{}}
}});
</script>"#
    ))
}

pub fn open(url: &str) -> Result<tempfile::TempPath, String> {
    let html = page(url)?;
    let mut file = tempfile::Builder::new()
        .prefix("proton-gallery-verification-")
        .suffix(".html")
        .tempfile()
        .map_err(|e| e.to_string())?;
    file.write_all(html.as_bytes()).map_err(|e| e.to_string())?;
    let path = file.into_temp_path();
    #[cfg(target_os = "linux")]
    let result = std::process::Command::new("xdg-open").arg(&path).spawn();
    #[cfg(target_os = "windows")]
    let result = std::process::Command::new("explorer.exe")
        .arg(&path)
        .spawn();
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    let result = std::process::Command::new("open").arg(&path).spawn();
    result.map_err(|_| {
        "Couldn’t open your browser. Check the default browser setting.".to_string()
    })?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn the_bridge_accepts_only_protons_verification_origin_and_escapes_script_content() {
        for url in [
            "http://verify.proton.me/?token=x",
            "https://verify.proton.me.evil/?token=x",
            "https://verify.proton.me@evil/?token=x",
        ] {
            assert!(page(url).is_err());
        }
        let html = page("https://verify.proton.me/?token=</script>").unwrap();
        assert!(!html.contains("token=</script>"));
        assert!(html.contains("event.source!==frame.contentWindow"));
    }
}
